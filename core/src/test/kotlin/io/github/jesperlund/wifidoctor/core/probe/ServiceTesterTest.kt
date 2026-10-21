package io.github.jesperlund.wifidoctor.core.probe

import io.github.jesperlund.wifidoctor.core.model.EndpointFailure
import io.github.jesperlund.wifidoctor.core.model.ServiceEndpoint
import io.github.jesperlund.wifidoctor.core.model.ServiceTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class ServiceTesterTest {

    /** Resolves every name to [address] instead of using DNS. */
    private class FixedDns(val address: String) : NetworkBinding by NetworkBinding.Default {
        override fun lookup(host: String): Array<InetAddress> = arrayOf(InetAddress.getByName(address))
    }

    /** Plain-HTTP tester against loopback: TLS is replaced by the identity, filter detection is off. */
    private fun plain(dns: String = "127.0.0.1") =
        HttpsServiceTester(binding = FixedDns(dns), secure = { s, _, _, _ -> s }, treatLocalAnswersAsBlocked = false)

    private fun server(reply: (Socket) -> Unit): ServerSocket {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            runCatching { while (true) { val s = server.accept(); thread(isDaemon = true) { runCatching { s.use(reply) } } } }
        }
        return server
    }

    private val http200: (Socket) -> Unit = { s ->
        s.getInputStream().bufferedReader().let { r -> while (r.readLine()?.isNotEmpty() == true) Unit }
        s.getOutputStream().write("HTTP/1.1 301 Moved\r\nContent-Length: 0\r\n\r\n".toByteArray())
    }

    @Test
    fun `times every phase of a successful request`() = runBlocking {
        server(http200).use { srv ->
            val r = plain().probe(ServiceEndpoint("example.test", "Web", srv.localPort), 2_000)
            assertTrue(r.error, r.ok)
            assertEquals(301, r.httpStatus)
            assertEquals(listOf("127.0.0.1"), r.addresses)
            assertNotNull(r.dnsMs); assertNotNull(r.connectMs); assertNotNull(r.tlsMs); assertNotNull(r.firstByteMs)
            assertNotNull(r.totalMs)
        }
    }

    @Test
    fun `filter answers are reported as DNS blocking`() = runBlocking {
        val r = HttpsServiceTester(binding = FixedDns("0.0.0.0")).probe(ServiceEndpoint("www.instagram.com", "Web"), 500)
        assertEquals(EndpointFailure.DNS_BLOCKED, r.failure)
        assertEquals(listOf("0.0.0.0"), r.addresses)
        assertTrue(HttpsServiceTester.isFilterAnswer(InetAddress.getByName("192.168.1.1")))
        assertFalse(HttpsServiceTester.isFilterAnswer(InetAddress.getByName("157.240.1.174")))
    }

    @Test
    fun `lookup failures are reported`() = runBlocking {
        val failing = object : NetworkBinding by NetworkBinding.Default {
            override fun lookup(host: String): Array<InetAddress> = throw java.net.UnknownHostException(host)
        }
        val r = HttpsServiceTester(binding = failing).probe(ServiceEndpoint("nope.invalid", "Web"), 500)
        assertEquals(EndpointFailure.DNS_FAILED, r.failure)
        assertNull(r.connectMs)
    }

    @Test
    fun `refused connections are reported`() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        val r = plain().probe(ServiceEndpoint("example.test", "Web", closedPort), 500)
        assertEquals(EndpointFailure.CONNECT_FAILED, r.failure)
        assertNotNull(r.dnsMs)
    }

    @Test
    fun `handshake failures are reported as TLS failures`() = runBlocking {
        // A server that is not speaking TLS: the real TLS upgrade must fail.
        server { s -> s.getOutputStream().write("SSH-2.0-not-tls\r\n".toByteArray()); Thread.sleep(200) }.use { srv ->
            val tester = HttpsServiceTester(binding = FixedDns("127.0.0.1"), treatLocalAnswersAsBlocked = false)
            val r = tester.probe(ServiceEndpoint("example.test", "Web", srv.localPort), 1_000)
            assertEquals(EndpointFailure.TLS_FAILED, r.failure)
            assertNotNull(r.connectMs)
        }
    }

    @Test
    fun `silent servers time out without a response`() = runBlocking {
        server { Thread.sleep(2_000) }.use { srv ->
            val r = plain().probe(ServiceEndpoint("example.test", "Web", srv.localPort), 300)
            assertEquals(EndpointFailure.HTTP_FAILED, r.failure)
        }
    }

    @Test
    fun `checks every endpoint of every service plus the reference`() = runBlocking {
        server(http200).use { srv ->
            val closed = ServerSocket(0).use { it.localPort }
            val target = ServiceTarget(
                "x", "X",
                listOf(ServiceEndpoint("a.test", "A", srv.localPort), ServiceEndpoint("b.test", "B", closed)),
            )
            val check = plain().check(listOf(target), ServiceEndpoint("ref.test", "Ref", srv.localPort), 1_000)
            val r = check.result("x")!!
            assertEquals(1, r.failed.size)
            assertFalse(r.allFailed)
            assertTrue(check.baseline!!.ok)
            assertTrue(check.summary.startsWith("X:PARTIAL:"))
        }
    }
}
