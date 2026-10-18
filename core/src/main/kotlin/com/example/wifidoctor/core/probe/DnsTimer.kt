package com.example.wifidoctor.core.probe

import com.example.wifidoctor.core.model.DnsResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** Which resolver to time. */
sealed interface Resolver {
    val label: String

    /** The OS resolver (InetAddress), i.e. what apps actually experience. May hit a cache. */
    data object System : Resolver {
        override val label = "system"
    }

    /** A plain UDP DNS query to a specific server, bypassing any cache in the OS. */
    data class Udp(val server: String) : Resolver {
        override val label = server
    }
}

interface DnsTimer {
    /** Returns one entry per domain: lookup time in ms, or null if it failed. */
    suspend fun time(resolver: Resolver, domains: List<String>, timeoutMs: Int): List<Double?>
}

class DefaultDnsTimer(
    private val binding: NetworkBinding = NetworkBinding.Default,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nanoTime: () -> Long = System::nanoTime,
    private val port: Int = 53,
) : DnsTimer {

    override suspend fun time(resolver: Resolver, domains: List<String>, timeoutMs: Int): List<Double?> =
        withContext(dispatcher) {
            domains.map { domain ->
                coroutineContext.ensureActive()
                when (resolver) {
                    Resolver.System -> timeSystem(domain)
                    is Resolver.Udp -> timeUdp(resolver.server, domain, timeoutMs)
                }
            }
        }

    private fun timeSystem(domain: String): Double? {
        val start = nanoTime()
        return try {
            binding.lookup(domain)
            (nanoTime() - start) / 1_000_000.0
        } catch (_: Exception) {
            null
        }
    }

    private fun timeUdp(server: String, domain: String, timeoutMs: Int): Double? {
        val id = Random.nextInt(0, 0x10000)
        val query = DnsPacket.query(id, domain)
        return try {
            DatagramSocket().use { socket ->
                binding.bind(socket)
                socket.soTimeout = timeoutMs
                val address = InetAddress.getByName(server) // numeric IP: no lookup happens
                val start = nanoTime()
                socket.send(DatagramPacket(query, query.size, address, port))
                val buffer = ByteArray(1500)
                var elapsed: Double? = null
                // Stray datagrams are skipped; receive() throws once the timeout passes.
                while (elapsed == null) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    if (DnsPacket.isResponseTo(buffer, packet.length, id)) {
                        elapsed = (nanoTime() - start) / 1_000_000.0
                    }
                }
                elapsed
            }
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}

/** Minimal DNS wire format: enough to send an A query and recognise its response. */
object DnsPacket {
    fun query(id: Int, domain: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id shr 8 and 0xFF); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00) // standard query, recursion desired
        out.write(0); out.write(1) // QDCOUNT
        repeat(6) { out.write(0) } // ANCOUNT, NSCOUNT, ARCOUNT
        domain.trimEnd('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) { "Invalid DNS label in $domain" }
            out.write(bytes.size); out.write(bytes)
        }
        out.write(0)
        out.write(0); out.write(1) // QTYPE A
        out.write(0); out.write(1) // QCLASS IN
        return out.toByteArray()
    }

    /** True for a response (QR bit set) with matching ID, regardless of RCODE: NXDOMAIN is still an answer. */
    fun isResponseTo(data: ByteArray, length: Int, id: Int): Boolean {
        if (length < 12) return false
        val responseId = (data[0].toInt() and 0xFF shl 8) or (data[1].toInt() and 0xFF)
        val isResponse = data[2].toInt() and 0x80 != 0
        return isResponse && responseId == id
    }

    fun rcode(data: ByteArray): Int = data[3].toInt() and 0x0F
}

/** Times the same domains through the system resolver and a reference resolver. */
class DnsComparison(private val timer: DnsTimer) {
    suspend fun compare(system: Resolver, reference: Resolver, domains: List<String>, timeoutMs: Int): DnsResult {
        val sys = timer.time(system, domains, timeoutMs)
        val ref = timer.time(reference, domains, timeoutMs)
        return DnsResult(
            systemServer = system.label,
            referenceServer = reference.label,
            lookups = domains.size,
            systemAvgMs = sys.filterNotNull().takeIf { it.isNotEmpty() }?.average(),
            systemFailures = sys.count { it == null },
            referenceAvgMs = ref.filterNotNull().takeIf { it.isNotEmpty() }?.average(),
            referenceFailures = ref.count { it == null },
        )
    }
}
