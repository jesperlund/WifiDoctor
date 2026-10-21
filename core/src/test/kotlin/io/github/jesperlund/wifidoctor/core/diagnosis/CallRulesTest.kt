package io.github.jesperlund.wifidoctor.core.diagnosis

import io.github.jesperlund.wifidoctor.core.Fixtures.healthy
import io.github.jesperlund.wifidoctor.core.Fixtures.lost
import io.github.jesperlund.wifidoctor.core.Fixtures.probe
import io.github.jesperlund.wifidoctor.core.model.Layer
import io.github.jesperlund.wifidoctor.core.model.LoadedLatency
import io.github.jesperlund.wifidoctor.core.model.NatBehavior
import io.github.jesperlund.wifidoctor.core.model.ProbeMethod
import io.github.jesperlund.wifidoctor.core.model.RealtimeResult
import io.github.jesperlund.wifidoctor.core.model.Severity
import io.github.jesperlund.wifidoctor.core.model.StunServer
import io.github.jesperlund.wifidoctor.core.model.ThroughputResult
import io.github.jesperlund.wifidoctor.core.model.UdpProbeResult
import io.github.jesperlund.wifidoctor.core.probe.ProbeMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRulesTest {
    private val c = DiagnoserConfig()

    private fun loaded(idle: Double = 15.0, down: Double? = 20.0, up: Double? = 25.0, loss: Int = 0) = LoadedLatency(
        "1.1.1.1",
        probe("1.1.1.1", avg = idle),
        down?.let { probe("1.1.1.1", avg = it, sent = 20, received = 20 - loss) },
        up?.let { probe("1.1.1.1", avg = it, sent = 20, received = 20) },
    )

    private fun udp(server: StunServer, rtts: List<Double?>, mapped: String? = "203.0.113.1:5000") =
        UdpProbeResult(server, ProbeMath.stats(server.label, rtts, ProbeMethod.UDP), if (rtts.any { it != null }) mapped else null)

    private val cf = StunServer("stun.cloudflare.com", 3478)
    private val google = StunServer("stun.l.google.com", 19302)

    @Test
    fun `small increase under load is fine`() {
        assertNull(Rules.BufferbloatRule.evaluate(healthy().copy(loaded = loaded()), c))
    }

    @Test
    fun `large increase under load is bufferbloat`() {
        val warn = Rules.BufferbloatRule.evaluate(healthy().copy(loaded = loaded(up = 120.0)), c)!!
        assertEquals(Severity.WARN, warn.severity)
        assertEquals(Layer.LOAD, warn.layer)
        assertTrue(warn.explanation.contains("105 ms while uploading"))
        assertTrue(warn.explanation.contains("Worst while uploading"))
        assertTrue(warn.fixes.first().contains("Smart Queue Management"))
        assertEquals(Severity.FAIL, Rules.BufferbloatRule.evaluate(healthy().copy(loaded = loaded(down = 400.0)), c)!!.severity)
    }

    @Test
    fun `loss under load counts too`() {
        assertEquals(Severity.WARN, Rules.BufferbloatRule.evaluate(healthy().copy(loaded = loaded(loss = 1)), c)!!.severity)
    }

    @Test
    fun `missing samples are not judged`() {
        assertNull(Rules.BufferbloatRule.evaluate(healthy().copy(loaded = loaded(down = null, up = null)), c))
    }

    @Test
    fun `slow upload for video calls`() {
        fun up(mbps: Double) = healthy().copy(upload = ThroughputResult("u", 1, 1, mbps))
        assertNull(Rules.SlowUploadRule.evaluate(up(10.0), c))
        assertEquals(Severity.WARN, Rules.SlowUploadRule.evaluate(up(1.2), c)!!.severity)
        assertEquals(Severity.FAIL, Rules.SlowUploadRule.evaluate(up(0.3), c)!!.severity)
    }

    @Test
    fun `udp blocked while tcp works`() {
        val r = RealtimeResult(listOf(udp(cf, List(15) { null }), udp(google, List(15) { null })), NatBehavior.UNKNOWN)
        val f = Rules.UdpBlockedRule.evaluate(healthy().copy(realtime = r), c)!!
        assertEquals(Severity.FAIL, f.severity)
        assertEquals(Layer.REALTIME, f.layer)
        // With no internet at all, UDP is not singled out.
        assertNull(Rules.UdpBlockedRule.evaluate(healthy(internet = listOf(lost("1.1.1.1"))).copy(realtime = r), c))
    }

    @Test
    fun `port 3478 blocked while other udp works`() {
        val r = RealtimeResult(listOf(udp(cf, List(15) { null }), udp(google, List(15) { 20.0 })), NatBehavior.UNKNOWN)
        val m = healthy().copy(realtime = r)
        assertEquals("call_port_blocked", Rules.CallPortBlockedRule.evaluate(m, c)!!.ruleId)
        assertNull(Rules.UdpBlockedRule.evaluate(m, c))
    }

    @Test
    fun `udp quality thresholds`() {
        fun q(rtts: List<Double?>) = Rules.UdpQualityRule.evaluate(
            healthy().copy(realtime = RealtimeResult(listOf(udp(cf, rtts)), NatBehavior.UNKNOWN)), c,
        )
        assertNull(q(List(15) { 30.0 }))
        assertTrue(q(List(15) { 200.0 })!!.explanation.contains("round trip 200 ms"))
        assertTrue(q(List(15) { if (it % 2 == 0) 20.0 else 80.0 })!!.explanation.contains("jitter 60 ms"))
        assertEquals(Severity.FAIL, q(List(14) { 20.0 } + null)!!.severity) // 1 of 15 lost = 6.7%
    }

    @Test
    fun `symmetric nat warning`() {
        val r = RealtimeResult(listOf(udp(cf, listOf(10.0), "1.1.1.1:1"), udp(google, listOf(10.0), "1.1.1.1:2")), NatBehavior.SYMMETRIC)
        assertTrue(Rules.SymmetricNatRule.evaluate(healthy().copy(realtime = r), c)!!.fixes.first().contains("bridge mode"))
        assertNull(Rules.SymmetricNatRule.evaluate(healthy().copy(realtime = r.copy(nat = NatBehavior.ENDPOINT_INDEPENDENT)), c))
    }
}
