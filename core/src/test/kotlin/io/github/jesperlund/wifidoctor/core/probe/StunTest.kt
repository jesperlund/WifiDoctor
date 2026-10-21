package io.github.jesperlund.wifidoctor.core.probe

import io.github.jesperlund.wifidoctor.core.model.NatBehavior
import io.github.jesperlund.wifidoctor.core.model.ProbeMethod
import io.github.jesperlund.wifidoctor.core.model.StunServer
import io.github.jesperlund.wifidoctor.core.model.UdpProbeResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class StunTest {

    private val tx = ByteArray(12) { it.toByte() }

    /** Builds a Binding success response carrying XOR-MAPPED-ADDRESS for [ip]:[port]. */
    private fun response(txId: ByteArray, ip: String, port: Int, xor: Boolean = true): ByteArray {
        val addr = InetAddress.getByName(ip).address
        val cookie = ByteBuffer.allocate(4).putInt(StunMessage.MAGIC_COOKIE).array()
        val attr = ByteBuffer.allocate(12)
            .putShort((if (xor) 0x0020 else 0x0001).toShort())
            .putShort(8)
            .put(0)
            .put(1)
            .putShort((if (xor) port xor (StunMessage.MAGIC_COOKIE ushr 16) else port).toShort())
            .put(ByteArray(4) { i -> if (xor) (addr[i].toInt() xor cookie[i].toInt()).toByte() else addr[i] })
            .array()
        return ByteBuffer.allocate(20 + attr.size)
            .putShort(0x0101)
            .putShort(attr.size.toShort())
            .putInt(StunMessage.MAGIC_COOKIE)
            .put(txId)
            .put(attr)
            .array()
    }

    @Test
    fun `binding request layout`() {
        val req = StunMessage.bindingRequest(tx)
        assertEquals(20, req.size)
        val buf = ByteBuffer.wrap(req)
        assertEquals(0x0001, buf.short.toInt())
        assertEquals(0, buf.short.toInt())
        assertEquals(StunMessage.MAGIC_COOKIE, buf.int)
    }

    @Test
    fun `parses xor and plain mapped addresses`() {
        val xor = response(tx, "203.0.113.7", 54321)
        assertEquals("203.0.113.7:54321", StunMessage.parseBindingResponse(xor, xor.size, tx))
        val plain = response(tx, "198.51.100.1", 1000, xor = false)
        assertEquals("198.51.100.1:1000", StunMessage.parseBindingResponse(plain, plain.size, tx))
    }

    @Test
    fun `ignores other transactions, requests and short packets`() {
        val r = response(tx, "203.0.113.7", 1)
        assertNull(StunMessage.parseBindingResponse(r, r.size, ByteArray(12)))
        val req = StunMessage.bindingRequest(tx)
        assertNull(StunMessage.parseBindingResponse(req, req.size, tx))
        assertNull(StunMessage.parseBindingResponse(r, 10, tx))
    }

    @Test
    fun `nat behaviour from mappings`() {
        fun res(mapped: String?) = UdpProbeResult(StunServer("h", 1), ProbeMath.stats("h", listOf(1.0)), mapped)
        assertEquals(NatBehavior.ENDPOINT_INDEPENDENT, StunRealtimeTester.natBehavior(listOf(res("1.2.3.4:5000"), res("1.2.3.4:5000"))))
        assertEquals(NatBehavior.SYMMETRIC, StunRealtimeTester.natBehavior(listOf(res("1.2.3.4:5000"), res("1.2.3.4:5001"))))
        assertEquals(NatBehavior.UNKNOWN, StunRealtimeTester.natBehavior(listOf(res("1.2.3.4:5000"), res(null))))
    }

    /** Minimal STUN server on loopback that echoes the client's address. */
    private fun stunServer(): DatagramSocket {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            runCatching {
                while (true) {
                    val buf = ByteArray(512)
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    val txId = buf.copyOfRange(8, 20)
                    val reply = response(txId, p.address.hostAddress, p.port)
                    socket.send(DatagramPacket(reply, reply.size, p.socketAddress))
                }
            }
        }
        return socket
    }

    @Test
    fun `measures udp round trips against local stun servers`() = runBlocking {
        stunServer().use { a ->
            stunServer().use { b ->
                val servers = listOf(StunServer("127.0.0.1", a.localPort, "A"), StunServer("127.0.0.1", b.localPort, "B"))
                val result = StunRealtimeTester(sleep = {}).test(servers, samples = 5, intervalMs = 1, timeoutMs = 500)
                assertEquals(2, result.servers.size)
                assertTrue(result.servers.all { it.stats.received == 5 && it.stats.method == ProbeMethod.UDP })
                assertTrue(result.servers.all { it.mappedAddress!!.startsWith("127.0.0.1:") })
                // One local socket for both servers, no NAT in between: same mapping.
                assertEquals(NatBehavior.ENDPOINT_INDEPENDENT, result.nat)
                assertTrue(result.anyReachable)
            }
        }
    }

    @Test
    fun `silent servers are given up on after three tries`() = runBlocking {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { silent ->
            val result = StunRealtimeTester(sleep = {}).test(listOf(StunServer("127.0.0.1", silent.localPort)), 15, 1, 50)
            val s = result.servers.single()
            assertEquals(15, s.stats.sent)
            assertEquals(0, s.stats.received)
            assertFalse(result.anyReachable)
            assertNull(result.best)
        }
    }

    @Test
    fun `stun server specs parse`() {
        assertEquals(StunServer("stun.example.com", 3478), StunServer.parse("stun.example.com"))
        assertEquals(19302, StunServer.parse("stun.l.google.com:19302")!!.port)
        assertNull(StunServer.parse("host:notaport"))
        assertNull(StunServer.parse(""))
    }

    @Test
    fun percentiles() {
        val v = (1..100).map { it.toDouble() }
        assertEquals(50.0, ProbeMath.percentile(v, 50.0)!!, 0.0)
        assertEquals(95.0, ProbeMath.percentile(v, 95.0)!!, 0.0)
        assertEquals(100.0, ProbeMath.percentile(v, 100.0)!!, 0.0)
        assertEquals(7.0, ProbeMath.percentile(listOf(7.0), 99.0)!!, 0.0)
        assertNull(ProbeMath.percentile(emptyList(), 50.0))
    }
}
