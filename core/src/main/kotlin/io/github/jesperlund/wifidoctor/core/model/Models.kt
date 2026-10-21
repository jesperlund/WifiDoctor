package io.github.jesperlund.wifidoctor.core.model

/** Radio band of a Wi-Fi network, derived from its centre frequency. */
enum class Band(val label: String) {
    GHZ_2_4("2.4 GHz"),
    GHZ_5("5 GHz"),
    GHZ_6("6 GHz"),
    UNKNOWN("Unknown"),
}

/** IEEE 802.11 generation the current link negotiated. */
enum class WifiStandard(val label: String) {
    LEGACY("802.11a/b/g"),
    N("Wi-Fi 4 (802.11n)"),
    AC("Wi-Fi 5 (802.11ac)"),
    AX("Wi-Fi 6 (802.11ax)"),
    BE("Wi-Fi 7 (802.11be)"),
    UNKNOWN("Unknown"),
}

/**
 * Snapshot of the current Wi-Fi association. Every field may be null because Android redacts
 * values without location permission and some devices simply do not report them.
 */
data class LinkInfo(
    val ssid: String? = null,
    val bssid: String? = null,
    val rssiDbm: Int? = null,
    val linkSpeedMbps: Int? = null,
    val txLinkSpeedMbps: Int? = null,
    val rxLinkSpeedMbps: Int? = null,
    val frequencyMhz: Int? = null,
    val channelWidthMhz: Int? = null,
    val standard: WifiStandard = WifiStandard.UNKNOWN,
    val gatewayIp: String? = null,
    val dnsServers: List<String> = emptyList(),
) {
    val band: Band get() = io.github.jesperlund.wifidoctor.core.wifi.WifiMath.bandFor(frequencyMhz)
    val channel: Int? get() = io.github.jesperlund.wifidoctor.core.wifi.WifiMath.channelFor(frequencyMhz)
}

/** Latency/loss summary of a series of probes. Times are milliseconds. */
data class ProbeStats(
    val target: String,
    val sent: Int,
    val received: Int,
    val avgMs: Double?,
    val minMs: Double?,
    val maxMs: Double?,
    val jitterMs: Double?,
    val method: ProbeMethod = ProbeMethod.TCP,
) {
    val lossPercent: Double get() = if (sent == 0) 0.0 else (sent - received) * 100.0 / sent
    val allLost: Boolean get() = sent > 0 && received == 0
}

enum class ProbeMethod { TCP, PING, UDP, NONE }

/** Result of timing DNS lookups through the system resolver and a reference resolver. */
data class DnsResult(
    val systemServer: String,
    val referenceServer: String,
    val lookups: Int,
    val systemAvgMs: Double?,
    val systemFailures: Int,
    val referenceAvgMs: Double?,
    val referenceFailures: Int,
)

data class ThroughputResult(
    val url: String,
    val bytes: Long,
    val durationMs: Long,
    val mbps: Double?,
    val error: String? = null,
    val cancelled: Boolean = false,
)

/** One access point seen in a Wi-Fi scan. */
data class ScanEntry(
    val ssid: String?,
    val bssid: String?,
    val frequencyMhz: Int,
    val rssiDbm: Int,
    val channelWidthMhz: Int? = null,
    /** Country from the access point's 802.11d country element, if it broadcasts one. */
    val countryCode: String? = null,
) {
    val band: Band get() = io.github.jesperlund.wifidoctor.core.wifi.WifiMath.bandFor(frequencyMhz)
    val channel: Int? get() = io.github.jesperlund.wifidoctor.core.wifi.WifiMath.channelFor(frequencyMhz)
}

data class ScanSnapshot(
    val entries: List<ScanEntry>,
    /** True when a fresh scan was refused (Android throttling) and cached results were used. */
    val throttled: Boolean,
    val ageMs: Long? = null,
)

data class CongestionResult(
    val sameChannel: Int,
    val overlapping: Int,
    val totalVisible: Int,
)

/** Everything one diagnostic run measured. Null means "not measured / unavailable". */
data class Measurements(
    val timestampMs: Long,
    val link: LinkInfo?,
    val gateway: ProbeStats? = null,
    val internet: List<ProbeStats> = emptyList(),
    val dns: DnsResult? = null,
    val throughput: ThroughputResult? = null,
    val scan: ScanSnapshot? = null,
    val congestion: CongestionResult? = null,
    /** Upload test (the download test is [throughput]). */
    val upload: ThroughputResult? = null,
    /** Latency while the download and upload tests saturate the line (bufferbloat). */
    val loaded: LoadedLatency? = null,
    /** UDP round trips and NAT behaviour on the path voice/video calls use. */
    val realtime: RealtimeResult? = null,
    /** Per-band channel survey (2.4, 5 and, where permitted, 6 GHz) with region-aware suggestions. */
    val channels: io.github.jesperlund.wifidoctor.core.wifi.ChannelAnalysis? = null,
) {
    /** The best responding internet target (lowest loss, then latency) or null when none were probed. */
    val internetSummary: ProbeStats?
        get() = internet.minWithOrNull(
            compareBy<ProbeStats> { it.lossPercent }.thenBy { it.avgMs ?: Double.MAX_VALUE },
        )
}

enum class Severity { OK, WARN, FAIL }

/** Diagnostic layers in the order the engine checks them. */
enum class Layer(val label: String) {
    LINK("Wi-Fi link"),
    GATEWAY("Router"),
    INTERNET("Internet"),
    DNS("DNS"),
    THROUGHPUT("Speed"),
    LOAD("Under load"),
    REALTIME("Calls (UDP)"),
    CONGESTION("Congestion"),
}

/** A single rule's conclusion. */
data class Finding(
    val ruleId: String,
    val severity: Severity,
    val layer: Layer,
    val title: String,
    val explanation: String,
    val fixes: List<String>,
)

data class Diagnosis(
    val verdict: String,
    val severity: Severity,
    /** Layer of the primary finding, or null when everything is healthy. */
    val layer: Layer?,
    val explanation: String,
    val fixes: List<String>,
    val findings: List<Finding> = emptyList(),
)

/**
 * Latency to an internet target while idle versus while the line is saturated. A large increase
 * ("bufferbloat") makes calls stutter whenever anyone downloads or uploads.
 */
data class LoadedLatency(
    val target: String,
    val idle: ProbeStats?,
    val download: ProbeStats?,
    val upload: ProbeStats?,
) {
    val downloadIncreaseMs: Double? get() = increase(download)
    val uploadIncreaseMs: Double? get() = increase(upload)
    val worstIncreaseMs: Double? get() = listOfNotNull(downloadIncreaseMs, uploadIncreaseMs).maxOrNull()

    private fun increase(loaded: ProbeStats?): Double? {
        val base = idle?.avgMs ?: return null
        val under = loaded?.avgMs ?: return null
        return (under - base).coerceAtLeast(0.0)
    }
}

data class StunServer(val host: String, val port: Int, val label: String = "$host:$port") {
    companion object {
        /** "host:port" or "host" (port 3478). */
        fun parse(spec: String, label: String? = null): StunServer? {
            val trimmed = spec.trim()
            if (trimmed.isEmpty()) return null
            val host = trimmed.substringBeforeLast(':', trimmed)
            val port = if (trimmed.contains(':')) trimmed.substringAfterLast(':').toIntOrNull() ?: return null else 3478
            if (host.isBlank() || port !in 1..65535) return null
            return StunServer(host, port, label ?: "$host:$port")
        }

        val DEFAULTS = listOf(
            StunServer("stun.cloudflare.com", 3478, "Cloudflare (UDP 3478)"),
            StunServer("stun.l.google.com", 19302, "Google (UDP 19302)"),
            StunServer("worldaz.tr.teams.microsoft.com", 3478, "Microsoft Teams relay (UDP 3478)"),
        )
    }
}

data class UdpProbeResult(
    val server: StunServer,
    val stats: ProbeStats,
    /** Public "ip:port" the NAT mapped us to, as seen by the server. */
    val mappedAddress: String?,
    val error: String? = null,
) {
    val reachable: Boolean get() = stats.received > 0
}

enum class NatBehavior(val label: String) {
    ENDPOINT_INDEPENDENT("Open (same mapping for every destination)"),
    SYMMETRIC("Symmetric (calls must be relayed)"),
    UNKNOWN("Unknown"),
}

data class RealtimeResult(val servers: List<UdpProbeResult>, val nat: NatBehavior) {
    val anyReachable: Boolean get() = servers.any { it.reachable }

    /** Best answering server: lowest loss, then lowest latency. */
    val best: UdpProbeResult?
        get() = servers.filter { it.reachable }
            .minWithOrNull(compareBy<UdpProbeResult> { it.stats.lossPercent }.thenBy { it.stats.avgMs ?: Double.MAX_VALUE })
}
