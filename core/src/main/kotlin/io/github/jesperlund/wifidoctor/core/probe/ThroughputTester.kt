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

data class ThroughputProgress(val bytes: Long, val elapsedMs: Long, val mbps: Double?, val fraction: Float)

interface ThroughputTester {
    suspend fun run(url: String, maxDurationMs: Long, onProgress: (ThroughputProgress) -> Unit = {}): ThroughputResult
}

/**
 * Downloads [url] until it ends, [maxBytes] have arrived or [maxDurationMs] has elapsed, and reports
 * the average rate. Cancelling the calling coroutine stops the download promptly.
 */
class HttpThroughputTester(
    private val binding: NetworkBinding = NetworkBinding.Default,
    private val maxBytes: Long = 50L * 1024 * 1024,
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
}
