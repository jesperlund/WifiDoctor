package io.github.jesperlund.wifidoctor.core.engine

import io.github.jesperlund.wifidoctor.core.Fixtures
import io.github.jesperlund.wifidoctor.core.Fixtures.scanEntry
import io.github.jesperlund.wifidoctor.core.model.Layer
import io.github.jesperlund.wifidoctor.core.model.NatBehavior
import io.github.jesperlund.wifidoctor.core.model.ProbeMethod
import io.github.jesperlund.wifidoctor.core.model.RealtimeResult
import io.github.jesperlund.wifidoctor.core.model.StunServer
import io.github.jesperlund.wifidoctor.core.model.UdpProbeResult
import io.github.jesperlund.wifidoctor.core.probe.RealtimeTester
import io.github.jesperlund.wifidoctor.core.probe.RttProbe
import io.github.jesperlund.wifidoctor.core.model.ProbeStats
import io.github.jesperlund.wifidoctor.core.model.ScanSnapshot
import io.github.jesperlund.wifidoctor.core.model.Severity
import io.github.jesperlund.wifidoctor.core.model.ThroughputResult
import io.github.jesperlund.wifidoctor.core.probe.DnsTimer
import io.github.jesperlund.wifidoctor.core.probe.LatencyProber
import io.github.jesperlund.wifidoctor.core.probe.ProbeMath
import io.github.jesperlund.wifidoctor.core.probe.Resolver
import io.github.jesperlund.wifidoctor.core.probe.ThroughputProgress
import io.github.jesperlund.wifidoctor.core.probe.ThroughputTester
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeProber(private val byHost: Map<String, List<Double?>>) : LatencyProber {
    val probed = mutableListOf<String>()
    override suspend fun probe(host: String, count: Int, timeoutMs: Int): ProbeStats {
        probed += host
        return ProbeMath.stats(host, byHost[host] ?: List(count) { 5.0 })
    }
}

class FakeDns(private val system: Double?, private val reference: Double?) : DnsTimer {
    val resolvers = mutableListOf<Resolver>()
    override suspend fun time(resolver: Resolver, domains: List<String>, timeoutMs: Int): List<Double?> {
        resolvers += resolver
        val v = if (resolver == Resolver.Udp("1.1.1.1")) reference else system
        return domains.map { v }
    }
}

class FakeThroughput(
    private val mbps: Double?,
    private val uploadMbps: Double? = 50.0,
    /** Virtual time each transfer takes, so latency can be sampled under load. */
    private val transferMs: Long = 0,
) : ThroughputTester {
    var calls = 0
    var uploads = 0
    override suspend fun run(url: String, maxDurationMs: Long, onProgress: (ThroughputProgress) -> Unit): ThroughputResult {
        calls++
        onProgress(ThroughputProgress(1, 1, mbps, 0.5f))
        if (transferMs > 0) kotlinx.coroutines.delay(transferMs)
        return ThroughputResult(url, 1_000_000, 1_000, mbps)
    }

    override suspend fun upload(url: String, maxDurationMs: Long, onProgress: (ThroughputProgress) -> Unit): ThroughputResult {
        uploads++
        onProgress(ThroughputProgress(1, 1, uploadMbps, 0.5f, upload = true))
        if (transferMs > 0) kotlinx.coroutines.delay(transferMs)
        return ThroughputResult(url, 1_000_000, 1_000, uploadMbps)
    }
}

class FakeRealtime(private val result: RealtimeResult) : RealtimeTester {
    var calls = 0
    override suspend fun test(servers: List<StunServer>, samples: Int, intervalMs: Long, timeoutMs: Int): RealtimeResult {
        calls++
        return result
    }

    companion object {
        fun good() = FakeRealtime(
            RealtimeResult(
                listOf(UdpProbeResult(StunServer.DEFAULTS[0], ProbeMath.stats("cf", List(15) { 20.0 }, ProbeMethod.UDP), "203.0.113.5:4000")),
                NatBehavior.ENDPOINT_INDEPENDENT,
            ),
        )
    }
}

class DiagnosticEngineTest {

    private fun engine(
        link: io.github.jesperlund.wifidoctor.core.model.LinkInfo? = Fixtures.link(width = null),
        prober: LatencyProber = FakeProber(emptyMap()),
        dns: DnsTimer = FakeDns(20.0, 18.0),
        throughput: ThroughputTester = FakeThroughput(200.0),
        scan: ScanSnapshot? = ScanSnapshot(
            listOf(scanEntry(ssid = Fixtures.SSID, bssid = Fixtures.BSSID_5, freq = 5180, rssi = -50, width = 80)),
            throttled = false,
        ),
        realtime: RealtimeTester? = FakeRealtime.good(),
        rttProbe: RttProbe? = null,
    ) = if (rttProbe == null) {
        DiagnosticEngine({ link }, { scan }, prober, dns, throughput, clock = { 42L }, realtimeTester = realtime)
    } else {
        DiagnosticEngine({ link }, { scan }, prober, dns, throughput, clock = { 42L }, realtimeTester = realtime, rttProbe = rttProbe)
    }

    @Test
    fun `runs every layer in order and finishes healthy`() = runTest {
        val events = engine().run().toList()
        val started = events.filterIsInstance<EngineEvent.StepStarted>().map { it.layer }
        assertEquals(Layer.entries.toList(), started)
        val finished = events.filterIsInstance<EngineEvent.StepFinished>()
        assertEquals(Layer.entries.toList(), finished.map { it.layer })
        assertTrue(finished.all { it.state == StepState.OK })
        val done = events.last() as EngineEvent.Finished
        assertEquals(Severity.OK, done.diagnosis.severity)
        assertEquals(42L, done.measurements.timestampMs)
        assertTrue(events.any { it is EngineEvent.Throughput })
    }

    @Test
    fun `latency under load is compared with idle latency`() = runTest {
        // Idle probes answer in 5 ms (FakeProber); under load the line queues: 300 ms.
        val events = engine(throughput = FakeThroughput(100.0, transferMs = 3_000), rttProbe = RttProbe { _, _ -> 300.0 }).run().toList()
        val done = events.last() as EngineEvent.Finished
        val loaded = done.measurements.loaded!!
        assertEquals(295.0, loaded.downloadIncreaseMs!!, 1e-9)
        assertEquals(295.0, loaded.uploadIncreaseMs!!, 1e-9)
        assertTrue(loaded.download!!.sent >= 8)
        val load = events.filterIsInstance<EngineEvent.StepFinished>().first { it.layer == Layer.LOAD }
        assertEquals(StepState.FAIL, load.state)
        assertTrue(load.summary.contains("+295 ms downloading"))
        assertTrue(done.diagnosis.findings.any { it.ruleId == "bufferbloat" })
    }

    @Test
    fun `upload is measured after the download`() = runTest {
        val t = FakeThroughput(100.0, uploadMbps = 1.0)
        val events = engine(throughput = t).run().toList()
        assertEquals(1, t.uploads)
        val step = events.filterIsInstance<EngineEvent.StepFinished>().first { it.layer == Layer.THROUGHPUT }
        assertEquals("↓ 100.0 Mbps · ↑ 1.0 Mbps", step.summary)
        assertTrue((events.last() as EngineEvent.Finished).diagnosis.findings.any { it.ruleId == "slow_upload" })
        assertTrue(events.any { it is EngineEvent.Throughput && it.progress.upload })
    }

    @Test
    fun `blocked UDP fails the call layer`() = runTest {
        val blocked = FakeRealtime(
            RealtimeResult(
                StunServer.DEFAULTS.map { UdpProbeResult(it, ProbeMath.stats(it.label, List(15) { null }, ProbeMethod.UDP), null) },
                NatBehavior.UNKNOWN,
            ),
        )
        val events = engine(realtime = blocked).run().toList()
        val step = events.filterIsInstance<EngineEvent.StepFinished>().first { it.layer == Layer.REALTIME }
        assertEquals(StepState.FAIL, step.state)
        assertEquals("No UDP replies", step.summary)
        assertEquals("udp_blocked", (events.last() as EngineEvent.Finished).diagnosis.findings.first().ruleId)
    }

    @Test
    fun `call layer is skipped without a tester or when turned off`() = runTest {
        val r = FakeRealtime.good()
        val off = engine(realtime = r).run(RunConfig(runRealtime = false)).toList()
        assertEquals(0, r.calls)
        assertEquals(StepState.SKIPPED, off.filterIsInstance<EngineEvent.StepFinished>().first { it.layer == Layer.REALTIME }.state)
        val none = engine(realtime = null).run().toList()
        assertEquals(StepState.SKIPPED, none.filterIsInstance<EngineEvent.StepFinished>().first { it.layer == Layer.REALTIME }.state)
    }

    @Test
    fun `not connected skips everything after the link`() = runTest {
        val prober = FakeProber(emptyMap())
        val events = engine(link = null, prober = prober).run().toList()
        val finished = events.filterIsInstance<EngineEvent.StepFinished>()
        assertEquals(StepState.FAIL, finished.first().state)
        assertTrue(finished.drop(1).all { it.state == StepState.SKIPPED })
        assertTrue(prober.probed.isEmpty())
        assertEquals("not_connected", (events.last() as EngineEvent.Finished).diagnosis.findings.single().ruleId)
    }

    @Test
    fun `probes the gateway and every internet target`() = runTest {
        val prober = FakeProber(emptyMap())
        engine(prober = prober).run().toList()
        assertEquals(listOf("192.168.1.1", "1.1.1.1", "8.8.8.8"), prober.probed)
    }

    @Test
    fun `unknown gateway is skipped`() = runTest {
        val events = engine(link = Fixtures.link(gateway = null)).run().toList()
        val gw = events.filterIsInstance<EngineEvent.StepFinished>().first { it.layer == Layer.GATEWAY }
        assertEquals(StepState.SKIPPED, gw.state)
    }

    @Test
    fun `internet failure is reported on its layer and diagnosed as ISP`() = runTest {
        val prober = FakeProber(mapOf("1.1.1.1" to List(10) { null }, "8.8.8.8" to List(10) { null }))
        val events = engine(prober = prober).run().toList()
        val inet = events.filterIsInstance<EngineEvent.StepFinished>().first { it.layer == Layer.INTERNET }
        assertEquals(StepState.FAIL, inet.state)
        val d = (events.last() as EngineEvent.Finished).diagnosis
        assertEquals(Layer.INTERNET, d.layer)
    }

    @Test
    fun `internet step is red even when the router is blamed`() = runTest {
        val prober = FakeProber(mapOf("192.168.1.1" to List(10) { null }, "1.1.1.1" to List(10) { null }, "8.8.8.8" to List(10) { null }))
        val events = engine(prober = prober).run().toList()
        val states = events.filterIsInstance<EngineEvent.StepFinished>().associate { it.layer to it.state }
        assertEquals(StepState.FAIL, states[Layer.GATEWAY])
        assertEquals(StepState.FAIL, states[Layer.INTERNET])
        assertEquals(Layer.GATEWAY, (events.last() as EngineEvent.Finished).diagnosis.layer)
    }

    @Test
    fun `dns uses the network's server versus the reference`() = runTest {
        val dns = FakeDns(300.0, 20.0)
        val events = engine(dns = dns).run().toList()
        assertEquals(listOf(Resolver.Udp("192.168.1.1"), Resolver.Udp("1.1.1.1")), dns.resolvers)
        assertEquals(Layer.DNS, (events.last() as EngineEvent.Finished).diagnosis.layer)
    }

    @Test
    fun `falls back to the system resolver when the network's DNS is unknown`() = runTest {
        val dns = FakeDns(20.0, 20.0)
        engine(link = Fixtures.link(dns = emptyList()), dns = dns).run().toList()
        assertEquals(Resolver.System, dns.resolvers.first())
    }

    @Test
    fun `throughput can be turned off`() = runTest {
        val t = FakeThroughput(100.0)
        val events = engine(throughput = t).run(RunConfig(runThroughput = false)).toList()
        assertEquals(0, t.calls)
        val step = events.filterIsInstance<EngineEvent.StepFinished>().first { it.layer == Layer.THROUGHPUT }
        assertEquals(StepState.SKIPPED, step.state)
        assertEquals(StepState.SKIPPED, events.filterIsInstance<EngineEvent.StepFinished>().first { it.layer == Layer.LOAD }.state)
        assertNull((events.last() as EngineEvent.Finished).measurements.throughput)
        assertEquals(0, t.uploads)
    }

    @Test
    fun `unavailable scan skips congestion`() = runTest {
        val events = engine(scan = null).run().toList()
        val step = events.filterIsInstance<EngineEvent.StepFinished>().first { it.layer == Layer.CONGESTION }
        assertEquals(StepState.SKIPPED, step.state)
    }

    @Test
    fun `scan fills in channel width and congestion`() = runTest {
        val done = engine().run().toList().last() as EngineEvent.Finished
        assertEquals(80, done.measurements.link!!.channelWidthMhz)
        assertEquals(0, done.measurements.congestion!!.sameChannel)
    }

    @Test
    fun `throttled scan is labelled as cached`() = runTest {
        val events = engine(scan = ScanSnapshot(emptyList(), throttled = true)).run().toList()
        val step = events.filterIsInstance<EngineEvent.StepFinished>().first { it.layer == Layer.CONGESTION }
        assertTrue(step.summary.contains("cached"))
    }

    @Test
    fun `can be cancelled by the collector`() = runTest {
        val first = engine().run().first()
        assertEquals(EngineEvent.StepStarted(Layer.LINK), first)
    }
}
