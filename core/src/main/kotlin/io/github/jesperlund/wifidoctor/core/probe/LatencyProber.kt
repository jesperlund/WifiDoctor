package io.github.jesperlund.wifidoctor.core.probe

import io.github.jesperlund.wifidoctor.core.model.ProbeMethod
import io.github.jesperlund.wifidoctor.core.model.ProbeStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

/** Measures round-trip latency to a host. */
interface LatencyProber {
    suspend fun probe(host: String, count: Int, timeoutMs: Int): ProbeStats
}

/**
 * Times TCP handshakes. A SYN answered with SYN-ACK (port open) or RST (connection refused) is a
 * full round trip, so both count as a reply. Only timeouts and unreachable errors count as loss.
 *
 * The first port that answers is reused for all samples so each sample costs one round trip.
 */
class TcpConnectProber(
    private val binding: NetworkBinding = NetworkBinding.Default,
    private val ports: List<Int> = listOf(53, 443, 80),
    private val intervalMs: Long = 100,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nanoTime: () -> Long = System::nanoTime,
) : LatencyProber {

    override suspend fun probe(host: String, count: Int, timeoutMs: Int): ProbeStats = withContext(dispatcher) {
        val samples = mutableListOf<Double?>()
        var port: Int? = null
        // Find a port that answers; its timing counts as the first sample.
        for (candidate in ports) {
            coroutineContext.ensureActive()
            val rtt = connectOnce(host, candidate, timeoutMs)
            if (rtt != null) {
                port = candidate
                samples += rtt
                break
            }
        }
        if (port == null) {
            return@withContext ProbeMath.stats(host, List(count) { null }, ProbeMethod.TCP)
        }
        while (samples.size < count) {
            coroutineContext.ensureActive()
            delay(intervalMs)
            samples += connectOnce(host, port, timeoutMs)
        }
        ProbeMath.stats(host, samples, ProbeMethod.TCP)
    }

    /** Returns the handshake time in ms, or null if the probe was lost. */
    private fun connectOnce(host: String, port: Int, timeoutMs: Int): Double? {
        val socket: Socket = binding.socketFactory().createSocket()
        val start = nanoTime()
        return try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            (nanoTime() - start) / 1_000_000.0
        } catch (e: ConnectException) {
            // "Connection refused" means the host answered with RST: a valid round trip.
            // Other ConnectExceptions (e.g. network unreachable) arrive without a reply.
            val elapsed = (nanoTime() - start) / 1_000_000.0
            if (e is NoRouteToHostException || e.message?.contains("refused", ignoreCase = true) != true) null else elapsed
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }
}

/**
 * Prefers the primary prober and falls back to the secondary (ICMP ping) only when the primary
 * got no replies at all, e.g. a router that silently drops SYNs on every probed port.
 */
class FallbackProber(
    private val primary: LatencyProber,
    private val secondary: LatencyProber?,
) : LatencyProber {
    override suspend fun probe(host: String, count: Int, timeoutMs: Int): ProbeStats {
        val first = primary.probe(host, count, timeoutMs)
        if (!first.allLost) return first
        val fallback = secondary ?: return first
        val second = try {
            fallback.probe(host, count, timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return if (second != null && second.method != ProbeMethod.NONE) second else first
    }
}
