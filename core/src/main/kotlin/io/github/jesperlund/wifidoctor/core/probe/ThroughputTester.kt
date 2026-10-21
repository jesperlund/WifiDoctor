package io.github.jesperlund.wifidoctor.core.probe

import io.github.jesperlund.wifidoctor.core.model.ThroughputResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class ThroughputProgress(
    val bytes: Long,
    val elapsedMs: Long,
    val mbps: Double?,
    val fraction: Float,
    val upload: Boolean = false,
)

interface ThroughputTester {
    /** Download test. */
    suspend fun run(url: String, maxDurationMs: Long, onProgress: (ThroughputProgress) -> Unit = {}): ThroughputResult

    /** Upload test; implementations without one report "not supported". */
    suspend fun upload(url: String, maxDurationMs: Long, onProgress: (ThroughputProgress) -> Unit = {}): ThroughputResult =
        ThroughputResult(url, 0, 0, null, error = "Upload test not supported")
}

/**
 * Downloads [url] until it ends, [maxBytes] have arrived or [maxDurationMs] has elapsed, and reports
 * the average rate. Cancelling the calling coroutine stops the download promptly.
 */
class HttpThroughputTester(
    private val binding: NetworkBinding = NetworkBinding.Default,
    private val maxBytes: Long = 100L * 1024 * 1024,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 5_000,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : ThroughputTester {

    override suspend fun run(url: String, maxDurationMs: Long, onProgress: (ThroughputProgress) -> Unit): ThroughputResult =
        withContext(dispatcher) {
            var bytes = 0L
            var start = clockMs()
            var connection: HttpURLConnection? = null
            try {
                connection = (binding.openConnection(URL(url)) as HttpURLConnection).apply {
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache")
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    return@withContext ThroughputResult(url, 0, 0, null, error = "HTTP $code")
                }
                val expected = connection.contentLengthLong.takeIf { it > 0 }?.coerceAtMost(maxBytes) ?: maxBytes
                // Time the body only, so DNS + TLS setup does not drag the result down.
                start = clockMs()
                var lastReport = start
                val buffer = ByteArray(64 * 1024)
                connection.inputStream.use { input ->
                    while (currentCoroutineContext().isActive) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        bytes += read
                        val now = clockMs()
                        val elapsed = now - start
                        if (now - lastReport >= 200) {
                            lastReport = now
                            val fraction = maxOf(bytes.toFloat() / expected, elapsed.toFloat() / maxDurationMs).coerceIn(0f, 1f)
                            onProgress(ThroughputProgress(bytes, elapsed, ProbeMath.mbps(bytes, elapsed), fraction))
                        }
                        if (bytes >= maxBytes || elapsed >= maxDurationMs) break
                    }
                }
                val cancelled = !currentCoroutineContext().isActive
                val duration = clockMs() - start
                onProgress(ThroughputProgress(bytes, duration, ProbeMath.mbps(bytes, duration), 1f))
                ThroughputResult(url, bytes, duration, ProbeMath.mbps(bytes, duration), cancelled = cancelled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val duration = clockMs() - start
                // A partial download still says something about speed if enough data arrived.
                val mbps = if (bytes >= 256 * 1024) ProbeMath.mbps(bytes, duration) else null
                ThroughputResult(url, bytes, duration, mbps, error = e.message ?: e.javaClass.simpleName)
            } finally {
                connection?.disconnect()
            }
        }

    /**
     * Streams random bytes to [url] with a chunked POST for up to [maxDurationMs]. The rate is
     * computed after a warm-up second, because the first writes only fill the socket buffer and
     * would make a slow uplink look fast.
     */
    override suspend fun upload(url: String, maxDurationMs: Long, onProgress: (ThroughputProgress) -> Unit): ThroughputResult =
        withContext(dispatcher) {
            var bytes = 0L
            val start = clockMs()
            var warmBytes = -1L
            var warmAt = 0L
            var connection: HttpURLConnection? = null
            fun rate(now: Long): Double? =
                if (warmBytes >= 0 && now - warmAt >= 500) ProbeMath.mbps(bytes - warmBytes, now - warmAt) else ProbeMath.mbps(bytes, now - start)
            try {
                connection = (binding.openConnection(URL(url)) as HttpURLConnection).apply {
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    doOutput = true
                    requestMethod = "POST"
                    useCaches = false
                    setChunkedStreamingMode(CHUNK)
                    setRequestProperty("Content-Type", "application/octet-stream")
                }
                val chunk = ByteArray(CHUNK).also { kotlin.random.Random.nextBytes(it) } // incompressible
                var lastReport = start
                connection.outputStream.use { out ->
                    while (currentCoroutineContext().isActive) {
                        out.write(chunk)
                        bytes += chunk.size
                        val now = clockMs()
                        if (warmBytes < 0 && now - start >= WARM_UP_MS) {
                            warmBytes = bytes
                            warmAt = now
                        }
                        if (now - lastReport >= 200) {
                            lastReport = now
                            val fraction = maxOf(bytes.toFloat() / maxBytes, (now - start).toFloat() / maxDurationMs).coerceIn(0f, 1f)
                            onProgress(ThroughputProgress(bytes, now - start, rate(now), fraction, upload = true))
                        }
                        if (bytes >= maxBytes || now - start >= maxDurationMs) break
                    }
                }
                val end = clockMs()
                val cancelled = !currentCoroutineContext().isActive
                val code = if (cancelled) 200 else connection.responseCode
                val mbps = rate(end)
                onProgress(ThroughputProgress(bytes, end - start, mbps, 1f, upload = true))
                if (code !in 200..299) {
                    ThroughputResult(url, bytes, end - start, null, error = "HTTP $code")
                } else {
                    ThroughputResult(url, bytes, end - start, mbps, cancelled = cancelled)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val now = clockMs()
                val mbps = if (warmBytes >= 0 && bytes - warmBytes >= 256 * 1024) rate(now) else null
                ThroughputResult(url, bytes, now - start, mbps, error = e.message ?: e.javaClass.simpleName)
            } finally {
                connection?.disconnect()
            }
        }

    private companion object {
        const val CHUNK = 64 * 1024
        const val WARM_UP_MS = 1_000L
    }
}
