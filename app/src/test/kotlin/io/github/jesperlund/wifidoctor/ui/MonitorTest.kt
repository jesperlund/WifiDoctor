package io.github.jesperlund.wifidoctor.ui

import io.github.jesperlund.wifidoctor.core.history.RunKind
import io.github.jesperlund.wifidoctor.core.model.EnvironmentStatus
import io.github.jesperlund.wifidoctor.core.model.Severity
import io.github.jesperlund.wifidoctor.core.probe.RttProbe
import io.github.jesperlund.wifidoctor.data.AppSettings
import io.github.jesperlund.wifidoctor.di.Clock
import io.github.jesperlund.wifidoctor.monitor.CallMonitorController
import io.github.jesperlund.wifidoctor.monitor.ForegroundKeeper
import io.github.jesperlund.wifidoctor.monitor.MonitorState
import io.github.jesperlund.wifidoctor.ui.monitor.MonitorViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FakeKeeper : ForegroundKeeper {
    var starts = 0
    var stops = 0
    override fun start() { starts++ }
    override fun stop() { stops++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MonitorTest {
    @get:Rule val main = MainDispatcherRule()

    private val repo = FakeRunRepository()
    private val settings = FakeSettingsRepository(AppSettings(probeTargets = listOf("9.9.9.9")))
    private val keeper = FakeKeeper()
    private val probed = mutableListOf<String>()

    private fun TestScope.controller(rtt: (Long, String) -> Double? = { _, _ -> 15.0 }) = CallMonitorController(
        linkSource = { healthyLink },
        probe = RttProbe { host, _ -> probed += host; rtt(currentTime, host) },
        repository = repo,
        settings = settings,
        foreground = keeper,
        scope = backgroundScope,
        clock = Clock { currentTime },
    )

    @Test
    fun `session runs in the background, reports progress and saves the result`() = runTest {
        val c = controller()
        c.start(60_000)
        runCurrent()
        assertEquals(1, keeper.starts)
        advanceTimeBy(10_500)
        val running = c.state.value as MonitorState.Running
        assertEquals(10_000, running.progress!!.elapsedMs)
        assertEquals(0, running.progress!!.dropouts)
        advanceTimeBy(60_000)
        val done = c.state.value as MonitorState.Finished
        assertEquals(Severity.OK, done.diagnosis.severity)
        assertEquals(1, keeper.stops)
        val saved = repo.runs.value.single()
        assertEquals(RunKind.CALL_MONITOR, saved.kind)
        assertEquals(done.savedRunId, saved.id)
        assertEquals(60_000L, saved.monitorDurationMs)
        assertTrue("uses the first probe target", "9.9.9.9" in probed)
        assertTrue("and the router", "192.168.1.1" in probed)
    }

    @Test
    fun `stopping early analyses what was measured`() = runTest {
        val c = controller { t, host -> if (host == "9.9.9.9" && t in 3_000 until 5_000) null else 15.0 }
        c.start(15 * 60_000)
        advanceTimeBy(8_000)
        c.stop()
        advanceTimeBy(1_000)
        val done = c.state.value as MonitorState.Finished
        assertTrue(done.report.stoppedEarly)
        assertEquals(1, done.report.dropouts.size)
        assertEquals("monitor_internet_dropout", done.diagnosis.findings.first().ruleId)
        assertEquals(1, repo.runs.value.size)
    }

    @Test
    fun `second start while running is ignored and reset returns to idle`() = runTest {
        val c = controller()
        c.start(5_000)
        c.start(5_000)
        assertEquals(1, keeper.starts)
        c.reset() // ignored while running
        assertTrue(c.state.value is MonitorState.Running)
        advanceTimeBy(6_000)
        assertTrue(c.state.value is MonitorState.Finished)
        c.reset()
        assertEquals(MonitorState.Idle, c.state.value)
    }

    @Test
    fun `view model blocks starting without wifi and passes the chosen duration`() = runTest {
        val env = FakeEnvironmentMonitor(EnvironmentStatus.Unknown.copy(wifiConnected = false))
        val c = controller()
        val vm = MonitorViewModel(c, env)
        vm.start()
        assertEquals(MonitorState.Idle, c.state.value)
        assertNotNull(vm.options.value.message)

        env.state.value = EnvironmentStatus.Unknown
        vm.setDuration(2)
        vm.start()
        assertEquals(120_000L, (c.state.value as MonitorState.Running).durationMs)
        vm.stop()
        advanceTimeBy(1_000) // background work does not run under advanceUntilIdle()
        assertTrue(c.state.value is MonitorState.Finished)
    }
}
