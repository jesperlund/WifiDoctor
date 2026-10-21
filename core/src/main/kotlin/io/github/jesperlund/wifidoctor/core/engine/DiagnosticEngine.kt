package io.github.jesperlund.wifidoctor.core.engine

import io.github.jesperlund.wifidoctor.core.diagnosis.Diagnoser
import io.github.jesperlund.wifidoctor.core.model.Diagnosis
import io.github.jesperlund.wifidoctor.core.model.Layer
import io.github.jesperlund.wifidoctor.core.model.LoadedLatency
import io.github.jesperlund.wifidoctor.core.model.NatBehavior
import io.github.jesperlund.wifidoctor.core.model.ProbeStats
import io.github.jesperlund.wifidoctor.core.model.ServiceCheck
import io.github.jesperlund.wifidoctor.core.model.ServiceEndpoint
import io.github.jesperlund.wifidoctor.core.model.ServiceTarget
import io.github.jesperlund.wifidoctor.core.model.Services
import io.github.jesperlund.wifidoctor.core.probe.ServiceTester
import io.github.jesperlund.wifidoctor.core.model.RealtimeResult
import io.github.jesperlund.wifidoctor.core.model.StunServer
import io.github.jesperlund.wifidoctor.core.model.ThroughputResult
import io.github.jesperlund.wifidoctor.core.probe.ProbeMath
import io.github.jesperlund.wifidoctor.core.probe.RealtimeTester
import io.github.jesperlund.wifidoctor.core.probe.RttProbe
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.github.jesperlund.wifidoctor.core.model.LinkInfo
import io.github.jesperlund.wifidoctor.core.model.Measurements
import io.github.jesperlund.wifidoctor.core.model.ScanSnapshot
import io.github.jesperlund.wifidoctor.core.model.Severity
import io.github.jesperlund.wifidoctor.core.probe.DnsComparison
import io.github.jesperlund.wifidoctor.core.probe.DnsTimer
import io.github.jesperlund.wifidoctor.core.probe.LatencyProber
import io.github.jesperlund.wifidoctor.core.probe.Resolver
import io.github.jesperlund.wifidoctor.core.probe.ThroughputProgress
import io.github.jesperlund.wifidoctor.core.probe.ThroughputTester
import io.github.jesperlund.wifidoctor.core.wifi.ChannelPlanner
import io.github.jesperlund.wifidoctor.core.wifi.RegionHints
import io.github.jesperlund.wifidoctor.core.wifi.RegionResolver
import io.github.jesperlund.wifidoctor.core.wifi.WifiMath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/** Current Wi-Fi association, or null when not connected. */
fun interface LinkSource {
    suspend fun currentLink(): LinkInfo?
}

/** Nearby networks, or null when scanning is not possible (no permission, Wi-Fi off). */
fun interface ScanSource {
    suspend fun scan(): ScanSnapshot?
}

data class RunConfig(
    val probeCount: Int = 10,
    val probeTimeoutMs: Int = 1_000,
    val internetTargets: List<String> = listOf("1.1.1.1", "8.8.8.8"),
    val dnsDomains: List<String> = listOf("example.com", "wikipedia.org", "github.com", "cloudflare.com", "google.com"),
    val referenceDns: String = "1.1.1.1",
    val dnsTimeoutMs: Int = 2_000,
    val throughputUrl: String = DEFAULT_THROUGHPUT_URL,
    val runThroughput: Boolean = true,
    val throughputMaxDurationMs: Long = 10_000,
    val uploadUrl: String = DEFAULT_UPLOAD_URL,
    val uploadMaxDurationMs: Long = 8_000,
    /** Latency samples taken while the download/upload runs, to measure bufferbloat. */
    val loadedProbeIntervalMs: Long = 250,
    val loadedProbeTimeoutMs: Int = 2_000,
    val loadedWarmUpMs: Long = 500,
    val runRealtime: Boolean = true,
    val stunServers: List<StunServer> = StunServer.DEFAULTS,
    /** 15 packets 50 ms apart: a short burst shaped like call audio. */
    val stunSamples: Int = 15,
    val stunIntervalMs: Long = 50,
    val stunTimeoutMs: Int = 500,
    val runServices: Boolean = true,
    val services: List<ServiceTarget> = Services.DEFAULTS,
    val serviceBaseline: ServiceEndpoint = Services.BASELINE,
    val serviceTimeoutMs: Int = 4_000,
    val runScan: Boolean = true,
    /** Country hints for channel regulations; access-point country elements are added from the scan. */
    val regionHints: RegionHints = RegionHints(),
) {
    companion object {
        // Large enough to keep a fast line busy for a few seconds, so latency under load can be sampled.
        const val DEFAULT_THROUGHPUT_URL = "https://speed.cloudflare.com/__down?bytes=100000000"
        const val DEFAULT_UPLOAD_URL = "https://speed.cloudflare.com/__up"
    }
}

enum class StepState { PENDING, RUNNING, OK, WARN, FAIL, SKIPPED }

fun Severity.toStepState(): StepState = when (this) {
    Severity.OK -> StepState.OK
    Severity.WARN -> StepState.WARN
    Severity.FAIL -> StepState.FAIL
}

sealed interface EngineEvent {
    data class StepStarted(val layer: Layer) : EngineEvent
    data class StepFinished(val layer: Layer, val state: StepState, val summary: String) : EngineEvent
    data class Throughput(val progress: ThroughputProgress) : EngineEvent
    data class Finished(val measurements: Measurements, val diagnosis: Diagnosis) : EngineEvent
}

/**
 * Runs the layered checks in order (link → gateway → internet → DNS → speed → under load → UDP →
 * apps → congestion),
 * emitting progress as it goes. Cancelling the collector cancels any probe in flight.
 */
class DiagnosticEngine(
    private val linkSource: LinkSource,
    private val scanSource: ScanSource,
    private val prober: LatencyProber,
    private val dnsTimer: DnsTimer,
    private val throughputTester: ThroughputTester,
    private val diagnoser: Diagnoser = Diagnoser(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Tests UDP for calls; null skips that layer. */
    private val realtimeTester: RealtimeTester? = null,
    /** Single-sample prober used while the line is loaded. */
    private val rttProbe: RttProbe = RttProbe { host, timeout -> prober.probe(host, 1, timeout).avgMs },
    /** Checks app-specific servers; null skips that layer. */
    private val serviceTester: ServiceTester? = null,
) {

    fun run(config: RunConfig = RunConfig()): Flow<EngineEvent> = channelFlow {
        var m = Measurements(timestampMs = clock(), link = null)
        suspend fun finish(layer: Layer, summary: String) {
            send(EngineEvent.StepFinished(layer, stepState(m, layer), summary))
        }
        suspend fun skip(layer: Layer, why: String) = send(EngineEvent.StepFinished(layer, StepState.SKIPPED, why))

        // 1. Link
        send(EngineEvent.StepStarted(Layer.LINK))
        val link = linkSource.currentLink()
        m = m.copy(link = link)
        if (link == null) {
            send(EngineEvent.StepFinished(Layer.LINK, StepState.FAIL, "Not connected"))
            Layer.entries.drop(1).forEach { skip(it, "Not connected to Wi-Fi") }
            send(EngineEvent.Finished(m, diagnoser.diagnose(m)))
            return@channelFlow
        }
        finish(Layer.LINK, link.rssiDbm?.let { "$it dBm, ${link.band.label}" } ?: link.band.label)

        // 2. Gateway
        send(EngineEvent.StepStarted(Layer.GATEWAY))
        val gatewayIp = link.gatewayIp
        if (gatewayIp == null) {
            skip(Layer.GATEWAY, "Router address unknown")
        } else {
            val gw = prober.probe(gatewayIp, config.probeCount, config.probeTimeoutMs)
            m = m.copy(gateway = gw)
            finish(Layer.GATEWAY, gw.summary())
        }

        // 3. Internet
        send(EngineEvent.StepStarted(Layer.INTERNET))
        val internet = config.internetTargets.map { prober.probe(it, config.probeCount, config.probeTimeoutMs) }
        m = m.copy(internet = internet)
        finish(Layer.INTERNET, m.internetSummary?.summary() ?: "No targets")

        // 4. DNS
        send(EngineEvent.StepStarted(Layer.DNS))
        val systemResolver = link.dnsServers.firstOrNull { it.contains('.') }?.let { Resolver.Udp(it) } ?: Resolver.System
        val dns = DnsComparison(dnsTimer).compare(
            system = systemResolver,
            reference = Resolver.Udp(config.referenceDns),
            domains = config.dnsDomains,
            timeoutMs = config.dnsTimeoutMs,
        )
        m = m.copy(dns = dns)
        finish(Layer.DNS, dns.systemAvgMs?.let { "${it.toInt()} ms (reference ${dns.referenceAvgMs?.toInt() ?: "–"} ms)" } ?: "Lookups failed")

        // 5. Throughput (download, then upload), sampling latency while each runs
        send(EngineEvent.StepStarted(Layer.THROUGHPUT))
        if (!config.runThroughput) {
            skip(Layer.THROUGHPUT, "Speed test turned off")
        } else {
            val target = config.internetTargets.first()
            val (down, downLoaded) = sampleLatencyDuring(target, config) {
                throughputTester.run(config.throughputUrl, config.throughputMaxDurationMs) { trySend(EngineEvent.Throughput(it)) }
            }
            val (up, upLoaded) = sampleLatencyDuring(target, config) {
                throughputTester.upload(config.uploadUrl, config.uploadMaxDurationMs) { trySend(EngineEvent.Throughput(it)) }
            }
            m = m.copy(
                throughput = down,
                upload = up,
                loaded = LoadedLatency(target, m.internet.firstOrNull { it.target == target }, downLoaded, upLoaded),
            )
            finish(Layer.THROUGHPUT, throughputSummary(down, up))
        }

        // 6. Latency under load (measured during step 5)
        send(EngineEvent.StepStarted(Layer.LOAD))
        val loaded = m.loaded
        if (loaded == null) {
            skip(Layer.LOAD, "Needs the speed test")
        } else {
            finish(Layer.LOAD, loadedSummary(loaded))
        }

        // 7. Real-time (UDP) path used by calls
        send(EngineEvent.StepStarted(Layer.REALTIME))
        val realtime = realtimeTester
        if (realtime == null || !config.runRealtime || config.stunServers.isEmpty()) {
            skip(Layer.REALTIME, "UDP test turned off")
        } else {
            val r = realtime.test(config.stunServers, config.stunSamples, config.stunIntervalMs, config.stunTimeoutMs)
            m = m.copy(realtime = r)
            finish(Layer.REALTIME, realtimeSummary(r))
        }

        // 8. App-specific servers
        send(EngineEvent.StepStarted(Layer.SERVICES))
        val services = serviceTester
        if (services == null || !config.runServices || config.services.isEmpty()) {
            skip(Layer.SERVICES, "App checks turned off")
        } else {
            val check = services.check(config.services, config.serviceBaseline, config.serviceTimeoutMs)
            m = m.copy(services = check)
            finish(Layer.SERVICES, servicesSummary(check))
        }

        // 6. Congestion
        send(EngineEvent.StepStarted(Layer.CONGESTION))
        val scan = if (config.runScan) scanSource.scan() else null
        if (scan == null) {
            skip(Layer.CONGESTION, if (config.runScan) "Scanning unavailable" else "Scan turned off")
        } else {
            val enrichedLink = enrichFromScan(link, scan)
            val region = RegionResolver.resolve(config.regionHints, scan.entries.map { it.countryCode })
            m = m.copy(
                link = enrichedLink,
                scan = scan,
                congestion = WifiMath.congestion(enrichedLink, scan.entries),
                channels = ChannelPlanner.analyze(enrichedLink, scan.entries, region),
            )
            finish(Layer.CONGESTION, congestionSummary(m, scan.throttled))
        }

        send(EngineEvent.Finished(m, diagnoser.diagnose(m)))
    }

    /**
     * Stepper state for a layer: the worst finding attributed to it, plus raw failures whose
     * diagnosis lands on an earlier layer (no internet target answering is still a red step).
     */
    private fun stepState(m: Measurements, layer: Layer): StepState {
        val rawFail = when (layer) {
            Layer.INTERNET -> m.internet.isNotEmpty() && m.internet.all { it.allLost }
            Layer.DNS -> m.dns?.let { it.systemAvgMs == null && it.referenceAvgMs == null } ?: false
            else -> false
        }
        return if (rawFail) StepState.FAIL else diagnoser.layerSeverity(m, layer).toStepState()
    }

    /** Runs [block] while sampling round trips to [target] in parallel; returns its result and the samples. */
    private suspend fun <T> sampleLatencyDuring(target: String, config: RunConfig, block: suspend () -> T): Pair<T, ProbeStats?> =
        coroutineScope {
            val samples = mutableListOf<Double?>()
            val sampler = launch {
                delay(config.loadedWarmUpMs) // let the transfer ramp up first
                while (isActive) {
                    samples += rttProbe.rtt(target, config.loadedProbeTimeoutMs)
                    delay(config.loadedProbeIntervalMs)
                }
            }
            val result = block()
            sampler.cancelAndJoin()
            result to samples.takeIf { it.size >= MIN_LOADED_SAMPLES }?.let { ProbeMath.stats(target, it) }
        }

    companion object {
        private const val MIN_LOADED_SAMPLES = 3

        fun throughputSummary(down: ThroughputResult, up: ThroughputResult): String {
            fun fmt(r: ThroughputResult) = r.mbps?.let { "%.1f Mbps".format(it) } ?: (r.error ?: "no data")
            return "↓ ${fmt(down)} · ↑ ${fmt(up)}"
        }

        fun loadedSummary(l: LoadedLatency): String {
            val parts = listOfNotNull(
                l.downloadIncreaseMs?.let { "+${it.toInt()} ms downloading" },
                l.uploadIncreaseMs?.let { "+${it.toInt()} ms uploading" },
            )
            return if (parts.isEmpty()) "Too few samples (transfer too short)" else parts.joinToString(", ")
        }

        fun realtimeSummary(r: RealtimeResult): String {
            val best = r.best ?: return "No UDP replies"
            val s = best.stats
            return "${s.avgMs?.toInt()} ms, ${"%.0f".format(s.lossPercent)}% loss, ${s.jitterMs?.toInt() ?: 0} ms jitter" +
                (if (r.nat == NatBehavior.SYMMETRIC) " · symmetric NAT" else "")
        }

        fun servicesSummary(c: ServiceCheck): String = c.results.joinToString(" · ") { r ->
            when {
                r.allFailed -> "${r.target.name}: ${r.endpoints.first().failure?.label?.lowercase()}"
                r.failed.isNotEmpty() -> "${r.target.name}: ${r.failed.size} of ${r.endpoints.size} servers failing"
                else -> "${r.target.name} ${r.medianTotalMs?.toInt()} ms"
            }
        } + (c.baseline?.totalMs?.let { " (reference ${it.toInt()} ms)" } ?: "")

        fun congestionSummary(m: Measurements, throttled: Boolean): String {
            val c = m.congestion
            val base = if (c == null) "Channel unknown" else "${c.sameChannel} same, ${c.overlapping} overlapping"
            val best = m.channels?.bands.orEmpty().mapNotNull { b -> b.recommended?.let { "${b.band.label} ch ${it.channel}" } }
            return base + (if (best.isEmpty()) "" else " · best: " + best.joinToString(", ")) + (if (throttled) " (cached scan)" else "")
        }

        /** Fills in the channel width (not exposed by WifiInfo) from our own BSSID's scan entry. */
        fun enrichFromScan(link: LinkInfo, scan: ScanSnapshot): LinkInfo {
            if (link.channelWidthMhz != null || link.bssid == null) return link
            val own = scan.entries.firstOrNull { it.bssid.equals(link.bssid, ignoreCase = true) } ?: return link
            return link.copy(channelWidthMhz = own.channelWidthMhz)
        }
    }
}

internal fun io.github.jesperlund.wifidoctor.core.model.ProbeStats.summary(): String =
    if (allLost) "No replies" else "${avgMs?.toInt() ?: "–"} ms avg, ${"%.0f".format(lossPercent)}% loss"
