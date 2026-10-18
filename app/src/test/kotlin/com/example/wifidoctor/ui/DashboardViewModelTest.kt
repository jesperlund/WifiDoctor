package com.example.wifidoctor.ui

import com.example.wifidoctor.core.engine.StepState
import com.example.wifidoctor.core.model.EnvironmentStatus
import com.example.wifidoctor.core.model.Layer
import com.example.wifidoctor.core.model.Severity
import com.example.wifidoctor.data.AppSettings
import com.example.wifidoctor.di.EngineFactory
import com.example.wifidoctor.ui.dashboard.DashboardViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class DashboardViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val repo = FakeRunRepository()
    private val settings = FakeSettingsRepository()
    private val env = FakeEnvironmentMonitor()

    private fun vm(factory: EngineFactory = fakeEngineFactory()) =
        DashboardViewModel(factory, { healthyLink }, env, repo, settings)

    @Test
    fun `initial state shows all layers pending`() {
        val s = vm().uiState.value
        assertFalse(s.running)
        assertEquals(Layer.entries.toList(), s.steps.map { it.layer })
        assertTrue(s.steps.all { it.state == StepState.PENDING })
        assertNull(s.diagnosis)
    }

    @Test
    fun `successful run updates every step, shows the diagnosis and saves it`() = runTest {
        val vm = vm()
        vm.runDiagnosis()
        assertTrue(vm.uiState.value.running)
        advanceUntilIdle()

        val s = vm.uiState.value
        assertFalse(s.running)
        assertEquals(Severity.OK, s.diagnosis!!.severity)
        assertTrue(s.steps.all { it.state == StepState.OK })
        assertEquals(1, repo.runs.value.size)
        assertEquals(s.savedRunId, repo.runs.value.single().id)
        assertEquals(-50, repo.runs.value.single().rssiDbm)
        assertNull(s.throughput)
    }

    @Test
    fun `problems are diagnosed and the failing step is marked`() = runTest {
        val vm = vm(fakeEngineFactory(prober = FakeProber(rtt = null)))
        vm.runDiagnosis()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(Severity.FAIL, s.diagnosis!!.severity)
        assertEquals(Layer.GATEWAY, s.diagnosis!!.layer)
        assertEquals(StepState.FAIL, s.steps.first { it.layer == Layer.INTERNET }.state)
    }

    @Test
    fun `blocked environment shows a message instead of running`() = runTest {
        env.state.value = EnvironmentStatus.Unknown.copy(wifiEnabled = false, wifiConnected = false)
        val vm = vm()
        vm.runDiagnosis()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.running)
        assertEquals("Turn on Wi-Fi to diagnose your network.", vm.uiState.value.message)
        assertTrue(repo.runs.value.isEmpty())
        vm.messageShown()
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun `speed test is skipped on metered wifi unless allowed`() = runTest {
        env.state.value = EnvironmentStatus.Unknown.copy(wifiMetered = true)
        val throughput = FakeThroughput()
        val vm = vm(fakeEngineFactory(throughput = throughput))
        vm.runDiagnosis()
        advanceUntilIdle()
        assertEquals(0, throughput.calls)
        assertEquals(StepState.SKIPPED, vm.uiState.value.steps.first { it.layer == Layer.THROUGHPUT }.state)

        settings.state.value = AppSettings(speedTestOnMetered = true)
        vm.runDiagnosis()
        advanceUntilIdle()
        assertEquals(1, throughput.calls)
    }

    @Test
    fun `cancel stops the run and resets the running step`() = runTest {
        val vm = vm(fakeEngineFactory(prober = FakeProber(delayMs = 5_000)))
        vm.runDiagnosis()
        runCurrent()
        advanceTimeBy(1_000)
        assertEquals(StepState.RUNNING, vm.uiState.value.steps.first { it.layer == Layer.GATEWAY }.state)
        vm.cancel()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertFalse(s.running)
        assertEquals("Diagnosis cancelled", s.message)
        assertEquals(StepState.PENDING, s.steps.first { it.layer == Layer.GATEWAY }.state)
        assertTrue(repo.runs.value.isEmpty())
    }

    @Test
    fun `a second tap while running is ignored`() = runTest {
        val throughput = FakeThroughput()
        val vm = vm(fakeEngineFactory(throughput = throughput))
        vm.runDiagnosis()
        vm.runDiagnosis()
        advanceUntilIdle()
        assertEquals(1, throughput.calls)
        assertEquals(1, repo.runs.value.size)
    }

    @Test
    fun `unexpected errors are reported`() = runTest {
        val vm = vm(fakeEngineFactory(throughput = FakeThroughput(error = IllegalStateException("boom"))))
        vm.runDiagnosis()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.running)
        assertNotNull(vm.uiState.value.message)
        assertTrue(vm.uiState.value.message!!.contains("boom"))
    }
}
