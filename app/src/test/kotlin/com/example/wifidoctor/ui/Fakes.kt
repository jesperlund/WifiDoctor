package com.example.wifidoctor.ui

import com.example.wifidoctor.core.engine.DiagnosticEngine
import com.example.wifidoctor.core.engine.LinkSource
import com.example.wifidoctor.core.history.RunRecord
import com.example.wifidoctor.core.model.EnvironmentStatus
import com.example.wifidoctor.core.model.LinkInfo
import com.example.wifidoctor.core.model.ProbeStats
import com.example.wifidoctor.core.model.ScanEntry
import com.example.wifidoctor.core.model.ScanSnapshot
import com.example.wifidoctor.core.model.ThroughputResult
import com.example.wifidoctor.core.model.WifiStandard
import com.example.wifidoctor.core.probe.DnsTimer
import com.example.wifidoctor.core.probe.LatencyProber
import com.example.wifidoctor.core.probe.ProbeMath
import com.example.wifidoctor.core.probe.Resolver
import com.example.wifidoctor.core.probe.ThroughputProgress
import com.example.wifidoctor.core.probe.ThroughputTester
import com.example.wifidoctor.data.AppSettings
import com.example.wifidoctor.data.RunRepository
import com.example.wifidoctor.data.SettingsRepository
import com.example.wifidoctor.di.EngineFactory
import com.example.wifidoctor.platform.DocumentWriter
import com.example.wifidoctor.platform.EnvironmentMonitor
import com.example.wifidoctor.work.CheckScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

val healthyLink = LinkInfo(
    ssid = "HomeNet", bssid = "aa:bb:cc:dd:ee:ff", rssiDbm = -50, linkSpeedMbps = 866, frequencyMhz = 5180,
    channelWidthMhz = 80, standard = WifiStandard.AC, gatewayIp = "192.168.1.1", dnsServers = listOf("192.168.1.1"),
)

class FakeRunRepository : RunRepository {
    val runs = MutableStateFlow<List<RunRecord>>(emptyList())
    private var nextId = 1L

    override fun observeRuns(): Flow<List<RunRecord>> = runs
    override fun observeRoomRuns(): Flow<List<RunRecord>> = runs.map { list -> list.filter { it.roomLabel != null } }
    override suspend fun save(record: RunRecord): Long {
        val id = nextId++
        runs.value = runs.value + record.copy(id = id)
        return id
    }
    override suspend fun get(id: Long) = runs.value.firstOrNull { it.id == id }
    override suspend fun all() = runs.value
    override suspend fun deleteRoom(room: String) {
        runs.value = runs.value.filterNot { it.roomLabel.equals(room, ignoreCase = true) }
    }
    override suspend fun clear() { runs.value = emptyList() }
}

class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun update(transform: (AppSettings) -> AppSettings) { state.value = transform(state.value) }
}

class FakeEnvironmentMonitor(initial: EnvironmentStatus = EnvironmentStatus.Unknown) : EnvironmentMonitor {
    val state = MutableStateFlow(initial)
    override val status: StateFlow<EnvironmentStatus> = state
    override fun refresh(): EnvironmentStatus = state.value
}

class FakeScheduler : CheckScheduler {
    val calls = mutableListOf<Pair<Boolean, Long>>()
    override fun apply(enabled: Boolean, intervalMinutes: Long) { calls += enabled to intervalMinutes }
}

class FakeWriter(private val fail: Boolean = false) : DocumentWriter {
    val written = mutableMapOf<String, String>()
    override suspend fun write(uri: String, content: String) {
        if (fail) error("disk full")
        written[uri] = content
    }
}

/** Probe that takes [delayMs] of virtual time per call, so cancellation can be tested. */
class FakeProber(private val delayMs: Long = 0, private val rtt: Double? = 5.0) : LatencyProber {
    override suspend fun probe(host: String, count: Int, timeoutMs: Int): ProbeStats {
        if (delayMs > 0) delay(delayMs)
        return ProbeMath.stats(host, List(count) { rtt })
    }
}

class FakeDnsTimer(private val ms: Double? = 20.0) : DnsTimer {
    override suspend fun time(resolver: Resolver, domains: List<String>, timeoutMs: Int) = domains.map { ms }
}

class FakeThroughput(private val mbps: Double? = 150.0, private val error: Exception? = null) : ThroughputTester {
    var calls = 0
    override suspend fun run(url: String, maxDurationMs: Long, onProgress: (ThroughputProgress) -> Unit): ThroughputResult {
        calls++
        error?.let { throw it }
        onProgress(ThroughputProgress(1_000, 100, mbps, 0.5f))
        return ThroughputResult(url, 10_000_000, 1_000, mbps)
    }
}

fun fakeEngineFactory(
    link: LinkInfo? = healthyLink,
    prober: LatencyProber = FakeProber(),
    throughput: ThroughputTester = FakeThroughput(),
    linkSource: LinkSource = LinkSource { link },
    scan: List<ScanEntry> = emptyList(),
): EngineFactory = EngineFactory {
    DiagnosticEngine(
        linkSource = linkSource,
        scanSource = { ScanSnapshot(scan, throttled = false) },
        prober = prober,
        dnsTimer = FakeDnsTimer(),
        throughputTester = throughput,
        clock = { 1_000L },
    )
}
