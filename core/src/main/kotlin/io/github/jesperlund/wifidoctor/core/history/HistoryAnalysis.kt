package io.github.jesperlund.wifidoctor.core.history

import io.github.jesperlund.wifidoctor.core.model.Severity
import io.github.jesperlund.wifidoctor.core.probe.ProbeMath
import io.github.jesperlund.wifidoctor.core.wifi.SignalQuality

enum class DropReason(val label: String) {
    PACKET_LOSS("Packet loss"),
    LATENCY_SPIKE("Latency spike"),
    SIGNAL_DIP("Signal dip"),
}

data class Drop(val recordId: Long, val timestampMs: Long, val reasons: Set<DropReason>)

/**
 * Finds intermittent problems: individual runs that are much worse than the typical run, which
 * is what users experience as "it randomly drops".
 */
object DropDetector {
    fun detect(
        records: List<RunRecord>,
        lossThresholdPercent: Double = 10.0,
        latencySpikeFactor: Double = 3.0,
        latencySpikeMinMs: Double = 50.0,
        signalDipDb: Int = 15,
    ): List<Drop> {
        if (records.isEmpty()) return emptyList()
        val medianLatency = ProbeMath.median(records.mapNotNull { it.internetAvgMs ?: it.gatewayAvgMs })
        val medianRssi = ProbeMath.median(records.mapNotNull { it.rssiDbm?.toDouble() })
        return records.sortedBy { it.timestampMs }.mapNotNull { r ->
            val reasons = mutableSetOf<DropReason>()
            val loss = maxOf(r.gatewayLossPercent ?: 0.0, r.internetLossPercent ?: 0.0)
            if (loss >= lossThresholdPercent) reasons += DropReason.PACKET_LOSS
            val latency = r.internetAvgMs ?: r.gatewayAvgMs
            if (latency != null && medianLatency != null && records.size >= 3 &&
                latency > medianLatency * latencySpikeFactor && latency - medianLatency >= latencySpikeMinMs
            ) reasons += DropReason.LATENCY_SPIKE
            if (r.rssiDbm != null && medianRssi != null && records.size >= 3 && medianRssi - r.rssiDbm >= signalDipDb) {
                reasons += DropReason.SIGNAL_DIP
            }
            if (reasons.isEmpty()) null else Drop(r.id, r.timestampMs, reasons)
        }
    }
}

/** One room's latest walk test, ready for ranking. */
data class RoomSummary(
    val roomName: String,
    val rssiDbm: Int?,
    val gatewayLossPercent: Double?,
    val gatewayAvgMs: Double?,
    val linkSpeedMbps: Int?,
    val timestampMs: Long,
    val severity: Severity,
    val recordId: Long,
) {
    val quality: SignalQuality get() = SignalQuality.of(rssiDbm)

    /** Higher is better: RSSI in dB, penalised for loss (0.5 dB per %) and latency above 20 ms. */
    val score: Double
        get() {
            val base = rssiDbm?.toDouble() ?: -110.0
            val lossPenalty = (gatewayLossPercent ?: 0.0) * 0.5
            val latencyPenalty = ((gatewayAvgMs ?: 0.0) - 20.0).coerceAtLeast(0.0) / 5.0
            return base - lossPenalty - latencyPenalty
        }

    /** Dead zone: a room where Wi-Fi is effectively unusable. */
    val isDeadZone: Boolean get() = (rssiDbm ?: -100) < -80 || (gatewayLossPercent ?: 0.0) >= 30.0
}

object RoomRanking {
    /** Latest result per room (case-insensitive name), best first. */
    fun rank(records: List<RunRecord>): List<RoomSummary> =
        records.filter { !it.roomLabel.isNullOrBlank() }
            .groupBy { it.roomLabel!!.trim().lowercase() }
            .map { (_, runs) -> runs.maxBy { it.timestampMs } }
            .map {
                RoomSummary(
                    roomName = it.roomLabel!!.trim(),
                    rssiDbm = it.rssiDbm,
                    gatewayLossPercent = it.gatewayLossPercent,
                    gatewayAvgMs = it.gatewayAvgMs,
                    linkSpeedMbps = it.linkSpeedMbps,
                    timestampMs = it.timestampMs,
                    severity = it.severity,
                    recordId = it.id,
                )
            }
            .sortedWith(compareByDescending<RoomSummary> { it.score }.thenBy { it.roomName.lowercase() })
}

object CsvExporter {
    val header = listOf(
        "id", "timestamp_ms", "room", "ssid", "bssid", "rssi_dbm", "link_speed_mbps", "frequency_mhz", "band",
        "channel", "channel_width_mhz", "standard", "gateway_ip", "gateway_avg_ms", "gateway_max_ms",
        "gateway_jitter_ms", "gateway_loss_pct", "internet_target", "internet_avg_ms", "internet_max_ms",
        "internet_jitter_ms", "internet_loss_pct", "dns_system_ms", "dns_reference_ms", "throughput_mbps",
        "same_channel_networks", "overlapping_networks", "severity", "layer", "verdict", "fixes",
        "country", "router_channel_24", "suggested_channel_24", "router_channel_5", "suggested_channel_5", "suggested_width_5",
    )

    fun export(records: List<RunRecord>): String = buildString {
        appendLine(header.joinToString(","))
        records.sortedBy { it.timestampMs }.forEach { r ->
            val cells = listOf(
                r.id, r.timestampMs, r.roomLabel, r.ssid, r.bssid, r.rssiDbm, r.linkSpeedMbps, r.frequencyMhz,
                r.band.label, r.channel, r.channelWidthMhz, r.standard.label, r.gatewayIp, r.gatewayAvgMs?.fmt(),
                r.gatewayMaxMs?.fmt(), r.gatewayJitterMs?.fmt(), r.gatewayLossPercent?.fmt(), r.internetTarget,
                r.internetAvgMs?.fmt(), r.internetMaxMs?.fmt(), r.internetJitterMs?.fmt(), r.internetLossPercent?.fmt(),
                r.dnsSystemMs?.fmt(), r.dnsReferenceMs?.fmt(), r.throughputMbps?.fmt(), r.sameChannelNetworks,
                r.overlappingNetworks, r.severity.name, r.layer?.name, r.verdict, r.fixes.joinToString(" | "),
                r.countryCode, r.routerChannel24, r.suggestedChannel24, r.routerChannel5, r.suggestedChannel5, r.suggestedWidth5,
            )
            appendLine(cells.joinToString(",") { escape(it?.toString()) })
        }
    }

    // Locale-independent so the file opens the same everywhere.
    private fun Double.fmt(): String = String.format(java.util.Locale.ROOT, "%.2f", this)

    fun escape(value: String?): String {
        if (value == null) return ""
        // Guard against CSV/formula injection from SSIDs, which are attacker-controlled names.
        val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r" && value.toDoubleOrNull() == null) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + safe.replace("\"", "\"\"") + "\""
        } else {
            safe
        }
    }
}
