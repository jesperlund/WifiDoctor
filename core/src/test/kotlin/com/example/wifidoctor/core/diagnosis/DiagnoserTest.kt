package com.example.wifidoctor.core.diagnosis

import com.example.wifidoctor.core.Fixtures
import com.example.wifidoctor.core.Fixtures.healthy
import com.example.wifidoctor.core.Fixtures.link
import com.example.wifidoctor.core.Fixtures.lost
import com.example.wifidoctor.core.Fixtures.probe
import com.example.wifidoctor.core.model.CongestionResult
import com.example.wifidoctor.core.model.Layer
import com.example.wifidoctor.core.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnoserTest {
    private val diagnoser = Diagnoser()

    @Test
    fun `healthy network gets an OK verdict without fixes`() {
        val d = diagnoser.diagnose(healthy())
        assertEquals(Severity.OK, d.severity)
        assertNull(d.layer)
        assertTrue(d.fixes.isEmpty())
        assertTrue(d.explanation.contains("-50 dBm"))
    }

    @Test
    fun `not connected diagnosis`() {
        val d = diagnoser.diagnose(healthy(link = null, gateway = null, internet = emptyList()))
        assertEquals(Severity.FAIL, d.severity)
        assertEquals(Layer.LINK, d.layer)
        assertEquals("Not connected to Wi-Fi", d.verdict)
    }

    @Test
    fun `failures outrank warnings even on a later layer`() {
        // Weak (WARN, link) + internet down (FAIL, internet).
        val m = healthy(link = link(rssi = -72), internet = listOf(lost("1.1.1.1"), lost("8.8.8.8")))
        val d = diagnoser.diagnose(m)
        assertEquals(Severity.FAIL, d.severity)
        assertEquals(Layer.INTERNET, d.layer)
        assertTrue(d.explanation.contains("Also noticed: weak Wi-Fi signal"))
    }

    @Test
    fun `equal severity reports the first failing layer`() {
        // Slow DNS (WARN, DNS) + weak signal (WARN, link) + congestion (WARN).
        val m = healthy(
            link = link(rssi = -72),
            dns = Fixtures.dns(system = 300.0, reference = 20.0),
            congestion = CongestionResult(5, 2, 10),
        )
        val d = diagnoser.diagnose(m)
        assertEquals(Layer.LINK, d.layer)
        assertEquals(listOf(Layer.LINK, Layer.DNS, Layer.CONGESTION), d.findings.map { it.layer })
    }

    @Test
    fun `fixes are ordered by finding priority and de-duplicated`() {
        val m = healthy(link = link(rssi = -45), gateway = probe(avg = 90.0), congestion = CongestionResult(6, 2, 12))
        val d = diagnoser.diagnose(m)
        assertEquals("interference", d.findings.first().ruleId)
        assertEquals(d.findings.first().fixes.first(), d.fixes.first())
        assertEquals(d.fixes.size, d.fixes.toSet().size)
    }

    @Test
    fun `layer severity feeds the progress stepper`() {
        val m = healthy(link = link(rssi = -85))
        assertEquals(Severity.FAIL, diagnoser.layerSeverity(m, Layer.LINK))
        assertEquals(Severity.OK, diagnoser.layerSeverity(m, Layer.DNS))
    }

    @Test
    fun `custom rule list is honoured`() {
        val only = Diagnoser(rules = listOf(Rules.DnsRule))
        assertEquals(Severity.OK, only.diagnose(healthy(link = link(rssi = -90))).severity)
    }
}
