package io.github.jesperlund.wifidoctor.core.probe

import io.github.jesperlund.wifidoctor.core.model.NatBehavior
import io.github.jesperlund.wifidoctor.core.model.ProbeMethod
import io.github.jesperlund.wifidoctor.core.model.RealtimeResult
import io.github.jesperlund.wifidoctor.core.model.StunServer
import io.github.jesperlund.wifidoctor.core.model.UdpProbeResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** Tests the UDP path that voice/video calls use. */
interface RealtimeTester {
    suspend fun test(servers: List<StunServer>, samples: Int, intervalMs: Long, timeoutMs: Int): RealtimeResult
}

/**
 * Sends STUN Binding requests (RFC 5389), the same packets Teams/WebRTC use to set up a call,
 * at a steady interval like an audio stream. Each response gives one UDP round trip plus the
 * public address/port the NAT mapped us to. Using one local socket for every server reveals
 * whether the NAT keeps the same mapping per destination (good) or not (symmetric: calls must
 * be relayed).
 */
class StunRealtimeTester(
    private val binding: NetworkBinding = NetworkBinding.Default,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : RealtimeTester {

    override suspend fun test(servers: List<StunServer>, samples: Int, intervalMs: Long, timeoutMs: Int): RealtimeResult =
        withContext(dispatcher) {
            val results = DatagramSocket().use { socket ->
                binding.bind(socket)
                servers.map { server ->
                    coroutineContext.ensureActive()
                    testServer(socket, server, samples, intervalMs, timeoutMs)
                }
            }
            RealtimeResult(results, natBehavior(results))
        }

    private suspend fun testServer(
        socket: DatagramSocket,
        server: StunServer,
        samples: Int,
        intervalMs: Long,
        timeoutMs: Int,
    ): UdpProbeResult {
        val address = runCatching {
            binding.lookup(server.host).firstOrNull { it is Inet4Address } ?: binding.lookup(server.host).first()
        }.getOrElse {
            return UdpProbeResult(server, ProbeMath.stats(server.label, List(samples) { null }, ProbeMethod.UDP), null, error = "DNS lookup failed")
        }
        val target = InetSocketAddress(address, server.port)
        val rtts = mutableListOf<Double?>()
        var mapped: String? = null
        for (i in 0 until samples) {
            coroutineContext.ensureActive()
            val started = nanoTime()
            val response = exchange(socket, target, timeoutMs)
            rtts += response?.let { (nanoTime() - started) / 1_000_000.0 }
            response?.mapped?.let { mapped = it }
            // Three silent tries in a row: the server or the port is unreachable, stop early.
            if (i == 2 && rtts.all { it == null }) {
                rtts += List(samples - rtts.size) { null }
                break
            }
            val spent = (nanoTime() - started) / 1_000_000
            if (i < samples - 1 && spent < intervalMs) sleep(intervalMs - spent)
        }
        return UdpProbeResult(server, ProbeMath.stats(server.label, rtts, ProbeMethod.UDP), mapped)
    }

    private class Response(val mapped: String?)

    private fun exchange(socket: DatagramSocket, target: InetSocketAddress, timeoutMs: Int): Response? {
        val txId = Random.nextBytes(12)
        val request = StunMessage.bindingRequest(txId)
        return try {
            socket.soTimeout = timeoutMs
            socket.send(DatagramPacket(request, request.size, target))
            val deadline = nanoTime() + timeoutMs * 1_000_000L
            val buffer = ByteArray(1024)
            while (true) {
                val remainingMs = ((deadline - nanoTime()) / 1_000_000).toInt()
                if (remainingMs <= 0) return null
                socket.soTimeout = remainingMs
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val parsed = StunMessage.parseBindingResponse(buffer, packet.length, txId) ?: continue // stray/late reply
                return Response(parsed.ifEmpty { null })
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** Same local socket, different servers: a changing public port means symmetric NAT. */
        fun natBehavior(results: List<UdpProbeResult>): NatBehavior {
            val ports = results.mapNotNull { it.mappedAddress?.substringAfterLast(':') }.distinct()
            val answered = results.count { it.mappedAddress != null }
            return when {
                answered < 2 -> NatBehavior.UNKNOWN
                ports.size == 1 -> NatBehavior.ENDPOINT_INDEPENDENT
                else -> NatBehavior.SYMMETRIC
            }
        }
    }
}

/** Minimal STUN encoding/decoding: Binding request and (XOR-)MAPPED-ADDRESS. */
object StunMessage {
    const val MAGIC_COOKIE = 0x2112A442
    private const val BINDING_REQUEST = 0x0001
    private const val BINDING_SUCCESS = 0x0101
    private const val ATTR_MAPPED_ADDRESS = 0x0001
    private const val ATTR_XOR_MAPPED_ADDRESS = 0x0020

    fun bindingRequest(transactionId: ByteArray): ByteArray {
        require(transactionId.size == 12)
        return ByteBuffer.allocate(20)
            .putShort(BINDING_REQUEST.toShort())
            .putShort(0) // no attributes
            .putInt(MAGIC_COOKIE)
            .put(transactionId)
            .array()
    }

    /**
     * Returns the mapped "ip:port" for a success response matching [transactionId], "" if it
     * matches but carries no address, or null if the packet is not our response.
     */
    fun parseBindingResponse(data: ByteArray, length: Int, transactionId: ByteArray): String? {
        if (length < 20) return null
        val buf = ByteBuffer.wrap(data, 0, length)
        val type = buf.short.toInt() and 0xFFFF
        val bodyLength = buf.short.toInt() and 0xFFFF
        val cookie = buf.int
        val tx = ByteArray(12).also { buf.get(it) }
        if (type != BINDING_SUCCESS || cookie != MAGIC_COOKIE || !tx.contentEquals(transactionId)) return null
        val end = minOf(length, 20 + bodyLength)
        var mapped: String? = null
        while (buf.position() + 4 <= end) {
            val attrType = buf.short.toInt() and 0xFFFF
            val attrLen = buf.short.toInt() and 0xFFFF
            val valueStart = buf.position()
            if (valueStart + attrLen > end) break
            if ((attrType == ATTR_XOR_MAPPED_ADDRESS || attrType == ATTR_MAPPED_ADDRESS) && attrLen >= 8) {
                val xor = attrType == ATTR_XOR_MAPPED_ADDRESS
                buf.get() // reserved
                val family = buf.get().toInt()
                var port = buf.short.toInt() and 0xFFFF
                if (xor) port = port xor (MAGIC_COOKIE ushr 16)
                if (family == 0x01) {
                    val raw = ByteArray(4).also { buf.get(it) }
                    if (xor) {
                        val c = ByteBuffer.allocate(4).putInt(MAGIC_COOKIE).array()
                        for (i in 0..3) raw[i] = (raw[i].toInt() xor c[i].toInt()).toByte()
                    }
                    val address = InetAddress.getByAddress(raw).hostAddress
                    if (xor || mapped == null) mapped = "$address:$port"
                }
            }
            // Attributes are padded to 4 bytes.
            buf.position(valueStart + ((attrLen + 3) / 4) * 4)
        }
        return mapped ?: ""
    }
}
