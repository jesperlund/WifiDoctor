package io.github.jesperlund.wifidoctor.core.diagnosis

import io.github.jesperlund.wifidoctor.core.Fixtures.healthy
import io.github.jesperlund.wifidoctor.core.model.EndpointFailure
import io.github.jesperlund.wifidoctor.core.model.EndpointResult
import io.github.jesperlund.wifidoctor.core.model.Layer
import io.github.jesperlund.wifidoctor.core.model.ServiceCheck
import io.github.jesperlund.wifidoctor.core.model.ServiceEndpoint
import io.github.jesperlund.wifidoctor.core.model.ServiceResult
import io.github.jesperlund.wifidoctor.core.model.Services
import io.github.jesperlund.wifidoctor.core.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceRulesTest {
    private val c = DiagnoserConfig()

    private fun ok(e: ServiceEndpoint, connect: Double = 20.0, firstByte: Double = 60.0) =
        EndpointResult(e, listOf("157.240.1.1"), 5.0, connect, 40.0, firstByte, 200)

    private fun failed(e: ServiceEndpoint, failure: EndpointFailure, addresses: List<String> = emptyList()) =
        EndpointResult(e, addresses, 5.0, failure = failure)

    private fun check(
        instagram: (ServiceEndpoint) -> EndpointResult = { ok(it) },
        teams: (ServiceEndpoint) -> EndpointResult = { ok(it) },
        baseline: EndpointResult? = ok(Services.BASELINE, connect = 15.0),
    ) = ServiceCheck(
        listOf(
            ServiceResult(Services.INSTAGRAM, Services.INSTAGRAM.endpoints.map(instagram)),
            ServiceResult(Services.TEAMS, Services.TEAMS.endpoints.map(teams)),
        ),
        baseline,
    )

    private fun findings(check: ServiceCheck) = Diagnoser().findings(healthy().copy(services = check))

    @Test
    fun `healthy services produce nothing`() {
        assertTrue(findings(check()).isEmpty())
        assertEquals("Instagram:OK:125ms;Microsoft Teams:OK:125ms", check().summary)
    }

    @Test
    fun `dns filter blocking instagram's cdn`() {
        val f = findings(check(instagram = {
            if (it.host.contains("cdninstagram")) failed(it, EndpointFailure.DNS_BLOCKED, listOf("0.0.0.0")) else ok(it)
        })).single()
        assertEquals("service_dns_blocked", f.ruleId)
        assertEquals(Severity.FAIL, f.severity)
        assertEquals(Layer.SERVICES, f.layer)
        assertEquals("Instagram blocked by DNS filtering", f.title)
        assertTrue(f.explanation.contains("Photos and videos (CDN) (scontent.cdninstagram.com) resolved to 0.0.0.0"))
        assertTrue(f.fixes.first().contains("*.cdninstagram.com"))
    }

    @Test
    fun `teams relay unreachable while other sites work`() {
        val f = findings(check(teams = {
            if (it.host.startsWith("worldaz")) failed(it, EndpointFailure.CONNECT_FAILED) else ok(it)
        })).single()
        assertEquals("service_unreachable", f.ruleId)
        assertEquals(Severity.WARN, f.severity)
        assertEquals("Some Microsoft Teams servers unreachable", f.title)
        assertTrue(f.explanation.contains("Call media relay (TCP 443) (worldaz.tr.teams.microsoft.com): cannot connect"))
    }

    @Test
    fun `everything failing for one service is a failure, with a TLS hint`() {
        val f = findings(check(instagram = { failed(it, EndpointFailure.TLS_FAILED) })).single()
        assertEquals(Severity.FAIL, f.severity)
        assertEquals("Instagram servers unreachable", f.title)
        assertTrue(f.explanation.contains("intercepted"))
        assertTrue(f.fixes.first().contains("date and time"))
    }

    @Test
    fun `no blame on the service when the reference fails too`() {
        val dead = check(instagram = { failed(it, EndpointFailure.CONNECT_FAILED) }, baseline = failed(Services.BASELINE, EndpointFailure.CONNECT_FAILED))
        assertTrue(findings(dead).none { it.ruleId == "service_unreachable" })
    }

    @Test
    fun `slow route compared with the reference`() {
        val f = findings(check(instagram = { ok(it, connect = 140.0) })).single()
        assertEquals("service_slow", f.ruleId)
        assertEquals("Slow connection to Instagram", f.title)
        assertTrue(f.explanation.contains("140 ms per round trip, versus 15 ms"))
        // 3x but less than the 80 ms margin is fine.
        assertTrue(findings(check(instagram = { ok(it, connect = 60.0) })).isEmpty())
    }

    @Test
    fun `slow first response`() {
        val f = findings(check(teams = { ok(it, firstByte = 2_500.0) })).single()
        assertTrue(f.explanation.contains("slow to answer"))
    }

    @Test
    fun `nothing measured, nothing reported`() {
        ServiceRules.all.forEach { assertNull(it.evaluate(healthy(), c)) }
    }
}
