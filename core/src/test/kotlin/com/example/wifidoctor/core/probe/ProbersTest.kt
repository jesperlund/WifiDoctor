package com.example.wifidoctor.core.probe

import com.example.wifidoctor.core.model.ProbeMethod
import com.example.wifidoctor.core.model.ProbeStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import javax.net.SocketFactory
import kotlin.concurrent.thread

class ProbersTest {

    // ---- ping output parsing -----------------------------------------------------------------

    private val pingOutput = """
        PING 192.168.1.1 (192.168.1.1) 56(84) bytes of data.
        64 bytes from 192.168.1.1: icmp_seq=1 ttl=64 time=3.21 ms
        64 bytes from 192.168.1.1: icmp_seq=2 ttl=64 time=4.80 ms
        64 bytes from 192.168.1.1: icmp_seq=4 ttl=64 time=12.0 ms
        64 bytes from 192.168.1.1: icmp_seq=4 ttl=64 time=13.0 ms (DUP!)

        --- 192.168.1.1 ping statistics ---
        4 packets transmitted, 3 received, 25% packet loss, time 3004ms
    """.trimIndent()

    @Test
    fun `ping parser maps replies to sequence numbers`() {
        assertEquals(listOf(3.21, 4.80, null, 12.0), PingOutputParser.samples(pingOutput, 4))
    }

    @Test
    fun `ping parser handles sub-millisecond and zero-based output`() {
        val out = "64 bytes from 1.1.1.1: icmp_seq=0 ttl=57 time<1 ms\n64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=0.9 ms"
        assertEquals(listOf(1.0, 0.9, null), PingOutputParser.samples(out, 3))
    }

    @Test
    fun `ping parser with no replies`() {
        assertEquals(listOf(null, null), PingOutputParser.samples("connect: Network is unreachable", 2))
    }

    @Test
    fun `missing ping binary reports method NONE`() = runBlocking {
        val p = PingProber(pingPath = "/definitely/not/ping")
        assertFalse(p.isAvailable())
        assertEquals(ProbeMethod.NONE, p.probe("1.1.1.1", 3, 500).method)
    }

    // ---- TCP connect -------------------------------------------------------------------------

    @Test
    fun `tcp prober times handshakes to an open port`() = runBlocking {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            thread(isDaemon = true) { runCatching { while (true) server.accept().close() } }
            val prober = TcpConnectProber(ports = listOf(server.localPort), intervalMs = 0, dispatcher = Dispatchers.IO)
            val s = prober.probe("127.0.0.1", 5, 1_000)
            assertEquals(5, s.sent)
            assertEquals(5, s.received)
            assertNotNull(s.avgMs)
        }
    }

    @Test
    fun `connection refused counts as a reply`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort } // now closed: the OS answers with RST
        val prober = TcpConnectProber(ports = listOf(port), intervalMs = 0)
        assertEquals(3, prober.probe("127.0.0.1", 3, 1_000).received)
    }

    @Test
    fun `timeouts on every port mean total loss`() = runBlocking {
        val prober = TcpConnectProber(binding = TimeoutBinding, ports = listOf(53, 443, 80), intervalMs = 0)
        val s = prober.probe("10.255.255.1", 4, 50)
        assertEquals(4, s.sent)
        assertTrue(s.allLost)
    }

    private object TimeoutBinding : NetworkBinding by NetworkBinding.Default {
        override fun socketFactory(): SocketFactory = object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun connect(endpoint: SocketAddress?, timeout: Int) = throw SocketTimeoutException()
            }
            override fun createSocket(host: String?, port: Int) = throw UnsupportedOperationException()
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) = throw UnsupportedOperationException()
            override fun createSocket(host: InetAddress?, port: Int) = throw UnsupportedOperationException()
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) = throw UnsupportedOperationException()
        }
    }

    // ---- fallback ----------------------------------------------------------------------------

    private class FixedProber(val stats: ProbeStats) : LatencyProber {
        var calls = 0
        override suspend fun probe(host: String, count: Int, timeoutMs: Int): ProbeStats = stats.also { calls++ }
    }

    @Test
    fun `fallback is used only when the primary got nothing`() = runBlocking {
        val good = ProbeMath.stats("h", listOf(1.0, 2.0))
        val lost = ProbeMath.stats("h", listOf(null, null))
        val ping = ProbeMath.stats("h", listOf(5.0, 6.0), ProbeMethod.PING)

        val secondary = FixedProber(ping)
        assertSame(good, FallbackProber(FixedProber(good), secondary).probe("h", 2, 100))
        assertEquals(0, secondary.calls)
        assertSame(ping, FallbackProber(FixedProber(lost), secondary).probe("h", 2, 100))
        assertSame(lost, FallbackProber(FixedProber(lost), null).probe("h", 2, 100))
        val unavailable = FixedProber(ProbeStats("h", 0, 0, null, null, null, null, ProbeMethod.NONE))
        assertSame(lost, FallbackProber(FixedProber(lost), unavailable).probe("h", 2, 100))
    }

    // ---- DNS ---------------------------------------------------------------------------------

    @Test
    fun `dns query encodes the name`() {
        val q = DnsPacket.query(0x1234, "example.com")
        assertEquals(0x12, q[0].toInt())
        assertEquals(0x34, q[1].toInt())
        assertEquals(7, q[12].toInt())
        assertEquals("example", String(q, 13, 7))
        assertEquals(3, q[20].toInt())
        assertEquals(12 + 13 + 4, q.size)
    }

    @Test
    fun `dns response matching`() {
        val q = DnsPacket.query(42, "a.b")
        assertFalse("a query is not a response", DnsPacket.isResponseTo(q, q.size, 42))
        val r = q.copyOf().also { it[2] = (it[2].toInt() or 0x80).toByte(); it[3] = 3 }
        assertTrue(DnsPacket.isResponseTo(r, r.size, 42))
        assertFalse(DnsPacket.isResponseTo(r, r.size, 43))
        assertFalse(DnsPacket.isResponseTo(r, 5, 42))
        assertEquals(3, DnsPacket.rcode(r)) // NXDOMAIN still counts as answered
    }

    @Test
    fun `udp dns timer against a local responder`() = runBlocking {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            thread(isDaemon = true) {
                runCatching {
                    while (true) {
                        val buf = ByteArray(512)
                        val p = DatagramPacket(buf, buf.size)
                        server.receive(p)
                        buf[2] = (buf[2].toInt() or 0x80).toByte()
                        server.send(DatagramPacket(buf, p.length, p.socketAddress))
                    }
                }
            }
            val timer = DefaultDnsTimer(port = server.localPort)
            val times = timer.time(Resolver.Udp("127.0.0.1"), listOf("example.com", "github.com"), 1_000)
            assertEquals(2, times.size)
            assertTrue(times.all { it != null })
        }
    }

    @Test
    fun `udp dns timer times out against a silent server`() = runBlocking {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { silent ->
            val timer = DefaultDnsTimer(port = silent.localPort)
            assertEquals(listOf<Double?>(null), timer.time(Resolver.Udp("127.0.0.1"), listOf("example.com"), 100))
        }
    }

    @Test
    fun `dns comparison summarises both resolvers`() = runBlocking {
        val fake = object : DnsTimer {
            override suspend fun time(resolver: Resolver, domains: List<String>, timeoutMs: Int): List<Double?> =
                if (resolver == Resolver.System) listOf(100.0, null, 200.0) else listOf(10.0, 20.0, 30.0)
        }
        val r = DnsComparison(fake).compare(Resolver.System, Resolver.Udp("1.1.1.1"), listOf("a", "b", "c"), 100)
        assertEquals(150.0, r.systemAvgMs!!, 1e-9)
        assertEquals(1, r.systemFailures)
        assertEquals(20.0, r.referenceAvgMs!!, 1e-9)
        assertEquals("system", r.systemServer)
        assertEquals("1.1.1.1", r.referenceServer)
        assertNull(DnsComparison(object : DnsTimer {
            override suspend fun time(resolver: Resolver, domains: List<String>, timeoutMs: Int) = listOf<Double?>(null)
        }).compare(Resolver.System, Resolver.System, listOf("a"), 1).systemAvgMs)
    }
}
