package com.example.wifidoctor.core.engine

import com.example.wifidoctor.core.diagnosis.Diagnoser
import com.example.wifidoctor.core.model.Diagnosis
import com.example.wifidoctor.core.model.Layer
import com.example.wifidoctor.core.model.LinkInfo
import com.example.wifidoctor.core.model.Measurements
import com.example.wifidoctor.core.model.ScanSnapshot
import com.example.wifidoctor.core.model.Severity
import com.example.wifidoctor.core.probe.DnsComparison
import com.example.wifidoctor.core.probe.DnsTimer
import com.example.wifidoctor.core.probe.LatencyProber
import com.example.wifidoctor.core.probe.Resolver
import com.example.wifidoctor.core.probe.ThroughputProgress
import com.example.wifidoctor.core.probe.ThroughputTester
import com.example.wifidoctor.core.wifi.WifiMath
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
    val runScan: Boolean = true,
) {
    companion object {
        const val DEFAULT_THROUGHPUT_URL = "https://speed.cloudflare.com/__down?bytes=25000000"
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
 * Runs the layered checks in order (link → gateway → internet → DNS → throughput → congestion),
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
) {

    fun run(config: RunConfig = RunConfig()): Flow<EngineEvent> = channelFlow {
        var m = Measurements(timestampMs = clock(), link = null)
        suspend fun finish(layer: Layer, summary: String) {
            send(EngineEvent.StepFinished(layer, diagnoser.layerSeverity(m, layer).toStepState(), summary))
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

        // 5. Throughput
        send(EngineEvent.StepStarted(Layer.THROUGHPUT))
        if (!config.runThroughput) {
            skip(Layer.THROUGHPUT, "Speed test turned off")
        } else {
            val result = throughputTester.run(config.throughputUrl, config.throughputMaxDurationMs) { progress ->
                trySend(EngineEvent.Throughput(progress))
            }
            m = m.copy(throughput = result)
            finish(Layer.THROUGHPUT, result.mbps?.let { "%.1f Mbps".format(it) } ?: (result.error ?: "No data"))
        }

        // 6. Congestion
        send(EngineEvent.StepStarted(Layer.CONGESTION))
        val scan = if (config.runScan) scanSource.scan() else null
        if (scan == null) {
            skip(Layer.CONGESTION, if (config.runScan) "Scanning unavailable" else "Scan turned off")
        } else {
            val enrichedLink = enrichFromScan(link, scan)
            m = m.copy(link = enrichedLink, scan = scan, congestion = WifiMath.congestion(enrichedLink, scan.entries))
            val c = m.congestion
            val summary = if (c == null) "Channel unknown" else "${c.sameChannel} same, ${c.overlapping} overlapping" +
                if (scan.throttled) " (cached scan)" else ""
            finish(Layer.CONGESTION, summary)
        }

        send(EngineEvent.Finished(m, diagnoser.diagnose(m)))
    }

    companion object {
        /** Fills in the channel width (not exposed by WifiInfo) from our own BSSID's scan entry. */
        fun enrichFromScan(link: LinkInfo, scan: ScanSnapshot): LinkInfo {
            if (link.channelWidthMhz != null || link.bssid == null) return link
            val own = scan.entries.firstOrNull { it.bssid.equals(link.bssid, ignoreCase = true) } ?: return link
            return link.copy(channelWidthMhz = own.channelWidthMhz)
        }
    }
}

internal fun com.example.wifidoctor.core.model.ProbeStats.summary(): String =
    if (allLost) "No replies" else "${avgMs?.toInt() ?: "–"} ms avg, ${"%.0f".format(lossPercent)}% loss"
