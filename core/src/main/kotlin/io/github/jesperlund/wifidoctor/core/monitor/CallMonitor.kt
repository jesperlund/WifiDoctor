package io.github.jesperlund.wifidoctor.core.monitor

import io.github.jesperlund.wifidoctor.core.engine.LinkSource
import io.github.jesperlund.wifidoctor.core.probe.ProbeMath
import io.github.jesperlund.wifidoctor.core.probe.RttProbe
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

data class MonitorConfig(
    val durationMs: Long,
    val internetTarget: String = "1.1.1.1",
    /** 200 ms between samples: fine enough to catch the 1–2 s gaps that break calls. */
    val sampleIntervalMs: Long = 200,
    val probeTimeoutMs: Int = 1_000,
    val linkSampleEveryMs: Long = 1_000,
    /** Consecutive lost internet samples that count as a drop-out (3 × 200 ms ≈ 0.6 s). */
    val minDropoutSamples: Int = 3,
    val weakRssiDbm: Int = -75,
    /** Samples kept for the live chart (~60 s at 200 ms). */
    val liveWindow: Int = 300,
)

/** One tick: round trip to the router and to the internet target (null = lost). */
data class MonitorSample(val tMs: Long, val gatewayMs: Double?, val internetMs: Double?)

data class LinkSample(
    val tMs: Long,
    val connected: Boolean,
    val ssid: String? = null,
    val bssid: String? = null,
    val rssiDbm: Int? = null,
    val linkSpeedMbps: Int? = null,
    val frequencyMhz: Int? = null,
)

/** Summary of a latency series. Percentiles describe what calls actually feel, unlike averages. */
data class SeriesStats(
    val sent: Int,
    val received: Int,
    val p50Ms: Double?,
    val p95Ms: Double?,
    val p99Ms: Double?,
    val maxMs: Double?,
    val jitterMs: Double?,
) {
    val lossPercent: Double get() = if (sent == 0) 0.0 else (sent - received) * 100.0 / sent

    companion object {
        fun of(values: List<Double?>): SeriesStats {
            val ok = values.filterNotNull()
            return SeriesStats(
                sent = values.size,
                received = ok.size,
                p50Ms = ProbeMath.percentile(ok, 50.0),
                p95Ms = ProbeMath.percentile(ok, 95.0),
                p99Ms = ProbeMath.percentile(ok, 99.0),
                maxMs = ok.maxOrNull(),
                jitterMs = ProbeMath.jitter(ok),
            )
        }
    }
}

/** Where a drop-out happened: the router stopped answering too (Wi-Fi), or only the internet did. */
enum class DropScope(val label: String) { WIFI("Wi-Fi"), INTERNET("Internet"), UNKNOWN("Unknown") }

enum class DropCause(val label: String) {
    DISCONNECTED("Wi-Fi disconnected"),
    ROAMING("Switching access point"),
    WEAK_SIGNAL("Weak signal"),
    NONE("No obvious cause"),
}

data class Dropout(val startMs: Long, val durationMs: Long, val scope: DropScope, val cause: DropCause)

data class RoamEvent(val tMs: Long, val fromBssid: String, val toBssid: String, val fromFrequencyMhz: Int?, val toFrequencyMhz: Int?)

data class MonitorProgress(
    val elapsedMs: Long,
    val durationMs: Long,
    val recent: List<MonitorSample>,
    val internet: SeriesStats,
    val gateway: SeriesStats?,
    val dropouts: Int,
    val roams: Int,
    val latestRssiDbm: Int?,
)

data class MonitorReport(
    val startedAtMs: Long,
    val durationMs: Long,
    val plannedDurationMs: Long,
    val internetTarget: String,
    val gatewayIp: String?,
    val samples: List<MonitorSample>,
    val linkSamples: List<LinkSample>,
    val internet: SeriesStats,
    val gateway: SeriesStats?,
    val dropouts: List<Dropout>,
    val roams: List<RoamEvent>,
    /** Internet samples far above the median (> 3× median and > median + 100 ms). */
    val internetSpikes: Int,
    /** Router samples far above its median (> 3× median and > median + 50 ms). */
    val gatewaySpikes: Int,
    /** Lost internet samples that were not part of a drop-out, as % of all samples. */
    val scatteredLossPercent: Double,
    val rssiMinDbm: Int?,
    val rssiAvgDbm: Int?,
    val ssid: String?,
) {
    val stoppedEarly: Boolean get() = durationMs + 1_000 < plannedDurationMs
    val totalDropoutMs: Long get() = dropouts.sumOf { it.durationMs }
}

/**
 * Samples the router and an internet target every [MonitorConfig.sampleIntervalMs] and the Wi-Fi
 * link every second, e.g. while the user is in a Teams call, then analyses the timeline.
 */
class CallMonitor(
    private val linkSource: LinkSource,
    private val probe: RttProbe,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(
        config: MonitorConfig,
        stop: () -> Boolean = { false },
        onProgress: (MonitorProgress) -> Unit = {},
    ): MonitorReport {
        val startedAt = clock()
        val samples = mutableListOf<MonitorSample>()
        val links = mutableListOf<LinkSample>()
        var gateway: String? = null
        var nextLinkAt = 0L
        var lastProgressAt = -1_000L
        while (true) {
            val t = clock() - startedAt
            if (t >= config.durationMs || stop()) break
            if (t >= nextLinkAt) {
                val link = runCatching { linkSource.currentLink() }.getOrNull()
                links += LinkSample(t, link != null, link?.ssid, link?.bssid, link?.rssiDbm, link?.linkSpeedMbps, link?.frequencyMhz)
                if (link?.gatewayIp != null) gateway = link.gatewayIp
                nextLinkAt += config.linkSampleEveryMs
            }
            val gw = gateway
            val (gatewayMs, internetMs) = coroutineScope {
                val g = gw?.let { async { probe.rtt(it, config.probeTimeoutMs) } }
                val i = async { probe.rtt(config.internetTarget, config.probeTimeoutMs) }
                g?.await() to i.await()
            }
            samples += MonitorSample(t, gatewayMs, internetMs)
            if (t - lastProgressAt >= 1_000) {
                lastProgressAt = t
                onProgress(progress(config, t, samples, links, gw != null))
            }
            val spent = clock() - startedAt - t
            delay((config.sampleIntervalMs - spent).coerceAtLeast(0))
        }
        return analyze(config, startedAt, clock() - startedAt, samples, links, gateway)
    }

    private fun progress(config: MonitorConfig, t: Long, samples: List<MonitorSample>, links: List<LinkSample>, hasGateway: Boolean) =
        MonitorProgress(
            elapsedMs = t,
            durationMs = config.durationMs,
            recent = samples.takeLast(config.liveWindow),
            internet = SeriesStats.of(samples.map { it.internetMs }),
            gateway = if (hasGateway) SeriesStats.of(samples.map { it.gatewayMs }) else null,
            dropouts = findDropouts(samples, links, config).size,
            roams = findRoams(links).size,
            latestRssiDbm = links.lastOrNull()?.rssiDbm,
        )

    companion object {
        fun analyze(
            config: MonitorConfig,
            startedAt: Long,
            durationMs: Long,
            samples: List<MonitorSample>,
            links: List<LinkSample>,
            gateway: String?,
        ): MonitorReport {
            val internet = SeriesStats.of(samples.map { it.internetMs })
            val gatewayStats = if (gateway != null) SeriesStats.of(samples.map { it.gatewayMs }) else null
            val dropouts = findDropouts(samples, links, config)
            val inDropout = dropoutIndices(samples, config)
            val scattered = samples.indices.count { samples[it].internetMs == null && it !in inDropout }
            val rssi = links.mapNotNull { it.rssiDbm }
            return MonitorReport(
                startedAtMs = startedAt,
                durationMs = durationMs,
                plannedDurationMs = config.durationMs,
                internetTarget = config.internetTarget,
                gatewayIp = gateway,
                samples = samples,
                linkSamples = links,
                internet = internet,
                gateway = gatewayStats,
                dropouts = dropouts,
                roams = findRoams(links),
                internetSpikes = spikes(samples.map { it.internetMs }, 100.0),
                gatewaySpikes = if (gateway != null) spikes(samples.map { it.gatewayMs }, 50.0) else 0,
                scatteredLossPercent = if (samples.isEmpty()) 0.0 else scattered * 100.0 / samples.size,
                rssiMinDbm = rssi.minOrNull(),
                rssiAvgDbm = rssi.takeIf { it.isNotEmpty() }?.average()?.toInt(),
                ssid = links.lastOrNull { it.ssid != null }?.ssid,
            )
        }

        private fun spikes(values: List<Double?>, minAboveMs: Double): Int {
            val ok = values.filterNotNull()
            val median = ProbeMath.median(ok) ?: return 0
            return ok.count { it > median * 3 && it - median > minAboveMs }
        }

        /** Index ranges of consecutive lost internet samples long enough to be a drop-out. */
        private fun dropoutRuns(samples: List<MonitorSample>, config: MonitorConfig): List<IntRange> {
            val runs = mutableListOf<IntRange>()
            var start = -1
            for (i in samples.indices) {
                if (samples[i].internetMs == null) {
                    if (start < 0) start = i
                } else {
                    if (start >= 0 && i - start >= config.minDropoutSamples) runs += start until i
                    start = -1
                }
            }
            if (start >= 0 && samples.size - start >= config.minDropoutSamples) runs += start until samples.size
            return runs
        }

        private fun dropoutIndices(samples: List<MonitorSample>, config: MonitorConfig): Set<Int> =
            dropoutRuns(samples, config).flatMap { it.toList() }.toSet()

        fun findDropouts(samples: List<MonitorSample>, links: List<LinkSample>, config: MonitorConfig): List<Dropout> =
            dropoutRuns(samples, config).map { run ->
                val first = samples[run.first]
                val last = samples[run.last]
                val start = first.tMs
                val end = last.tMs + config.sampleIntervalMs
                val hasGateway = samples.any { it.gatewayMs != null }
                val gatewayLost = run.count { samples[it].gatewayMs == null }
                val scope = when {
                    !hasGateway -> DropScope.UNKNOWN
                    gatewayLost * 2 >= run.count() -> DropScope.WIFI
                    else -> DropScope.INTERNET
                }
                // Link events within 2 s of the gap explain it.
                val window = links.filter { it.tMs in (start - 2_000)..(end + 2_000) }
                val roamed = findRoams(links).any { it.tMs in (start - 2_000)..(end + 2_000) }
                val cause = when {
                    window.any { !it.connected } -> DropCause.DISCONNECTED
                    roamed -> DropCause.ROAMING
                    window.any { (it.rssiDbm ?: 0) < config.weakRssiDbm } -> DropCause.WEAK_SIGNAL
                    else -> DropCause.NONE
                }
                Dropout(start, end - start, scope, cause)
            }

        fun findRoams(links: List<LinkSample>): List<RoamEvent> {
            val roams = mutableListOf<RoamEvent>()
            var previous: LinkSample? = null
            for (l in links) {
                if (l.bssid == null) continue
                val p = previous
                if (p?.bssid != null && !p.bssid.equals(l.bssid, ignoreCase = true)) {
                    roams += RoamEvent(l.tMs, p.bssid, l.bssid, p.frequencyMhz, l.frequencyMhz)
                }
                previous = l
            }
            return roams
        }

    }
}
