package io.github.jesperlund.wifidoctor.ui

import io.github.jesperlund.wifidoctor.core.engine.WalkTester
import io.github.jesperlund.wifidoctor.core.history.RunRecord
import io.github.jesperlund.wifidoctor.core.model.EnvironmentStatus
import io.github.jesperlund.wifidoctor.core.model.Severity
import io.github.jesperlund.wifidoctor.data.AppSettings
import io.github.jesperlund.wifidoctor.ui.history.HistoryViewModel
import io.github.jesperlund.wifidoctor.ui.history.Metric
import io.github.jesperlund.wifidoctor.ui.rooms.RoomsViewModel
import io.github.jesperlund.wifidoctor.ui.rooms.WalkPhase
import io.github.jesperlund.wifidoctor.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val repo = FakeRunRepository()
    private val env = FakeEnvironmentMonitor()
    private var rssi = -60
    private val vm by lazy { RoomsViewModel(WalkTester({ healthyLink.copy(rssiDbm = rssi) }, FakeProber()), repo, env) }

    @Test
    fun `room name is required`() = runTest {
        vm.startWalk()
        assertEquals("Enter a room name first", vm.uiState.value.nameError)
        assertEquals(WalkPhase.Idle, vm.uiState.value.phase)
        vm.onRoomNameChange("Kitchen")
        assertNull(vm.uiState.value.nameError)
    }

    @Test
    fun `walk test measures for ten seconds, saves the room and ranks it`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.rooms.collect() }
        vm.onRoomNameChange("Kitchen")
        vm.startWalk()
        runCurrent()
        advanceTimeBy(4_500)
        val measuring = vm.uiState.value.phase as WalkPhase.Measuring
        assertEquals("Kitchen", measuring.room)
        assertEquals(5, measuring.secondsLeft)
        advanceUntilIdle()

        val done = vm.uiState.value.phase as WalkPhase.Done
        assertEquals("Kitchen", done.room)
        assertEquals("", vm.uiState.value.roomName)
        assertEquals("Kitchen", repo.runs.value.single().roomLabel)

        rssi = -85
        vm.onRoomNameChange("Garage")
        vm.startWalk()
        advanceUntilIdle()
        assertEquals(listOf("Kitchen", "Garage"), vm.rooms.value.map { it.roomName })
        assertTrue(vm.rooms.value.last().isDeadZone)
    }

    @Test
    fun `cancelling a walk test saves nothing`() = runTest {
        vm.onRoomNameChange("Hall")
        vm.startWalk()
        runCurrent()
        advanceTimeBy(3_000)
        vm.cancel()
        advanceUntilIdle()
        assertEquals(WalkPhase.Idle, vm.uiState.value.phase)
        assertEquals("Walk test cancelled", vm.uiState.value.message)
        assertTrue(repo.runs.value.isEmpty())
    }

    @Test
    fun `walk test is blocked when not connected`() = runTest {
        env.state.value = EnvironmentStatus.Unknown.copy(wifiConnected = false)
        vm.onRoomNameChange("Hall")
        vm.startWalk()
        assertEquals(WalkPhase.Idle, vm.uiState.value.phase)
        assertNotNull(vm.uiState.value.message)
    }

    @Test
    fun `rooms can be deleted`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.rooms.collect() }
        repo.save(RunRecord(timestampMs = 1, roomLabel = "Attic", rssiDbm = -70, severity = Severity.OK, verdict = "", explanation = ""))
        advanceUntilIdle()
        assertEquals(1, vm.rooms.value.size)
        vm.deleteRoom("attic")
        advanceUntilIdle()
        assertTrue(vm.rooms.value.isEmpty())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val repo = FakeRunRepository()

    private fun record(t: Long, latency: Double, loss: Double = 0.0, rssi: Int = -55) = RunRecord(
        timestampMs = t, rssiDbm = rssi, internetAvgMs = latency, internetLossPercent = loss, gatewayLossPercent = 0.0,
        severity = if (loss > 0) Severity.WARN else Severity.OK, verdict = "v$t", explanation = "e$t", fixes = listOf("fix"),
    )

    @Test
    fun `exposes chart series, drops and the selected run`() = runTest {
        listOf(record(1, 20.0), record(2, 22.0), record(3, 21.0, loss = 30.0), record(4, 300.0), record(5, 20.0))
            .forEach { repo.save(it) }
        val vm = HistoryViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        advanceUntilIdle()

        val s = vm.uiState.value
        assertEquals(5, s.runs.size)
        assertEquals(listOf(20.0, 22.0, 21.0, 300.0, 20.0), s.values(Metric.LATENCY))
        assertEquals(listOf(0.0, 0.0, 30.0, 0.0, 0.0), s.values(Metric.LOSS))
        assertEquals(setOf(3L, 4L), s.drops.keys)
        assertNull(s.selected)

        vm.select(3)
        advanceUntilIdle()
        assertEquals("v3", vm.uiState.value.selected!!.verdict)
        vm.select(3) // tapping the same point again deselects
        advanceUntilIdle()
        assertNull(vm.uiState.value.selected)
    }

    @Test
    fun `selection disappears when history is cleared`() = runTest {
        repo.save(record(1, 20.0))
        val vm = HistoryViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        vm.select(1)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.selected)
        repo.clear()
        advanceUntilIdle()
        assertNull(vm.uiState.value.selected)
        assertTrue(vm.uiState.value.runs.isEmpty())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val settings = FakeSettingsRepository()
    private val scheduler = FakeScheduler()
    private val repo = FakeRunRepository()
    private val writer = FakeWriter()
    private val vm by lazy { SettingsViewModel(settings, scheduler, repo, writer) }

    @Test
    fun `enabling the schedule enqueues work with the chosen interval`() = runTest {
        vm.setInterval(60)
        advanceUntilIdle()
        assertTrue("not scheduled while disabled", scheduler.calls.isEmpty())
        vm.setScheduleEnabled(true)
        advanceUntilIdle()
        assertEquals(listOf(true to 60L), scheduler.calls)
        vm.setInterval(1440)
        advanceUntilIdle()
        assertEquals(true to 1440L, scheduler.calls.last())
        vm.setScheduleEnabled(false)
        advanceUntilIdle()
        assertEquals(false to 1440L, scheduler.calls.last())
    }

    @Test
    fun `interval is clamped to the WorkManager minimum`() = runTest {
        vm.setInterval(5)
        advanceUntilIdle()
        assertEquals(15L, settings.state.value.intervalMinutes)
    }

    @Test
    fun `invalid url is rejected with an error`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        assertFalse(vm.setTestServerUrl("speed.example.com/file"))
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.urlError)
        assertEquals(AppSettings().testServerUrl, settings.state.value.testServerUrl)

        assertTrue(vm.setTestServerUrl(" https://speed.example.com/100MB.bin "))
        advanceUntilIdle()
        assertNull(vm.uiState.value.urlError)
        assertEquals("https://speed.example.com/100MB.bin", settings.state.value.testServerUrl)
    }

    @Test
    fun `probe targets are parsed and validated`() = runTest {
        assertFalse(vm.setProbeTargets("1.1.1.1, not a host!"))
        assertFalse(vm.setProbeTargets(""))
        assertTrue(vm.setProbeTargets("9.9.9.9, one.one.one.one"))
        advanceUntilIdle()
        assertEquals(listOf("9.9.9.9", "one.one.one.one"), settings.state.value.probeTargets)
    }

    @Test
    fun `country override is validated and normalised`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        assertFalse(vm.setCountryOverride("Germany"))
        assertFalse(vm.setCountryOverride("D1"))
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.countryError)
        assertTrue(vm.setCountryOverride(" de "))
        advanceUntilIdle()
        assertEquals("DE", settings.state.value.countryOverride)
        assertNull(vm.uiState.value.countryError)
        assertTrue(vm.setCountryOverride(""))
        advanceUntilIdle()
        assertEquals("", settings.state.value.countryOverride)
    }

    @Test
    fun `upload url and udp test settings`() = runTest {
        assertFalse(vm.setUploadUrl("not a url"))
        assertTrue(vm.setUploadUrl("https://up.example.com/x"))
        vm.setUdpTestEnabled(false)
        vm.setServicesTestEnabled(false)
        advanceUntilIdle()
        assertFalse(settings.state.value.servicesTestEnabled)
        assertEquals("https://up.example.com/x", settings.state.value.uploadUrl)
        assertFalse(settings.state.value.udpTestEnabled)
    }

    @Test
    fun `toggles are persisted`() = runTest {
        vm.setNotifyOnlyOnProblems(false)
        vm.setUsePingFallback(false)
        vm.setSpeedTestOnMetered(true)
        vm.setSpeedTestInBackground(true)
        advanceUntilIdle()
        val s = settings.state.value
        assertFalse(s.notifyOnlyOnProblems)
        assertFalse(s.usePingFallback)
        assertTrue(s.speedTestOnMetered)
        assertTrue(s.speedTestInBackground)
    }

    @Test
    fun `reset keeps the schedule but restores test defaults`() = runTest {
        settings.state.value = AppSettings(scheduleEnabled = true, intervalMinutes = 60, probeTargets = listOf("9.9.9.9"))
        vm.resetDefaults()
        advanceUntilIdle()
        assertEquals(AppSettings(scheduleEnabled = true, intervalMinutes = 60), settings.state.value)
    }

    @Test
    fun `export writes csv and clear empties history`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        repo.save(RunRecord(timestampMs = 5, rssiDbm = -60, severity = Severity.OK, verdict = "fine", explanation = ""))
        vm.exportCsv("content://export.csv")
        advanceUntilIdle()
        val csv = writer.written.getValue("content://export.csv")
        assertTrue(csv.startsWith("id,timestamp_ms"))
        assertEquals(2, csv.trim().lines().size)
        assertEquals("Exported 1 runs", vm.uiState.value.message)

        vm.clearHistory()
        advanceUntilIdle()
        assertTrue(repo.runs.value.isEmpty())
        assertEquals("History cleared", vm.uiState.value.message)
    }

    @Test
    fun `export failures are reported`() = runTest {
        val failing = SettingsViewModel(settings, scheduler, repo, FakeWriter(fail = true))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { failing.uiState.collect() }
        failing.exportCsv("content://x")
        advanceUntilIdle()
        assertEquals("Export failed: disk full", failing.uiState.value.message)
    }
}
