package com.example.wifidoctor.core.history

import com.example.wifidoctor.core.model.Band
import com.example.wifidoctor.core.model.Diagnosis
import com.example.wifidoctor.core.model.Layer
import com.example.wifidoctor.core.model.Measurements
import com.example.wifidoctor.core.model.Severity
import com.example.wifidoctor.core.model.WifiStandard

/** Flattened, storable summary of one run: its measurements and diagnosis. */
data class RunRecord(
    val id: Long = 0,
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
            return RunRecord(
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
                severity = d.severity,
                layer = d.layer,
                verdict = d.verdict,
                explanation = d.explanation,
                fixes = d.fixes,
            )
        }
    }
}
