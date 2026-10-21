package io.github.jesperlund.wifidoctor.core.probe

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

class ThroughputTesterTest {
    private lateinit var server: HttpServer
    private val slowClosed = AtomicBoolean(false)
    private val uploaded = java.util.concurrent.atomic.AtomicLong(-1)
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/file") { ex ->
            val body = ByteArray(2 * 1024 * 1024)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/missing") { ex ->
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }
        server.createContext("/slow") { ex ->
            ex.sendResponseHeaders(200, 0) // chunked, endless trickle
            try {
                ex.responseBody.use { out ->
                    repeat(10_000) {
                        out.write(ByteArray(1024)); out.flush(); Thread.sleep(20)
                    }
                }
            } catch (_: Exception) {
                slowClosed.set(true)
            }
        }
        server.createContext("/up") { ex ->
            var total = 0L
            val buf = ByteArray(64 * 1024)
            ex.requestBody.use { body -> while (true) { val n = body.read(buf); if (n < 0) break; total += n } }
            uploaded.set(total)
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        server.createContext("/up-denied") { ex ->
            ex.requestBody.use { it.readBytes() }
            ex.sendResponseHeaders(403, -1)
            ex.close()
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    @Test
    fun `downloads a file and reports Mbps with progress`() = runBlocking {
        val progress = mutableListOf<ThroughputProgress>()
        val r = HttpThroughputTester().run("$base/file", 10_000) { progress += it }
        assertEquals(2L * 1024 * 1024, r.bytes)
        assertNotNull(r.mbps)
        assertNull(r.error)
        assertEquals(1f, progress.last().fraction)
    }

    @Test
    fun `stops at the byte cap`() = runBlocking {
        val r = HttpThroughputTester(maxBytes = 256 * 1024).run("$base/file", 10_000)
        assertTrue(r.bytes in 256 * 1024 until 2 * 1024 * 1024)
    }

    @Test
    fun `uploads for the given time and reports Mbps after warm-up`() = runBlocking {
        val progress = mutableListOf<ThroughputProgress>()
        val r = HttpThroughputTester(maxBytes = Long.MAX_VALUE).upload("$base/up", 1_500) { progress += it }
        assertNull(r.error)
        assertNotNull(r.mbps)
        assertTrue(r.durationMs in 1_400..5_000)
        assertTrue(progress.all { it.upload })
        assertEquals(r.bytes, uploaded.get()) // the server received every byte
    }

    @Test
    fun `upload errors are reported`() = runBlocking {
        assertEquals("HTTP 403", HttpThroughputTester().upload("$base/up-denied", 300).error)
        assertNotNull(HttpThroughputTester(connectTimeoutMs = 500).upload("http://127.0.0.1:1/x", 300).error)
    }

    @Test
    fun `http errors are reported`() = runBlocking {
        val r = HttpThroughputTester().run("$base/missing", 10_000)
        assertEquals("HTTP 404", r.error)
        assertNull(r.mbps)
    }

    @Test
    fun `connection failures are reported, not thrown`() = runBlocking {
        val r = HttpThroughputTester(connectTimeoutMs = 500).run("http://127.0.0.1:1/x", 1_000)
        assertNotNull(r.error)
    }

    @Test
    fun `respects the duration limit`() = runBlocking {
        val r = withTimeout(10_000) { HttpThroughputTester().run("$base/slow", 500) }
        assertTrue("took ${r.durationMs} ms", r.durationMs in 450..3_000)
        assertTrue(r.bytes > 0)
    }

    @Test
    fun `cancellation stops the download`() = runBlocking {
        val job = async(Dispatchers.Default) { HttpThroughputTester().run("$base/slow", 60_000) }
        delay(300)
        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }
}
