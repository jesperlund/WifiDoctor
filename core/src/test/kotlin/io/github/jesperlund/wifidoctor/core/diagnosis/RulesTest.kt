package io.github.jesperlund.wifidoctor.core.diagnosis

import io.github.jesperlund.wifidoctor.core.Fixtures
import io.github.jesperlund.wifidoctor.core.Fixtures.healthy
import io.github.jesperlund.wifidoctor.core.Fixtures.link
import io.github.jesperlund.wifidoctor.core.Fixtures.link24
import io.github.jesperlund.wifidoctor.core.Fixtures.lost
import io.github.jesperlund.wifidoctor.core.Fixtures.probe
import io.github.jesperlund.wifidoctor.core.Fixtures.scanEntry
import io.github.jesperlund.wifidoctor.core.model.CongestionResult
import io.github.jesperlund.wifidoctor.core.model.Layer
import io.github.jesperlund.wifidoctor.core.model.ScanSnapshot
import io.github.jesperlund.wifidoctor.core.model.Severity
import io.github.jesperlund.wifidoctor.core.model.ThroughputResult
import io.github.jesperlund.wifidoctor.core.model.WifiStandard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val c = DiagnoserConfig()

    // ---- every rule is silent on a healthy network -------------------------------------------

    @Test
    fun `no rule fires for a healthy network`() {
        Rules.all.forEach { rule ->
            assertNull("${rule::class.simpleName} fired on healthy data", rule.evaluate(healthy(), c))
        }
    }

    // ---- not connected -----------------------------------------------------------------------

    @Test
    fun `not connected fails the link layer`() {
        val f = Rules.NotConnectedRule.evaluate(healthy(link = null), c)!!
        assertEquals(Severity.FAIL, f.severity)
        assertEquals(Layer.LINK, f.layer)
    }

    // ---- weak signal -------------------------------------------------------------------------

    @Test
    fun `rssi below -70 is a weak signal warning about distance or obstruction`() {
        val f = Rules.WeakSignalRule.evaluate(healthy(link = link(rssi = -74)), c)!!
        assertEquals(Severity.WARN, f.severity)
        assertEquals(Layer.LINK, f.layer)
        assertTrue(f.explanation.contains("-74 dBm"))
        assertTrue(f.explanation.contains("too far") && f.explanation.contains("walls"))
        assertTrue(f.fixes.first().contains("closer"))
    }

    @Test
    fun `rssi of exactly -70 is not weak`() {
        assertNull(Rules.WeakSignalRule.evaluate(healthy(link = link(rssi = -70)), c))
    }

    @Test
    fun `rssi below -80 is a failure`() {
        assertEquals(Severity.FAIL, Rules.WeakSignalRule.evaluate(healthy(link = link(rssi = -85)), c)!!.severity)
    }

    @Test
    fun `unknown rssi does not trigger weak signal`() {
        assertNull(Rules.WeakSignalRule.evaluate(healthy(link = link(rssi = null)), c))
    }

    // ---- interference ------------------------------------------------------------------------

    @Test
    fun `strong signal with high gateway latency means interference`() {
        val m = healthy(link = link(rssi = -45), gateway = probe(avg = 80.0, jitter = 5.0))
        val f = Rules.InterferenceRule.evaluate(m, c)!!
        assertEquals(Layer.GATEWAY, f.layer)
        assertEquals(Severity.WARN, f.severity)
        assertTrue(f.fixes.any { it.contains("channel") })
    }

    @Test
    fun `strong signal with heavy gateway loss is a failure`() {
        val m = healthy(link = link(rssi = -45), gateway = probe(received = 8))
        assertEquals(Severity.FAIL, Rules.InterferenceRule.evaluate(m, c)!!.severity)
    }

    @Test
    fun `interference on 2_4 GHz suggests moving to 5 GHz`() {
        val m = healthy(link = link24(rssi = -50), gateway = probe(avg = 60.0))
        val f = Rules.InterferenceRule.evaluate(m, c)!!
        assertTrue(f.fixes.any { it.contains("5 GHz") })
    }

    @Test
    fun `interference on 5 GHz does not suggest 5 GHz`() {
        val m = healthy(link = link(rssi = -50), gateway = probe(avg = 60.0))
        assertTrue(Rules.InterferenceRule.evaluate(m, c)!!.fixes.none { it.contains("Connect to the 5 GHz") })
    }

    @Test
    fun `interference mentions neighbouring networks when the scan is known`() {
        val m = healthy(link = link(rssi = -50), gateway = probe(avg = 60.0), congestion = CongestionResult(3, 2, 9))
        assertTrue(Rules.InterferenceRule.evaluate(m, c)!!.explanation.contains("5 nearby networks"))
    }

    @Test
    fun `high gateway jitter alone counts as a bad gateway`() {
        val m = healthy(link = link(rssi = -45), gateway = probe(avg = 10.0, jitter = 35.0))
        assertNotNull(Rules.InterferenceRule.evaluate(m, c))
    }

    @Test
    fun `interference needs a strong signal`() {
        val m = healthy(link = link(rssi = -65), gateway = probe(avg = 80.0))
        assertNull(Rules.InterferenceRule.evaluate(m, c))
        // ...in which case the generic gateway rule covers it.
        assertNotNull(Rules.GatewayDegradedRule.evaluate(m, c))
    }

    @Test
    fun `weak signal with bad gateway is left to the weak signal rule`() {
        val m = healthy(link = link(rssi = -78), gateway = probe(avg = 80.0))
        assertNull(Rules.InterferenceRule.evaluate(m, c))
        assertNull(Rules.GatewayDegradedRule.evaluate(m, c))
        assertNotNull(Rules.WeakSignalRule.evaluate(m, c))
    }

    // ---- gateway unreachable -----------------------------------------------------------------

    @Test
    fun `gateway and internet both silent means router not responding`() {
        val m = healthy(gateway = lost("192.168.1.1"), internet = listOf(lost("1.1.1.1"), lost("8.8.8.8")))
        val f = Rules.GatewayUnreachableRule.evaluate(m, c)!!
        assertEquals(Severity.FAIL, f.severity)
        assertEquals(Layer.GATEWAY, f.layer)
    }

    @Test
    fun `router that ignores probes but forwards traffic is fine`() {
        val m = healthy(gateway = lost("192.168.1.1"))
        assertNull(Rules.GatewayUnreachableRule.evaluate(m, c))
        assertNull(Rules.InterferenceRule.evaluate(m, c))
        assertNull(Rules.IspUplinkRule.evaluate(m, c))
    }

    // ---- ISP / uplink ------------------------------------------------------------------------

    @Test
    fun `gateway fine but internet lossy points at the ISP`() {
        val m = healthy(internet = listOf(probe("1.1.1.1", avg = 30.0, received = 7), probe("8.8.8.8", avg = 30.0, received = 7)))
        val f = Rules.IspUplinkRule.evaluate(m, c)!!
        assertEquals(Layer.INTERNET, f.layer)
        assertEquals(Severity.FAIL, f.severity) // 30% loss >= 20%
        assertTrue(f.explanation.contains("provider"))
    }

    @Test
    fun `gateway fine but internet slow is a warning`() {
        val m = healthy(internet = listOf(probe("1.1.1.1", avg = 250.0)))
        assertEquals(Severity.WARN, Rules.IspUplinkRule.evaluate(m, c)!!.severity)
    }

    @Test
    fun `internet completely down beyond a healthy router`() {
        val m = healthy(internet = listOf(lost("1.1.1.1"), lost("8.8.8.8")))
        val f = Rules.IspUplinkRule.evaluate(m, c)!!
        assertEquals(Severity.FAIL, f.severity)
        assertTrue(f.title.contains("down"))
    }

    @Test
    fun `one good internet target is enough`() {
        val m = healthy(internet = listOf(lost("1.1.1.1"), probe("8.8.8.8", avg = 20.0)))
        assertNull(Rules.IspUplinkRule.evaluate(m, c))
    }

    @Test
    fun `isp rule does not fire when the gateway is also bad`() {
        val m = healthy(gateway = probe(avg = 90.0), internet = listOf(probe("1.1.1.1", avg = 300.0)))
        assertNull(Rules.IspUplinkRule.evaluate(m, c))
    }

    @Test
    fun `no internet with unknown gateway`() {
        val m = healthy(gateway = null, internet = listOf(lost("1.1.1.1")))
        assertEquals(Layer.INTERNET, Rules.NoInternetRule.evaluate(m, c)!!.layer)
    }

    // ---- DNS ---------------------------------------------------------------------------------

    @Test
    fun `pings fine but DNS slow suggests switching DNS`() {
        val m = healthy(dns = Fixtures.dns(system = 240.0, reference = 20.0))
        val f = Rules.DnsRule.evaluate(m, c)!!
        assertEquals(Layer.DNS, f.layer)
        assertEquals(Severity.WARN, f.severity)
        assertTrue(f.fixes.first().contains("1.1.1.1"))
    }

    @Test
    fun `dns twice as slow but within margin is fine`() {
        assertNull(Rules.DnsRule.evaluate(healthy(dns = Fixtures.dns(system = 30.0, reference = 10.0)), c))
    }

    @Test
    fun `dns slower by margin but not ratio is fine`() {
        assertNull(Rules.DnsRule.evaluate(healthy(dns = Fixtures.dns(system = 150.0, reference = 90.0)), c))
    }

    @Test
    fun `system resolver failing entirely is a DNS failure`() {
        val m = healthy(dns = Fixtures.dns(system = null, reference = 20.0, systemFailures = 5))
        assertEquals(Severity.FAIL, Rules.DnsRule.evaluate(m, c)!!.severity)
    }

    @Test
    fun `occasional system resolver failures are flagged as unreliable`() {
        val f = Rules.DnsRule.evaluate(healthy(dns = Fixtures.dns(systemFailures = 2)), c)!!
        assertEquals("Unreliable DNS", f.title)
    }

    @Test
    fun `dns is not blamed when the internet itself is bad`() {
        val m = healthy(internet = listOf(lost("1.1.1.1")), dns = Fixtures.dns(system = 400.0, reference = 20.0))
        assertNull(Rules.DnsRule.evaluate(m, c))
    }

    // ---- low link speed ----------------------------------------------------------------------

    @Test
    fun `low link speed for 5 GHz wifi 5 at 80 MHz`() {
        val f = Rules.LowLinkSpeedRule.evaluate(healthy(link = link(speed = 120)), c)!!
        assertEquals(Layer.LINK, f.layer)
        assertTrue(f.fixes.any { it.contains("closer") })
        assertTrue(f.fixes.any { it.contains("router settings") })
    }

    @Test
    fun `same speed is fine on 2_4 GHz wifi 4`() {
        assertNull(Rules.LowLinkSpeedRule.evaluate(healthy(link = link24(speed = 120)), c))
    }

    @Test
    fun `low speed on 2_4 GHz wifi 4`() {
        assertNotNull(Rules.LowLinkSpeedRule.evaluate(healthy(link = link24(speed = 26)), c))
    }

    @Test
    fun `wifi 6 expects more than wifi 5`() {
        val ax = link(speed = 250, standard = WifiStandard.AX)
        val ac = link(speed = 250, standard = WifiStandard.AC)
        assertNotNull(Rules.LowLinkSpeedRule.evaluate(healthy(link = ax), c))
        assertNull(Rules.LowLinkSpeedRule.evaluate(healthy(link = ac), c))
    }

    @Test
    fun `unknown link speed is ignored`() {
        assertNull(Rules.LowLinkSpeedRule.evaluate(healthy(link = link(speed = null)), c))
        assertNull(Rules.LowLinkSpeedRule.evaluate(healthy(link = link(speed = -1)), c))
        assertNull(Rules.LowLinkSpeedRule.evaluate(healthy(link = link(speed = 6, frequency = null, standard = WifiStandard.UNKNOWN)), c))
    }

    // ---- band steering -----------------------------------------------------------------------

    private fun steeringScan(rssi5: Int, ssid: String = Fixtures.SSID) = ScanSnapshot(
        listOf(
            scanEntry(ssid = Fixtures.SSID, bssid = Fixtures.BSSID_24, freq = 2437, rssi = -50),
            scanEntry(ssid = ssid, bssid = Fixtures.BSSID_5, freq = 5180, rssi = rssi5, width = 80),
        ),
        throttled = false,
    )

    @Test
    fun `on 2_4 GHz while a strong 5 GHz BSSID of the same SSID exists`() {
        val f = Rules.BandSteeringRule.evaluate(healthy(link = link24(), scan = steeringScan(-60)), c)!!
        assertEquals(Severity.WARN, f.severity)
        assertTrue(f.explanation.contains("5 GHz at -60 dBm"))
        assertTrue(f.fixes.any { it.contains("band steering") })
    }

    @Test
    fun `weak 5 GHz BSSID does not trigger band steering`() {
        assertNull(Rules.BandSteeringRule.evaluate(healthy(link = link24(), scan = steeringScan(-75)), c))
    }

    @Test
    fun `5 GHz BSSID of a different network does not count`() {
        assertNull(Rules.BandSteeringRule.evaluate(healthy(link = link24(), scan = steeringScan(-50, ssid = "Other")), c))
    }

    @Test
    fun `already on 5 GHz means no band steering advice`() {
        assertNull(Rules.BandSteeringRule.evaluate(healthy(link = link(), scan = steeringScan(-50)), c))
    }

    @Test
    fun `hidden ssid cannot be matched`() {
        val l = link24().copy(ssid = null)
        assertNull(Rules.BandSteeringRule.evaluate(healthy(link = l, scan = steeringScan(-50)), c))
    }

    // ---- congestion and throughput -----------------------------------------------------------

    @Test
    fun `many networks on the same channel is congestion`() {
        val f = Rules.CongestionRule.evaluate(healthy(link = link24(), congestion = CongestionResult(4, 0, 10)), c)!!
        assertEquals(Layer.CONGESTION, f.layer)
        assertTrue(f.fixes.any { it.contains("5 GHz") })
    }

    @Test
    fun `same plus overlapping networks is congestion`() {
        assertNotNull(Rules.CongestionRule.evaluate(healthy(congestion = CongestionResult(2, 4, 10)), c))
        assertNull(Rules.CongestionRule.evaluate(healthy(congestion = CongestionResult(2, 3, 10)), c))
    }

    @Test
    fun `slow download is flagged but cancelled tests are not`() {
        val slow = ThroughputResult("u", 1_000_000, 4_000, 2.0)
        assertEquals(Layer.THROUGHPUT, Rules.SlowThroughputRule.evaluate(healthy(throughput = slow), c)!!.layer)
        assertNull(Rules.SlowThroughputRule.evaluate(healthy(throughput = slow.copy(cancelled = true)), c))
        assertNull(Rules.SlowThroughputRule.evaluate(healthy(throughput = slow.copy(mbps = null)), c))
    }

    // ---- config ------------------------------------------------------------------------------

    @Test
    fun `thresholds are configurable`() {
        val strict = DiagnoserConfig(weakRssiDbm = -60)
        assertNotNull(Rules.WeakSignalRule.evaluate(healthy(link = link(rssi = -65)), strict))
    }
}
