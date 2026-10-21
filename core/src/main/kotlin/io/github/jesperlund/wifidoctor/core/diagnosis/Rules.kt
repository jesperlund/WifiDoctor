package io.github.jesperlund.wifidoctor.core.diagnosis

import io.github.jesperlund.wifidoctor.core.model.Band
import io.github.jesperlund.wifidoctor.core.model.Finding
import io.github.jesperlund.wifidoctor.core.model.Layer
import io.github.jesperlund.wifidoctor.core.model.Measurements
import io.github.jesperlund.wifidoctor.core.model.NatBehavior
import io.github.jesperlund.wifidoctor.core.model.ProbeStats
import io.github.jesperlund.wifidoctor.core.model.Severity
import io.github.jesperlund.wifidoctor.core.wifi.ChannelOption
import io.github.jesperlund.wifidoctor.core.wifi.Region
import io.github.jesperlund.wifidoctor.core.wifi.WifiMath

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
            ChannelPlanRule,
            SlowThroughputRule,
            SlowUploadRule,
            BufferbloatRule,
            UdpBlockedRule,
            CallPortBlockedRule,
            UdpQualityRule,
            SymmetricNatRule,
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
                    val analysis = m.channels
                    val plan = link?.let { analysis?.band(it.band) }
                    val best = plan?.recommended
                    if (analysis != null && plan != null && best != null && best.channel != plan.current?.channel) {
                        add(channelFix(plan.band, best, analysis.region))
                    } else {
                        add("Pick a less crowded channel in the router settings (or enable automatic channel selection).")
                    }
                    if (link?.band == Band.GHZ_2_4) add("Use the 5 GHz band, which has many more non-overlapping channels.")
                    add("On 2.4 GHz, use a 20 MHz channel width.")
                },
            )
        }
    }

    /** Same wording everywhere so the Diagnoser's de-duplication merges identical advice. */
    fun channelFix(band: Band, option: ChannelOption, region: Region): String = buildString {
        append("In the router's settings, set the ${band.label} channel to ${option.channel} (${option.widthMhz} MHz)")
        if (region.countryCode != null) append(", which is permitted in ${region.countryName ?: region.countryCode}")
        append('.')
        if (option.dfs) append(" It is a DFS (radar) channel: the router may pause for about a minute when it starts.")
    }

    /**
     * Region-aware channel plan for every band the router uses, including the band the phone is not
     * connected on (typically 2.4 GHz): flags channels that are not permitted where you are, much
     * quieter alternatives, and needlessly wide 2.4 GHz channels.
     */
    object ChannelPlanRule : DiagnosisRule {
        override fun evaluate(m: Measurements, config: DiagnoserConfig): Finding? {
            val analysis = m.channels ?: return null
            val region = analysis.region
            val lines = mutableListOf<String>()
            val fixes = mutableListOf<String>()
            var notPermitted = false
            for (b in analysis.bands) {
                val current = b.current ?: continue
                val best = b.recommended
                if (b.currentAllowed == false) {
                    notPermitted = true
                    lines += "${b.band.label}: channel ${current.channel} is not permitted in ${region.countryName ?: region.countryCode} " +
                        "(${region.domain.label}). Some devices will not be able to see the network."
                    if (best != null) fixes += channelFix(b.band, best, region)
                } else if (b.shouldChange && best != null) {
                    lines += "${b.band.label}: your router uses channel ${current.channel}, shared with an interference score of " +
                        "${"%.1f".format(current.score)} from ${b.neighbours} nearby networks; channel ${best.channel} scores " +
                        "${"%.1f".format(best.score)}."
                    fixes += channelFix(b.band, best, region)
                }
                if (b.band == Band.GHZ_2_4 && current.widthMhz >= 40 && b.neighbours >= 3) {
                    lines += "2.4 GHz: your router uses a ${current.widthMhz} MHz channel, which overlaps most of the band and its neighbours."
                    fixes += "In the router's settings, set the 2.4 GHz channel width to 20 MHz."
                }
            }
            if (lines.isEmpty()) return null
            return Finding(
                ruleId = "channel_plan",
                severity = Severity.WARN,
                layer = Layer.CONGESTION,
                title = if (notPermitted) "Router uses a channel not permitted here" else "Better Wi-Fi channel available",
                explanation = lines.joinToString(" ") + " Channel rules: ${region.description}.",
                fixes = fixes + "Or enable the router's automatic channel selection, then run the check again.",
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

    object SlowUploadRule : DiagnosisRule {
        override fun evaluate(m: Measurements, config: DiagnoserConfig): Finding? {
            val up = m.upload ?: return null
            val mbps = up.mbps ?: return null
            if (up.cancelled || mbps >= config.slowUploadMbps) return null
            return Finding(
                ruleId = "slow_upload",
                severity = if (mbps < config.verySlowUploadMbps) Severity.FAIL else Severity.WARN,
                layer = Layer.THROUGHPUT,
                title = "Upload too slow for video calls",
                explanation = "Upload ran at ${"%.1f".format(mbps)} Mbps. Teams needs about 1.5–4 Mbps up for your video " +
                    "(more for screen sharing), and posting stories or videos needs more still. With too little upload " +
                    "others see you frozen or pixelated even when you see them fine.",
                fixes = listOf(
                    "Pause cloud backups (photos, OneDrive, iCloud, Google Photos) and other uploads during calls.",
                    "Turn off your own video in large meetings to save upload.",
                    "Check your plan's upload speed; cable and DSL plans often have little. Ask your provider about a plan with more upload.",
                ),
            )
        }
    }

    object BufferbloatRule : DiagnosisRule {
        override fun evaluate(m: Measurements, config: DiagnoserConfig): Finding? {
            val l = m.loaded ?: return null
            val worst = l.worstIncreaseMs
            val loss = listOfNotNull(l.download?.lossPercent, l.upload?.lossPercent).maxOrNull() ?: 0.0
            val bloated = worst != null && worst >= config.bufferbloatWarnMs
            val lossy = loss >= config.loadedLossWarnPercent
            if (!bloated && !lossy) return null
            val severe = (worst ?: 0.0) >= config.bufferbloatFailMs || loss >= 2 * config.loadedLossWarnPercent
            val direction = when {
                (l.uploadIncreaseMs ?: 0.0) > (l.downloadIncreaseMs ?: 0.0) -> "uploading"
                else -> "downloading"
            }
            return Finding(
                ruleId = "bufferbloat",
                severity = if (severe) Severity.FAIL else Severity.WARN,
                layer = Layer.LOAD,
                title = "Latency spikes when the line is busy (bufferbloat)",
                explanation = buildString {
                    append("Idle latency to ${l.target} is ${l.idle?.avgMs?.toInt() ?: "?"} ms, but it rose by ")
                    append(listOfNotNull(
                        l.downloadIncreaseMs?.let { "${it.toInt()} ms while downloading" },
                        l.uploadIncreaseMs?.let { "${it.toInt()} ms while uploading" },
                    ).joinToString(" and "))
                    if (lossy) append(", with ${"%.0f".format(loss)}% of probes lost")
                    append(". The router or modem queues too much data, so whenever anyone streams, downloads or backs up ")
                    append("photos, calls stutter and videos stall, even though speed tests look fine. Worst while $direction.")
                },
                fixes = listOf(
                    "Turn on Smart Queue Management in the router (called SQM, fq_codel, CAKE, Smart QoS or Adaptive QoS), " +
                        "set to about 90% of your plan's download and upload speeds.",
                    "If the router has no SQM, consider one that does (OpenWrt, eero, Ubiquiti, ASUS, Fritz!Box and others support it).",
                    "Short term: pause large downloads, game updates and cloud backups during calls.",
                ),
            )
        }
    }

    private fun tcpInternetWorks(m: Measurements) = m.internet.any { it.received > 0 }

    object UdpBlockedRule : DiagnosisRule {
        override fun evaluate(m: Measurements, config: DiagnoserConfig): Finding? {
            val r = m.realtime ?: return null
            if (r.anyReachable || r.servers.isEmpty() || !tcpInternetWorks(m)) return null
            return Finding(
                ruleId = "udp_blocked",
                severity = Severity.FAIL,
                layer = Layer.REALTIME,
                title = "UDP is blocked: calls fall back to a slower path",
                explanation = "None of the ${r.servers.size} call servers answered over UDP, although the internet works over TCP. " +
                    "Teams, WhatsApp, FaceTime and Meet then tunnel media over TCP, which turns small delays into stutter and freezes.",
                fixes = listOf(
                    "Check the router's firewall for rules blocking outgoing UDP, and turn off any 'SIP ALG' setting.",
                    "If you use a VPN or a security/parental-control app, test with it turned off.",
                    "On a work or school network, ask IT to allow UDP 3478–3481 to Microsoft Teams.",
                ),
            )
        }
    }

    object CallPortBlockedRule : DiagnosisRule {
        override fun evaluate(m: Measurements, config: DiagnoserConfig): Finding? {
            val r = m.realtime ?: return null
            val on3478 = r.servers.filter { it.server.port == 3478 }
            val others = r.servers.filter { it.server.port != 3478 }
            if (on3478.isEmpty() || on3478.any { it.reachable } || others.none { it.reachable }) return null
            return Finding(
                ruleId = "call_port_blocked",
                severity = Severity.WARN,
                layer = Layer.REALTIME,
                title = "Teams' media port (UDP 3478) is blocked",
                explanation = "UDP works on other ports but no server answered on port 3478, which Teams uses for audio and video. " +
                    "Teams will fall back to TCP or port 443, which copes worse with delay.",
                fixes = listOf(
                    "Allow outgoing UDP 3478–3481 in the router or firewall, and turn off 'SIP ALG'.",
                    "Test with any VPN or filtering app turned off.",
                ),
            )
        }
    }

    object UdpQualityRule : DiagnosisRule {
        override fun evaluate(m: Measurements, config: DiagnoserConfig): Finding? {
            val best = m.realtime?.best ?: return null
            val s = best.stats
            val problems = buildList {
                if ((s.avgMs ?: 0.0) > config.udpRttWarnMs) add("round trip ${s.avgMs?.toInt()} ms (target under ${config.udpRttWarnMs.toInt()})")
                if ((s.jitterMs ?: 0.0) > config.udpJitterWarnMs) add("jitter ${s.jitterMs?.toInt()} ms (target under ${config.udpJitterWarnMs.toInt()})")
                if (s.lossPercent > config.udpLossWarnPercent) add("${"%.0f".format(s.lossPercent)}% packet loss (target under ${config.udpLossWarnPercent.toInt()}%)")
            }
            if (problems.isEmpty()) return null
            return Finding(
                ruleId = "udp_quality",
                severity = if (s.lossPercent >= config.udpLossFailPercent) Severity.FAIL else Severity.WARN,
                layer = Layer.REALTIME,
                title = "Call traffic is choppy",
                explanation = "A burst of call-like UDP packets to ${best.server.label} showed ${problems.joinToString(", ")}. " +
                    "Audio breaks up and video freezes at these levels.",
                fixes = listOf(
                    "Run the call monitor during a real call to see when and where it happens.",
                    "Move closer to the router or use 5 GHz.",
                    "If latency also rises under load, enable Smart Queue Management (SQM) on the router.",
                ),
            )
        }
    }

    object SymmetricNatRule : DiagnosisRule {
        override fun evaluate(m: Measurements, config: DiagnoserConfig): Finding? {
            val r = m.realtime ?: return null
            if (r.nat != NatBehavior.SYMMETRIC) return null
            return Finding(
                ruleId = "symmetric_nat",
                severity = Severity.WARN,
                layer = Layer.REALTIME,
                title = "Strict (symmetric) NAT",
                explanation = "Your public port changes for every destination. Calls cannot connect directly and must be " +
                    "relayed through a server, which adds delay. This often comes from two routers in a row (double NAT) or " +
                    "carrier-grade NAT at the provider.",
                fixes = listOf(
                    "If you have a provider modem/router plus your own router, put the provider's box in bridge mode.",
                    "Enable UPnP or 'endpoint-independent mapping' (sometimes called full-cone NAT) in the router if available.",
                    "Ask your provider whether you are behind carrier-grade NAT and whether a public IPv4 address or IPv6 is available.",
                ),
            )
        }
    }
}
