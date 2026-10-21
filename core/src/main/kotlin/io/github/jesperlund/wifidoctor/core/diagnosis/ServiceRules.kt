package io.github.jesperlund.wifidoctor.core.diagnosis

import io.github.jesperlund.wifidoctor.core.model.EndpointFailure
import io.github.jesperlund.wifidoctor.core.model.EndpointResult
import io.github.jesperlund.wifidoctor.core.model.Finding
import io.github.jesperlund.wifidoctor.core.model.Layer
import io.github.jesperlund.wifidoctor.core.model.Measurements
import io.github.jesperlund.wifidoctor.core.model.ServiceResult
import io.github.jesperlund.wifidoctor.core.model.Severity

/** Rules for the app-specific server checks (Instagram, Teams). */
object ServiceRules {

    /** Domains to allow in DNS filters / firewalls, per service. */
    private val domains = mapOf(
        "instagram" to "*.instagram.com, *.cdninstagram.com and *.fbcdn.net",
        "teams" to "*.teams.microsoft.com, *.teams.cdn.office.net, login.microsoftonline.com and *.skype.com",
    )

    private fun domainsFor(r: ServiceResult) = domains[r.target.id] ?: r.endpoints.joinToString { it.endpoint.host }

    private fun EndpointResult.describe() = "${endpoint.role} (${endpoint.host})"

    /** The reference server worked, so failures are specific to the service rather than "no internet". */
    private fun baselineOk(m: Measurements) = m.services?.baseline?.ok == true

    val all: List<DiagnosisRule> = listOf(
        DiagnosisRule { m, _ -> dnsBlocked(m) },
        DiagnosisRule { m, _ -> unreachable(m) },
        DiagnosisRule { m, c -> slow(m, c) },
    )

    private fun dnsBlocked(m: Measurements): Finding? {
        val hits = m.services?.results.orEmpty().mapNotNull { r ->
            r.endpoints.filter { it.failure == EndpointFailure.DNS_BLOCKED }.takeIf { it.isNotEmpty() }?.let { r to it }
        }
        if (hits.isEmpty()) return null
        val names = hits.joinToString(" and ") { it.first.target.name }
        return Finding(
            ruleId = "service_dns_blocked",
            severity = Severity.FAIL,
            layer = Layer.SERVICES,
            title = "$names blocked by DNS filtering",
            explanation = hits.joinToString(" ") { (r, eps) ->
                "${r.target.name}: ${eps.joinToString { "${it.describe()} resolved to ${it.addresses.joinToString().ifEmpty { "nothing" }}" }}."
            } + " An ad blocker, parental control or security DNS (Pi-hole, AdGuard, NextDNS, the router's filter) is blocking these names, " +
                "so the app partly or fully fails to load.",
            fixes = buildList {
                hits.forEach { (r, _) -> add("Allow ${domainsFor(r)} in your DNS filter or the router's parental controls.") }
                add("Check Android Settings → Network → Private DNS; set it to Automatic to test without a filtering DNS.")
                add("Compare on mobile data: if it works there, the block is on your home network.")
            },
        )
    }

    private fun unreachable(m: Measurements): Finding? {
        if (!baselineOk(m)) return null
        val affected = m.services?.results.orEmpty().filter { r -> r.endpoints.any { !it.ok && it.failure != EndpointFailure.DNS_BLOCKED } }
        if (affected.isEmpty()) return null
        val any = affected.any { r -> r.endpoints.all { !it.ok } }
        val tls = affected.flatMap { it.failed }.any { it.failure == EndpointFailure.TLS_FAILED }
        val title = if (affected.size == 1) {
            val r = affected.single()
            if (r.allFailed) "${r.target.name} servers unreachable" else "Some ${r.target.name} servers unreachable"
        } else {
            "${affected.joinToString(" and ") { it.target.name }} servers partly unreachable"
        }
        return Finding(
            ruleId = "service_unreachable",
            severity = if (any) Severity.FAIL else Severity.WARN,
            layer = Layer.SERVICES,
            title = title,
            explanation = affected.joinToString(" ") { r ->
                "${r.target.name}: " + r.failed.joinToString("; ") { "${it.describe()}: ${it.failure?.label?.lowercase()}" } + "."
            } + " Other websites work, so something on the way blocks or breaks these specific servers." +
                (if (tls) " A failing secure connection usually means traffic is being intercepted (security software, a VPN, " +
                    "a captive portal or a school/work filter) or the phone's date and time are wrong." else ""),
            fixes = buildList {
                if (tls) add("Check the phone's date and time are set automatically, and test with any VPN or security app turned off.")
                affected.forEach { r -> add("Allow ${domainsFor(r)} in the router firewall, parental controls or security filter.") }
                add("Restart the router; if it persists only at home, ask your provider whether these services are blocked or misrouted.")
            },
        )
    }

    private fun slow(m: Measurements, c: DiagnoserConfig): Finding? {
        val check = m.services ?: return null
        val ref = check.baseline?.connectMs?.takeIf { check.baseline.ok }
        val slow = check.results.mapNotNull { r ->
            val connect = r.medianConnectMs ?: return@mapNotNull null
            val slowRoute = ref != null && connect > ref * c.serviceSlowFactor && connect - ref >= c.serviceSlowMarginMs
            val slowServer = r.endpoints.mapNotNull { it.firstByteMs }.any { it > c.serviceSlowFirstByteMs }
            if (!slowRoute && !slowServer) null else Triple(r, slowRoute, connect)
        }
        if (slow.isEmpty()) return null
        return Finding(
            ruleId = "service_slow",
            severity = Severity.WARN,
            layer = Layer.SERVICES,
            title = "Slow connection to ${slow.joinToString(" and ") { it.first.target.name }}",
            explanation = slow.joinToString(" ") { (r, route, connect) ->
                if (route) {
                    "Reaching ${r.target.name}'s servers takes ${connect.toInt()} ms per round trip, versus ${ref?.toInt()} ms to the reference server. " +
                        "Your provider's route to them is poor (often a congested link between networks at busy hours)."
                } else {
                    "${r.target.name}'s servers were slow to answer (over ${(c.serviceSlowFirstByteMs / 1000).toInt()} s for the first response)."
                }
            } + " Videos then start slowly and stall, and calls may be relayed less directly.",
            fixes = listOf(
                "Test again at a different time of day; if it is only slow in the evening, report it to your provider with the History export.",
                "If you use a VPN, test without it; VPN exit points are often far from these servers.",
                "Try a different DNS server (e.g. 1.1.1.1 or 8.8.8.8) so the apps are pointed to nearer servers.",
            ),
        )
    }
}
