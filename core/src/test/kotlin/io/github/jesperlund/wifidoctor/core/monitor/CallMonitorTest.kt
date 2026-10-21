package io.github.jesperlund.wifidoctor.core.monitor

import io.github.jesperlund.wifidoctor.core.Fixtures
import io.github.jesperlund.wifidoctor.core.model.Layer
import io.github.jesperlund.wifidoctor.core.model.LinkInfo
import io.github.jesperlund.wifidoctor.core.model.Severity
import io.github.jesperlund.wifidoctor.core.probe.RttProbe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallMonitorTest {

    /** Scripted network: [rtt] decides each probe's result from virtual time and host. */
    private fun TestScope.monitor(
        link: (Long) -> LinkInfo? = { Fixtures.link() },
        rtt: (t: Long, host: String) -> Double?,
    ) = CallMonitor(
        linkSource = { link(currentTime) },
        probe = RttProbe { host, _ -> rtt(currentTime, host) },
        clock = { currentTime },
    )

    private val gw = "192.168.1.1"

    @Test
    fun `steady connection gives percentiles and no drop-outs`() = runTest {
        val progress = mutableListOf<MonitorProgress>()
        val report = monitor { t, host -> if (host == gw) 3.0 else 20.0 + (t / 200 % 5) }
            .run(MonitorConfig(durationMs = 60_000), onProgress = { progress += it })
        assertEquals(300, report.samples.size)
        assertEquals(60, report.linkSamples.size)
        assertEquals(gw, report.gatewayIp)
        assertEquals(0.0, report.internet.lossPercent, 0.0)
        assertEquals(22.0, report.internet.p50Ms!!, 0.0)
        assertEquals(24.0, report.internet.p95Ms!!, 0.0)
        assertTrue(report.dropouts.isEmpty())
        assertFalse(report.stoppedEarly)
        assertEquals(60, progress.size)
        assertEquals(Severity.OK, MonitorAnalyzer.diagnose(report).severity)
    }

    @Test
    fun `internet-only gap is a provider-side drop-out`() = runTest {
        val report = monitor { t, host -> if (host == gw) 3.0 else if (t in 10_000 until 12_000) null else 20.0 }
            .run(MonitorConfig(durationMs = 30_000))
        val d = report.dropouts.single()
        assertEquals(10_000, d.startMs)
        assertEquals(2_000, d.durationMs)
        assertEquals(DropScope.INTERNET, d.scope)
        assertEquals(DropCause.NONE, d.cause)
        val diagnosis = MonitorAnalyzer.diagnose(report)
        assertEquals("monitor_internet_dropout", diagnosis.findings.first().ruleId)
        assertEquals(Layer.INTERNET, diagnosis.layer)
    }

    @Test
    fun `gap while switching access points is blamed on roaming`() = runTest {
        val link = { t: Long -> Fixtures.link(bssid = if (t < 20_000) "aa:aa" else "bb:bb") }
        val report = monitor(link) { t, _ -> if (t in 19_600 until 21_000) null else 10.0 }
            .run(MonitorConfig(durationMs = 40_000))
        assertEquals(1, report.roams.size)
        assertEquals("aa:aa", report.roams.single().fromBssid)
        val d = report.dropouts.single()
        assertEquals(DropScope.WIFI, d.scope)
        assertEquals(DropCause.ROAMING, d.cause)
        assertEquals("monitor_roaming_dropout", MonitorAnalyzer.diagnose(report).findings.first().ruleId)
    }

    @Test
    fun `disconnection is reported as a failure`() = runTest {
        val link = { t: Long -> if (t in 5_000 until 8_000) null else Fixtures.link() }
        val report = monitor(link) { t, _ -> if (t in 5_000 until 8_000) null else 10.0 }
            .run(MonitorConfig(durationMs = 20_000))
        assertEquals(DropCause.DISCONNECTED, report.dropouts.single().cause)
        val diagnosis = MonitorAnalyzer.diagnose(report)
        assertEquals(Severity.FAIL, diagnosis.severity)
        assertEquals("Wi-Fi disconnected during the session", diagnosis.verdict)
    }

    @Test
    fun `single lost samples are scattered loss, not drop-outs`() = runTest {
        // Every 10th sample lost: 10% scattered loss.
        val report = monitor { t, host -> if (host != gw && (t / 200) % 10 == 0L) null else 10.0 }
            .run(MonitorConfig(durationMs = 20_000))
        assertTrue(report.dropouts.isEmpty())
        assertEquals(10.0, report.scatteredLossPercent, 0.01)
        val f = MonitorAnalyzer.diagnose(report).findings.single { it.ruleId == "monitor_loss" }
        assertEquals(Severity.FAIL, f.severity)
    }

    @Test
    fun `latency spikes are localised to wifi or beyond the router`() = runTest {
        val spiky = { t: Long -> if ((t / 200) % 10 == 0L) 400.0 else 20.0 }
        val beyond = monitor { t, host -> if (host == gw) 2.0 else spiky(t) }.run(MonitorConfig(durationMs = 20_000))
        assertEquals("monitor_internet_latency", MonitorAnalyzer.diagnose(beyond).findings.first().ruleId)
        assertEquals(Layer.LOAD, MonitorAnalyzer.diagnose(beyond).layer)

        val wifi = monitor { t, host -> if (host == gw) spiky(t) - 15 else spiky(t) }.run(MonitorConfig(durationMs = 20_000))
        assertEquals("monitor_wifi_latency", MonitorAnalyzer.diagnose(wifi).findings.first().ruleId)
    }

    @Test
    fun `stopping early keeps what was measured`() = runTest {
        var stop = false
        val report = monitor { t, _ -> if (t >= 5_000) { stop = true }; 10.0 }
            .run(MonitorConfig(durationMs = 60_000), stop = { stop })
        assertTrue(report.stoppedEarly)
        assertTrue(report.durationMs in 5_000..5_400)
    }

    @Test
    fun `without a router address drop-outs cannot be localised`() = runTest {
        val report = monitor(link = { Fixtures.link(gateway = null) }) { t, _ -> if (t in 1_000 until 2_000) null else 10.0 }
            .run(MonitorConfig(durationMs = 5_000))
        assertNull(report.gateway)
        assertEquals(DropScope.UNKNOWN, report.dropouts.single().scope)
    }

    @Test
    fun `frequent roaming without drop-outs is flagged`() = runTest {
        val link = { t: Long -> Fixtures.link(bssid = if ((t / 30_000) % 2 == 0L) "aa" else "bb") }
        val report = monitor(link) { _, _ -> 10.0 }.run(MonitorConfig(durationMs = 300_000))
        assertEquals(9, report.roams.size)
        assertTrue(MonitorAnalyzer.diagnose(report).findings.any { it.ruleId == "monitor_ping_pong" })
    }
}
