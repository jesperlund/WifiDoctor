package io.github.jesperlund.wifidoctor.core.monitor

import io.github.jesperlund.wifidoctor.core.diagnosis.Diagnoser
import io.github.jesperlund.wifidoctor.core.model.Diagnosis
import io.github.jesperlund.wifidoctor.core.model.Finding
import io.github.jesperlund.wifidoctor.core.model.Layer
import io.github.jesperlund.wifidoctor.core.model.Severity

/** Call-quality targets, based on Microsoft's network guidance for Teams (round-trip values). */
data class CallQualityConfig(
    val p95WarnMs: Double = 150.0,
    val p95FailMs: Double = 300.0,
    val jitterWarnMs: Double = 30.0,
    val lossWarnPercent: Double = 1.0,
    val lossFailPercent: Double = 5.0,
    /** Router round trips this high mean the Wi-Fi hop itself is slow. */
    val gatewayP95WarnMs: Double = 50.0,
    val dropoutFailCount: Int = 3,
    val dropoutFailTotalMs: Long = 3_000,
    /** More roams than this per 10 minutes is "ping-ponging" between access points. */
    val roamsPer10MinWarn: Int = 4,
    val weakRssiDbm: Int = -72,
)

/** Turns a call-monitor timeline into a diagnosis with fixes. */
object MonitorAnalyzer {

    fun diagnose(r: MonitorReport, c: CallQualityConfig = CallQualityConfig()): Diagnosis {
        val findings = buildList {
            addAll(dropoutFindings(r, c))
            roamingFinding(r, c)?.let(::add)
            latencyFinding(r, c)?.let(::add)
            jitterFinding(r, c)?.let(::add)
            lossFinding(r, c)?.let(::add)
            signalFinding(r, c)?.let(::add)
        }.sortedWith(Diagnoser.byPriority)
        return Diagnoser.assemble(findings, "Connection stayed call-ready", okExplanation(r))
    }

    private fun minutes(ms: Long) = ms / 60_000.0
    private fun seconds(ms: Long) = "%.1f s".format(ms / 1000.0)

    private fun okExplanation(r: MonitorReport) = buildString {
        append("Over ${"%.1f".format(minutes(r.durationMs))} minutes, the internet answered with a median of ")
        append("${r.internet.p50Ms?.toInt() ?: "?"} ms and 95% of round trips under ${r.internet.p95Ms?.toInt() ?: "?"} ms, ")
        append("${"%.1f".format(r.internet.lossPercent)}% loss and no drop-outs. ")
        append("If a call still went badly during this time, the cause is likely the app, the other participants' connection, or the service itself.")
    }

    private fun dropoutFindings(r: MonitorReport, c: CallQualityConfig): List<Finding> {
        if (r.dropouts.isEmpty()) return emptyList()
        fun severity(list: List<Dropout>) =
            if (list.size >= c.dropoutFailCount || list.sumOf { it.durationMs } >= c.dropoutFailTotalMs) Severity.FAIL else Severity.WARN
        fun describe(list: List<Dropout>) =
            "${list.size} drop-out${if (list.size == 1) "" else "s"} (${seconds(list.sumOf { it.durationMs })} in total, longest ${seconds(list.maxOf { it.durationMs })})"
        val out = mutableListOf<Finding>()

        val disconnected = r.dropouts.filter { it.cause == DropCause.DISCONNECTED }
        if (disconnected.isNotEmpty()) out += Finding(
            "monitor_disconnect", Severity.FAIL, Layer.LINK, "Wi-Fi disconnected during the session",
            "The phone lost its Wi-Fi connection: ${describe(disconnected)}. Every call drops or freezes when this happens.",
            listOf(
                "Check whether the router restarts or crashes (uptime in its status page); update its firmware.",
                "In Android Wi-Fi settings, make sure 'switch to mobile data' / 'adaptive Wi-Fi' is not flipping networks during calls.",
                "Move closer to the router or add a mesh node if the signal is weak where you take calls.",
            ),
        )

        val roaming = r.dropouts.filter { it.cause == DropCause.ROAMING }
        if (roaming.isNotEmpty()) out += Finding(
            "monitor_roaming_dropout", severity(roaming), Layer.LINK, "Drop-outs when switching access points",
            "Connection gaps lined up with the phone moving to a different access point: ${describe(roaming)}. " +
                "Mesh systems and extenders often take 1–3 seconds to hand over, which freezes calls.",
            listOf(
                "Enable fast roaming (802.11r/k/v) on the mesh/router if it is off; if it is on and drop-outs persist, try turning 802.11r off.",
                "Place nodes so their coverage overlaps less where you sit, or stay near one node during calls.",
                "Use the same network name for all nodes and bands, but avoid separate extenders with their own network names.",
            ),
        )

        val wifi = r.dropouts.filter { it.scope == DropScope.WIFI && it.cause != DropCause.ROAMING && it.cause != DropCause.DISCONNECTED }
        if (wifi.isNotEmpty()) out += Finding(
            "monitor_wifi_dropout", severity(wifi), Layer.GATEWAY, "Wi-Fi drop-outs",
            "The router itself stopped answering: ${describe(wifi)}" +
                (if (wifi.any { it.cause == DropCause.WEAK_SIGNAL }) ", while the signal was weak" else "") +
                ". The problem is between the phone and the router: interference, a weak signal, or the router stalling.",
            listOf(
                "Use 5 GHz and a less crowded channel (see Channel advice on the Dashboard).",
                "Move closer to the router or remove obstructions.",
                "Restart the router and update its firmware; check whether many devices are connected.",
            ),
        )

        val internet = r.dropouts.filter { it.scope == DropScope.INTERNET && it.cause != DropCause.ROAMING && it.cause != DropCause.DISCONNECTED }
        if (internet.isNotEmpty()) out += Finding(
            "monitor_internet_dropout", severity(internet), Layer.INTERNET, "Internet drop-outs beyond the router",
            "Wi-Fi stayed up, but the internet stopped answering: ${describe(internet)}. " +
                "The problem is the modem or the provider's line, not Wi-Fi.",
            listOf(
                "Look at the modem's log or status page for line resyncs or disconnects at those times.",
                "Check cables and splitters to the modem; restart it.",
                "If drop-outs repeat, report them to your provider with the times from History.",
            ),
        )

        val unknown = r.dropouts.filter { it.scope == DropScope.UNKNOWN && it.cause != DropCause.ROAMING && it.cause != DropCause.DISCONNECTED }
        if (unknown.isNotEmpty()) out += Finding(
            "monitor_dropout", severity(unknown), Layer.INTERNET, "Connection drop-outs",
            "The internet stopped answering: ${describe(unknown)}. The router address was unknown, so Wi-Fi and the provider could not be told apart.",
            listOf("Restart the router and modem.", "Run the monitor again while connected to Wi-Fi with location access granted."),
        )
        return out
    }

    private fun roamingFinding(r: MonitorReport, c: CallQualityConfig): Finding? {
        if (r.roams.isEmpty() || r.dropouts.any { it.cause == DropCause.ROAMING }) return null
        val per10 = r.roams.size / (minutes(r.durationMs) / 10.0).coerceAtLeast(0.1)
        if (per10 <= c.roamsPer10MinWarn) return null
        return Finding(
            "monitor_ping_pong", Severity.WARN, Layer.LINK, "Phone keeps switching access points",
            "The phone switched access points ${r.roams.size} times in ${"%.1f".format(minutes(r.durationMs))} minutes. " +
                "Each switch can cause a short hiccup in calls.",
            listOf(
                "Reduce the overlap between mesh nodes or lower their transmit power.",
                "Disable band steering or 'smart connect' if the phone keeps flipping between 2.4 and 5 GHz.",
            ),
        )
    }

    private fun latencyFinding(r: MonitorReport, c: CallQualityConfig): Finding? {
        val p95 = r.internet.p95Ms ?: return null
        if (p95 <= c.p95WarnMs) return null
        val gwP95 = r.gateway?.p95Ms
        val wifiSide = gwP95 != null && (gwP95 > c.gatewayP95WarnMs || r.gatewaySpikes * 2 >= r.internetSpikes.coerceAtLeast(1))
        val severity = if (p95 > c.p95FailMs) Severity.FAIL else Severity.WARN
        val stats = "median ${r.internet.p50Ms?.toInt()} ms, 95th percentile ${p95.toInt()} ms, worst ${r.internet.maxMs?.toInt()} ms"
        return if (wifiSide) {
            Finding(
                "monitor_wifi_latency", severity, Layer.GATEWAY, "Latency spikes on Wi-Fi",
                "Round trips to the internet spiked ($stats), and so did round trips to the router itself " +
                    "(95th percentile ${gwP95?.toInt()} ms). The delays come from the Wi-Fi hop: interference, a busy channel or power saving.",
                listOf(
                    "Switch to 5 GHz and a quieter channel (see Channel advice on the Dashboard).",
                    "Move closer to the router; avoid microwaves, baby monitors and Bluetooth speakers near it.",
                    "Pause other heavy Wi-Fi users (game downloads, 4K streaming) during calls.",
                ),
            )
        } else {
            Finding(
                "monitor_internet_latency", severity, Layer.LOAD, "Latency spikes beyond the router",
                "Round trips to the internet spiked ($stats) while the router stayed fast. This is usually another device " +
                    "downloading or uploading (bufferbloat), or congestion at the provider.",
                listOf(
                    "Enable Smart Queue Management (SQM / fq_codel / CAKE / Smart QoS) on the router.",
                    "Find what else is using the connection during calls (cloud backups, game updates, streaming) and pause it.",
                    "If spikes happen at the same times every day, report them to your provider.",
                ),
            )
        }
    }

    private fun jitterFinding(r: MonitorReport, c: CallQualityConfig): Finding? {
        val jitter = r.internet.jitterMs ?: return null
        if (jitter <= c.jitterWarnMs) return null
        return Finding(
            "monitor_jitter", Severity.WARN, Layer.INTERNET, "High jitter",
            "Latency varied by ${jitter.toInt()} ms between consecutive samples on average (Teams needs under ${c.jitterWarnMs.toInt()} ms). " +
                "Calls compensate with buffering, which adds delay and robotic audio.",
            listOf("Use 5 GHz close to the router.", "Enable Smart Queue Management (SQM) on the router."),
        )
    }

    private fun lossFinding(r: MonitorReport, c: CallQualityConfig): Finding? {
        val loss = r.scatteredLossPercent
        if (loss <= c.lossWarnPercent) return null
        return Finding(
            "monitor_loss", if (loss >= c.lossFailPercent) Severity.FAIL else Severity.WARN, Layer.GATEWAY, "Scattered packet loss",
            "${"%.1f".format(loss)}% of samples were lost outside of drop-outs (Teams needs under ${c.lossWarnPercent.toInt()}%). " +
                "Lost packets are heard as clipped words and seen as blocky video.",
            listOf("Move closer to the router or use 5 GHz.", "Change to a quieter Wi-Fi channel.", "Restart the router."),
        )
    }

    private fun signalFinding(r: MonitorReport, c: CallQualityConfig): Finding? {
        val min = r.rssiMinDbm ?: return null
        if (min >= c.weakRssiDbm) return null
        return Finding(
            "monitor_weak_signal", Severity.WARN, Layer.LINK, "Signal dipped during the session",
            "Signal strength fell to $min dBm (average ${r.rssiAvgDbm} dBm). Below about ${c.weakRssiDbm} dBm calls become fragile.",
            listOf("Take calls closer to the router or add a mesh node nearby.", "Keep the phone out of pockets/behind the laptop while on a call."),
        )
    }
}
