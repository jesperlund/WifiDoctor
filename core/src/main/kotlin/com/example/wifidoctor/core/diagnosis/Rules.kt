package com.example.wifidoctor.core.diagnosis

import com.example.wifidoctor.core.model.Band
import com.example.wifidoctor.core.model.Finding
import com.example.wifidoctor.core.model.Layer
import com.example.wifidoctor.core.model.Measurements
import com.example.wifidoctor.core.model.ProbeStats
import com.example.wifidoctor.core.model.Severity
import com.example.wifidoctor.core.wifi.WifiMath

/** The built-in rule set. Each rule is independent and pure. */
object Rules {

    val all: List<DiagnosisRule> by lazy {
        listOf(
            NotConnectedRule,
            WeakSignalRule,
            LowLinkSpeedRule,
            BandSteeringRule,
            GatewayUnreachableRule,
            InterferenceRule,
            GatewayDegradedRule,
            IspUplinkRule,
            NoInternetRule,
            DnsRule,
            CongestionRule,
            SlowThroughputRule,
        )
    }

    // --- helpers -----------------------------------------------------------------------------

    internal fun ProbeStats.isGatewayBad(c: DiagnoserConfig): Boolean =
        received > 0 && (lossPercent >= c.gatewayLossWarnPercent ||
            (avgMs ?: 0.0) > c.gatewayHighLatencyMs ||
            (jitterMs ?: 0.0) > c.gatewayHighJitterMs)

    internal fun ProbeStats.isGatewayFine(c: DiagnoserConfig): Boolean = received > 0 && !isGatewayBad(c)

    internal fun ProbeStats.isInternetBad(c: DiagnoserConfig): Boolean =
        allLost || lossPercent >= c.internetLossWarnPercent ||
            (avgMs ?: 0.0) > c.internetHighLatencyMs ||
            (jitterMs ?: 0.0) > c.internetHighJitterMs

    internal fun ProbeStats.describe(): String = buildString {
        append("${"%.0f".format(lossPercent)}% loss")
        avgMs?.let { append(", ${it.toInt()} ms average") }
        maxMs?.let { append(" (max ${it.toInt()} ms)") }
        jitterMs?.let { append(", ${it.toInt()} ms jitter") }
    }

    private fun internetReachable(m: Measurements) = m.internet.any { it.received > 0 }

    // --- rules -------------------------------------------------------------------------------

    object NotConnectedRule : DiagnosisRule {
        override fun evaluate(m: Measurements, config: DiagnoserConfig): Finding? {
            if (m.link != null) return null
            return Finding(
                ruleId = "not_connected",
                severity = Severity.FAIL,
                layer = Layer.LINK,
                title = "Not connected to Wi-Fi",
                explanation = "The phone is not associated with a Wi-Fi network, so nothing else can be tested.",
                fixes = listOf(
                    "Turn Wi-Fi on and connect to your home network.",
                    "If the network does not appear, restart the router and wait two minutes.",
                ),
            )
        }
    }

    object WeakSignalRule : DiagnosisRule {
        override fun evaluate(m: Measurements, config: DiagnoserConfig): Finding? {
            val rssi = m.link?.rssiDbm ?: return null
            if (rssi >= config.weakRssiDbm) return null
            val veryWeak = rssi < config.veryWeakRssiDbm
            return Finding(
                ruleId = "weak_signal",
                severity = if (veryWeak) Severity.FAIL else Severity.WARN,
                layer = Layer.LINK,
                title = if (veryWeak) "Very weak Wi-Fi signal" else "Weak Wi-Fi signal",
                explanation = "Signal strength is $rssi dBm (below ${config.weakRssiDbm} dBm). " +
                    "You are probably too far from the router or walls, floors, mirrors or appliances are in the way. " +
                    "A weak signal forces slow, error-prone transmission and causes drop-outs.",
                fixes = listOf(
                    "Move closer to the router or remove obstructions between you and it.",
                    "Place the router up high, in the open and near the centre of the home.",
                    "Add a mesh node or access point in this part of the home.",
                ),
            )
        }
    }

    object LowLinkSpeedRule : DiagnosisRule {
        override fun evaluate(m: Measurements, config: DiagnoserConfig): Finding? {
            val link = m.link ?: return null
            val speed = link.linkSpeedMbps?.takeIf { it > 0 } ?: return null
            val typical = WifiMath.typicalMaxLinkSpeed(link.band, link.standard, link.channelWidthMhz) ?: return null
            val threshold = (typical * config.lowLinkSpeedFraction).toInt()
            if (speed >= threshold) return null
            return Finding(
                ruleId = "low_link_speed",
                severity = Severity.WARN,
                layer = Layer.LINK,
                title = "Low link speed",
                explanation = "The link negotiated $speed Mbps on ${link.band.label} " +
                    "(${link.standard.label}); a healthy link here is usually above $threshold Mbps " +
                    "(up to about $typical Mbps). Distance, interference or conservative router settings reduce it.",
                fixes = listOf(
                    "Move closer to the router and run the test again.",
                    "In the router settings, make sure the 5 GHz band uses 80 MHz channels and Wi-Fi 5/6 mode is enabled.",
                    "Update the router firmware.",
                ),
            )
        }
    }

    object BandSteeringRule : DiagnosisRule {
        override fun evaluate(m: Measurements, config: DiagnoserConfig): Finding? {
            val link = m.link ?: return null
            if (link.band != Band.GHZ_2_4) return null
            val ssid = link.ssid ?: return null
            val better = m.scan?.entries.orEmpty()
                .filter { it.ssid == ssid && it.band != Band.GHZ_2_4 && it.band != Band.UNKNOWN }
                .filter { it.rssiDbm >= config.bandSteeringMinRssiDbm }
                .maxByOrNull { it.rssiDbm } ?: return null
            return Finding(
                ruleId = "band_steering",
                severity = Severity.WARN,
                layer = Layer.LINK,
                title = "Stuck on 2.4 GHz",
                explanation = "You are connected to \"$ssid\" on 2.4 GHz, but the same network is available on " +
                    "${better.band.label} at ${better.rssiDbm} dBm. That band is usually much faster and less crowded.",
                fixes = listOf(
                    "Toggle Wi-Fi off and on so the phone re-picks the best band.",
                    "Enable band steering (sometimes called \"Smart Connect\") in the router.",
                    "Or give the 5 GHz band its own network name and connect to it directly.",
                ),
            )
        }
    }

    object GatewayUnreachableRule : DiagnosisRule {
        override fun evaluate(m: Measurements, config: DiagnoserConfig): Finding? {
            val gw = m.gateway ?: return null
            if (!gw.allLost) return null
            // Some routers drop probes but still forward traffic: that is not a problem.
            if (internetReachable(m)) return null
            return Finding(
                ruleId = "gateway_unreachable",
                severity = Severity.FAIL,
                layer = Layer.GATEWAY,
                title = "Router is not responding",
                explanation = "None of the ${gw.sent} probes to the router (${gw.target}) got an answer, and the " +
                    "internet is unreachable too. The router may be frozen or the Wi-Fi link is not passing traffic.",
                fixes = listOf(
                    "Restart the router: unplug it for 30 seconds, then wait two minutes.",
                    "Forget the network on the phone and reconnect.",
                ),
            )
        }
    }

    object InterferenceRule : DiagnosisRule {
        override fun evaluate(m: Measurements, config: DiagnoserConfig): Finding? {
            val link = m.link ?: return null
            val rssi = link.rssiDbm ?: return null
            val gw = m.gateway ?: return null
            if (rssi < config.strongRssiDbm || !gw.isGatewayBad(config)) return null
            val fail = gw.lossPercent >= config.gatewayLossFailPercent
            val neighbours = m.congestion?.let { " ${it.sameChannel + it.overlapping} nearby networks share or overlap your channel." } ?: ""
            val fixes = buildList {
                add("Change the router's Wi-Fi channel (on 2.4 GHz use 1, 6 or 11, whichever is least crowded).")
                if (link.band == Band.GHZ_2_4) add("Connect to the 5 GHz band if your router has one.")
                add("Move the router away from microwaves, cordless phones, baby monitors and Bluetooth speakers.")
                add("Restart the router to clear a possible overload.")
            }
            return Finding(
                ruleId = "interference",
                severity = if (fail) Severity.FAIL else Severity.WARN,
                layer = Layer.GATEWAY,
                title = "Interference or congestion near the router",
                explanation = "Signal is strong ($rssi dBm) yet the router responds poorly (${gw.describe()}). " +
                    "With a good signal this usually means radio interference or a crowded channel.$neighbours",
                fixes = fixes,
            )
        }
    }

    object GatewayDegradedRule : DiagnosisRule {
        override fun evaluate(m: Measurements, config: DiagnoserConfig): Finding? {
            val gw = m.gateway ?: return null
            val rssi = m.link?.rssiDbm
            // Strong signal is covered by InterferenceRule, weak signal by WeakSignalRule.
            if (rssi != null && (rssi >= config.strongRssiDbm || rssi < config.weakRssiDbm)) return null
            if (!gw.isGatewayBad(config)) return null
            return Finding(
                ruleId = "gateway_degraded",
                severity = if (gw.lossPercent >= config.gatewayLossFailPercent) Severity.FAIL else Severity.WARN,
                layer = Layer.GATEWAY,
                title = "Unstable connection to the router",
                explanation = "Probes to the router show ${gw.describe()}. The local Wi-Fi hop is unreliable.",
                fixes = listOf(
                    "Move a little closer to the router and test again.",
                    "Change the router's Wi-Fi channel.",
                    "Restart the router.",
                ),
            )
        }
    }

    object IspUplinkRule : DiagnosisRule {
        override fun evaluate(m: Measurements, config: DiagnoserConfig): Finding? {
            val gw = m.gateway ?: return null
            val inet = m.internetSummary ?: return null
            if (!gw.isGatewayFine(config) || !inet.isInternetBad(config)) return null
            val fail = inet.allLost || inet.lossPercent >= config.internetLossFailPercent
            return Finding(
                ruleId = "isp_uplink",
                severity = if (fail) Severity.FAIL else Severity.WARN,
                layer = Layer.INTERNET,
                title = if (inet.allLost) "Internet is down beyond the router" else "Problem between the router and the internet",
                explanation = "Your Wi-Fi to the router is fine (${gw.describe()}), but the internet is not " +
                    "(${inet.target}: ${inet.describe()}). The problem is the router's uplink or your provider, not Wi-Fi.",
                fixes = listOf(
                    "Check the modem/router status lights for an internet or WAN fault.",
                    "Restart the modem and router.",
                    "Check your provider's outage page, or contact them if it persists.",
                    "If someone is uploading or streaming heavily, pause it and test again.",
                ),
            )
        }
    }

    object NoInternetRule : DiagnosisRule {
        override fun evaluate(m: Measurements, config: DiagnoserConfig): Finding? {
            if (m.gateway != null || m.link == null) return null
            if (m.internet.isEmpty() || internetReachable(m)) return null
            return Finding(
                ruleId = "no_internet",
                severity = Severity.FAIL,
                layer = Layer.INTERNET,
                title = "No internet access",
                explanation = "None of the internet probe targets answered. The router address was unknown, so " +
                    "it was not possible to tell whether Wi-Fi or the provider is at fault.",
                fixes = listOf("Restart the router.", "Check your provider's outage page."),
            )
        }
    }

    object DnsRule : DiagnosisRule {
        override fun evaluate(m: Measurements, config: DiagnoserConfig): Finding? {
            val dns = m.dns ?: return null
            val inet = m.internetSummary
            // Only blame DNS when raw connectivity is fine.
            if (inet != null && inet.isInternetBad(config)) return null
            val fixes = listOf(
                "Set a faster DNS server, e.g. 1.1.1.1 or 8.8.8.8, in the router's DHCP/DNS settings.",
                "On Android 9+, you can also use Settings → Network → Private DNS (e.g. one.one.one.one).",
                "Restart the router to clear a stuck DNS cache.",
            )
            val sys = dns.systemAvgMs
            val ref = dns.referenceAvgMs
            if (sys == null && ref != null && dns.systemFailures == dns.lookups) {
                return Finding(
                    "dns_failing", Severity.FAIL, Layer.DNS, "DNS lookups are failing",
                    "Every lookup through your network's DNS server (${dns.systemServer}) failed, while " +
                        "${dns.referenceServer} answered in ${ref.toInt()} ms. Web pages will not load even though the connection works.",
                    fixes,
                )
            }
            if (sys == null || ref == null) return null
            val slow = sys > ref * config.dnsSlowRatio && sys - ref >= config.dnsSlowMarginMs
            val flaky = dns.systemFailures > 0 && dns.systemFailures > dns.referenceFailures
            if (!slow && !flaky) return null
            return Finding(
                ruleId = "dns_slow",
                severity = Severity.WARN,
                layer = Layer.DNS,
                title = if (slow) "Slow DNS" else "Unreliable DNS",
                explanation = "Lookups through your network's DNS (${dns.systemServer}) averaged ${sys.toInt()} ms" +
                    (if (dns.systemFailures > 0) " with ${dns.systemFailures} of ${dns.lookups} failing" else "") +
                    ", versus ${ref.toInt()} ms via ${dns.referenceServer}. Every new website waits for DNS, so browsing feels sluggish.",
                fixes = fixes,
            )
        }
    }

    object CongestionRule : DiagnosisRule {
        override fun evaluate(m: Measurements, config: DiagnoserConfig): Finding? {
            val c = m.congestion ?: return null
            val crowded = c.sameChannel >= config.congestedSameChannel || c.sameChannel + c.overlapping >= config.congestedOverlapping
            if (!crowded) return null
            val link = m.link
            return Finding(
                ruleId = "congestion",
                severity = Severity.WARN,
                layer = Layer.CONGESTION,
                title = "Crowded Wi-Fi channel",
                explanation = "${c.sameChannel} networks use the same channel" +
                    (link?.channel?.let { " ($it)" } ?: "") + " and ${c.overlapping} more overlap it. " +
                    "Networks on the same air time take turns, which slows everyone down at busy times.",
                fixes = buildList {
                    add("Pick a less crowded channel in the router settings (or enable automatic channel selection).")
                    if (link?.band == Band.GHZ_2_4) add("Use the 5 GHz band, which has many more non-overlapping channels.")
                    add("On 2.4 GHz, use a 20 MHz channel width.")
                },
            )
        }
    }

    object SlowThroughputRule : DiagnosisRule {
        override fun evaluate(m: Measurements, config: DiagnoserConfig): Finding? {
            val t = m.throughput ?: return null
            val mbps = t.mbps ?: return null
            if (t.cancelled || mbps >= config.slowThroughputMbps) return null
            return Finding(
                ruleId = "slow_throughput",
                severity = Severity.WARN,
                layer = Layer.THROUGHPUT,
                title = "Slow download speed",
                explanation = "The test download ran at ${"%.1f".format(mbps)} Mbps, which is too slow for HD video " +
                    "or large downloads. If other checks passed, your plan or the provider's network may be the limit.",
                fixes = listOf(
                    "Pause other downloads, backups and streams on the network and test again.",
                    "Compare with a wired speed test to see whether the plan itself is slow.",
                    "Contact your provider if speeds are far below what you pay for.",
                ),
            )
        }
    }
}
