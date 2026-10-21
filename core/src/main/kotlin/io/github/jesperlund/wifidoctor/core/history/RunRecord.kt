package io.github.jesperlund.wifidoctor.core.history

import io.github.jesperlund.wifidoctor.core.model.Band
import io.github.jesperlund.wifidoctor.core.model.Diagnosis
import io.github.jesperlund.wifidoctor.core.model.Layer
import io.github.jesperlund.wifidoctor.core.model.Measurements
import io.github.jesperlund.wifidoctor.core.model.Severity
import io.github.jesperlund.wifidoctor.core.model.WifiStandard
import io.github.jesperlund.wifidoctor.core.monitor.MonitorReport
import io.github.jesperlund.wifidoctor.core.wifi.WifiMath

enum class RunKind(val label: String) {
    DIAGNOSIS("Diagnosis"),
    WALK_TEST("Walk test"),
    CALL_MONITOR("Call monitor"),
}

/** Flattened, storable summary of one run: its measurements and diagnosis. */
data class RunRecord(
    val id: Long = 0,
    val kind: RunKind = RunKind.DIAGNOSIS,
    val timestampMs: Long,
    val roomLabel: String? = null,
    val ssid: String? = null,
    val bssid: String? = null,
    val rssiDbm: Int? = null,
    val linkSpeedMbps: Int? = null,
    val frequencyMhz: Int? = null,
    val band: Band = Band.UNKNOWN,
    val channel: Int? = null,
    val channelWidthMhz: Int? = null,
    val standard: WifiStandard = WifiStandard.UNKNOWN,
    val gatewayIp: String? = null,
    val gatewayAvgMs: Double? = null,
    val gatewayMaxMs: Double? = null,
    val gatewayJitterMs: Double? = null,
    val gatewayLossPercent: Double? = null,
    val internetTarget: String? = null,
    val internetAvgMs: Double? = null,
    val internetMaxMs: Double? = null,
    val internetJitterMs: Double? = null,
    val internetLossPercent: Double? = null,
    val dnsSystemMs: Double? = null,
    val dnsReferenceMs: Double? = null,
    val throughputMbps: Double? = null,
    val sameChannelNetworks: Int? = null,
    val overlappingNetworks: Int? = null,
    /** Country used for channel rules, and the router's current vs. suggested channel per band. */
    val countryCode: String? = null,
    val routerChannel24: Int? = null,
    val suggestedChannel24: Int? = null,
    val routerChannel5: Int? = null,
    val suggestedChannel5: Int? = null,
    val suggestedWidth5: Int? = null,
    val uploadMbps: Double? = null,
    /** Latency increase while downloading / uploading (bufferbloat). */
    val loadedDownIncreaseMs: Double? = null,
    val loadedUpIncreaseMs: Double? = null,
    /** Best STUN server over UDP, and NAT behaviour. */
    val udpRttMs: Double? = null,
    val udpJitterMs: Double? = null,
    val udpLossPercent: Double? = null,
    val natBehavior: String? = null,
    /** Call monitor: 95th-percentile internet round trip, drop-outs and session length. */
    val internetP95Ms: Double? = null,
    val dropouts: Int? = null,
    val monitorDurationMs: Long? = null,
    /** App server checks, e.g. "Instagram:OK:180ms;Microsoft Teams:PARTIAL:240ms". */
    val serviceSummary: String? = null,
    val severity: Severity,
    val layer: Layer? = null,
    val verdict: String,
    val explanation: String,
    val fixes: List<String> = emptyList(),
) {
    companion object {
        fun from(m: Measurements, d: Diagnosis, roomLabel: String? = null): RunRecord {
            val link = m.link
            val inet = m.internetSummary
            val b24 = m.channels?.band(Band.GHZ_2_4)
            val b5 = m.channels?.band(Band.GHZ_5)
            return RunRecord(
                kind = if (roomLabel.isNullOrBlank()) RunKind.DIAGNOSIS else RunKind.WALK_TEST,
                timestampMs = m.timestampMs,
                roomLabel = roomLabel?.trim()?.takeIf { it.isNotEmpty() },
                ssid = link?.ssid,
                bssid = link?.bssid,
                rssiDbm = link?.rssiDbm,
                linkSpeedMbps = link?.linkSpeedMbps,
                frequencyMhz = link?.frequencyMhz,
                band = link?.band ?: Band.UNKNOWN,
                channel = link?.channel,
                channelWidthMhz = link?.channelWidthMhz,
                standard = link?.standard ?: WifiStandard.UNKNOWN,
                gatewayIp = link?.gatewayIp,
                gatewayAvgMs = m.gateway?.avgMs,
                gatewayMaxMs = m.gateway?.maxMs,
                gatewayJitterMs = m.gateway?.jitterMs,
                gatewayLossPercent = m.gateway?.lossPercent,
                internetTarget = inet?.target,
                internetAvgMs = inet?.avgMs,
                internetMaxMs = inet?.maxMs,
                internetJitterMs = inet?.jitterMs,
                internetLossPercent = inet?.lossPercent,
                dnsSystemMs = m.dns?.systemAvgMs,
                dnsReferenceMs = m.dns?.referenceAvgMs,
                throughputMbps = m.throughput?.mbps,
                sameChannelNetworks = m.congestion?.sameChannel,
                overlappingNetworks = m.congestion?.overlapping,
                countryCode = m.channels?.region?.countryCode,
                routerChannel24 = b24?.current?.channel,
                suggestedChannel24 = b24?.recommended?.channel,
                routerChannel5 = b5?.current?.channel,
                suggestedChannel5 = b5?.recommended?.channel,
                suggestedWidth5 = b5?.recommended?.widthMhz,
                uploadMbps = m.upload?.mbps,
                loadedDownIncreaseMs = m.loaded?.downloadIncreaseMs,
                loadedUpIncreaseMs = m.loaded?.uploadIncreaseMs,
                udpRttMs = m.realtime?.best?.stats?.avgMs,
                udpJitterMs = m.realtime?.best?.stats?.jitterMs,
                udpLossPercent = m.realtime?.let { r -> r.best?.stats?.lossPercent ?: if (r.servers.isEmpty()) null else 100.0 },
                natBehavior = m.realtime?.nat?.name,
                serviceSummary = m.services?.summary,
                severity = d.severity,
                layer = d.layer,
                verdict = d.verdict,
                explanation = d.explanation,
                fixes = d.fixes,
            )
        }
    
        /** A call-monitor session, summarised with percentiles. */
        fun fromMonitor(r: MonitorReport, d: Diagnosis): RunRecord {
            val lastLink = r.linkSamples.lastOrNull { it.connected }
            return RunRecord(
                kind = RunKind.CALL_MONITOR,
                timestampMs = r.startedAtMs,
                ssid = r.ssid,
                bssid = lastLink?.bssid,
                rssiDbm = r.rssiAvgDbm,
                linkSpeedMbps = lastLink?.linkSpeedMbps,
                frequencyMhz = lastLink?.frequencyMhz,
                band = WifiMath.bandFor(lastLink?.frequencyMhz),
                channel = WifiMath.channelFor(lastLink?.frequencyMhz),
                gatewayIp = r.gatewayIp,
                gatewayAvgMs = r.gateway?.p50Ms,
                gatewayMaxMs = r.gateway?.maxMs,
                gatewayJitterMs = r.gateway?.jitterMs,
                gatewayLossPercent = r.gateway?.lossPercent,
                internetTarget = r.internetTarget,
                internetAvgMs = r.internet.p50Ms,
                internetMaxMs = r.internet.maxMs,
                internetJitterMs = r.internet.jitterMs,
                internetLossPercent = r.internet.lossPercent,
                internetP95Ms = r.internet.p95Ms,
                dropouts = r.dropouts.size,
                monitorDurationMs = r.durationMs,
                severity = d.severity,
                layer = d.layer,
                verdict = d.verdict,
                explanation = d.explanation,
                fixes = d.fixes,
            )
        }
    }
}
