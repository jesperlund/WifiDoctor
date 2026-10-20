package io.github.jesperlund.wifidoctor.core

import io.github.jesperlund.wifidoctor.core.model.CongestionResult
import io.github.jesperlund.wifidoctor.core.model.DnsResult
import io.github.jesperlund.wifidoctor.core.model.LinkInfo
import io.github.jesperlund.wifidoctor.core.model.Measurements
import io.github.jesperlund.wifidoctor.core.model.ProbeStats
import io.github.jesperlund.wifidoctor.core.model.ScanEntry
import io.github.jesperlund.wifidoctor.core.model.ScanSnapshot
import io.github.jesperlund.wifidoctor.core.model.ThroughputResult
import io.github.jesperlund.wifidoctor.core.model.WifiStandard

/** Fixture data shared by the rule and engine tests. Defaults describe a healthy home network. */
object Fixtures {
    const val SSID = "HomeNet"
    const val BSSID_24 = "aa:bb:cc:00:00:01"
    const val BSSID_5 = "aa:bb:cc:00:00:02"

    fun link(
        rssi: Int? = -50,
        speed: Int? = 866,
        frequency: Int? = 5180,
        width: Int? = 80,
        standard: WifiStandard = WifiStandard.AC,
        ssid: String? = SSID,
        bssid: String? = BSSID_5,
        gateway: String? = "192.168.1.1",
        dns: List<String> = listOf("192.168.1.1"),
    ) = LinkInfo(
        ssid = ssid, bssid = bssid, rssiDbm = rssi, linkSpeedMbps = speed, frequencyMhz = frequency,
        channelWidthMhz = width, standard = standard, gatewayIp = gateway, dnsServers = dns,
    )

    fun link24(rssi: Int = -50, speed: Int = 144) =
        link(rssi = rssi, speed = speed, frequency = 2437, width = 20, standard = WifiStandard.N, bssid = BSSID_24)

    fun probe(
        target: String = "192.168.1.1",
        avg: Double? = 4.0,
        max: Double? = avg?.times(2),
        jitter: Double? = 1.0,
        sent: Int = 10,
        received: Int = 10,
    ) = ProbeStats(target, sent, received, if (received == 0) null else avg, avg?.div(2), if (received == 0) null else max, jitter)

    fun lost(target: String) = ProbeStats(target, 10, 0, null, null, null, null)

    fun dns(system: Double? = 20.0, reference: Double? = 18.0, systemFailures: Int = 0, referenceFailures: Int = 0) =
        DnsResult("192.168.1.1", "1.1.1.1", 5, system, systemFailures, reference, referenceFailures)

    fun healthy(
        link: LinkInfo? = link(),
        gateway: ProbeStats? = probe(),
        internet: List<ProbeStats> = listOf(probe("1.1.1.1", avg = 15.0), probe("8.8.8.8", avg = 18.0)),
        dns: DnsResult? = dns(),
        throughput: ThroughputResult? = ThroughputResult("https://x", 50_000_000, 1_000, 400.0),
        scan: ScanSnapshot? = ScanSnapshot(emptyList(), throttled = false),
        congestion: CongestionResult? = CongestionResult(0, 1, 3),
        channels: io.github.jesperlund.wifidoctor.core.wifi.ChannelAnalysis? = null,
    ) = Measurements(
        timestampMs = 1_700_000_000_000,
        link = link, gateway = gateway, internet = internet, dns = dns,
        throughput = throughput, scan = scan, congestion = congestion, channels = channels,
    )

    fun scanEntry(ssid: String? = "Neighbour", bssid: String? = "11:22:33:44:55:66", freq: Int = 2437, rssi: Int = -70, width: Int? = 20) =
        ScanEntry(ssid, bssid, freq, rssi, width)
}
