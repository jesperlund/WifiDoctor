package com.example.wifidoctor.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wifidoctor.core.engine.EngineEvent
import com.example.wifidoctor.core.engine.LinkSource
import com.example.wifidoctor.core.engine.StepState
import com.example.wifidoctor.core.history.RunRecord
import com.example.wifidoctor.core.model.Diagnosis
import com.example.wifidoctor.core.model.EnvironmentStatus
import com.example.wifidoctor.core.model.Layer
import com.example.wifidoctor.core.model.LinkInfo
import com.example.wifidoctor.core.probe.ThroughputProgress
import com.example.wifidoctor.data.RunRepository
import com.example.wifidoctor.data.SettingsRepository
import com.example.wifidoctor.di.EngineFactory
import com.example.wifidoctor.platform.EnvironmentMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StepUi(val layer: Layer, val state: StepState = StepState.PENDING, val summary: String? = null)

data class DashboardUiState(
    val running: Boolean = false,
    val steps: List<StepUi> = Layer.entries.map { StepUi(it) },
    val throughput: ThroughputProgress? = null,
    val diagnosis: Diagnosis? = null,
    val savedRunId: Long? = null,
    val message: String? = null,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val engineFactory: EngineFactory,
    private val linkSource: LinkSource,
    private val environmentMonitor: EnvironmentMonitor,
    private val repository: RunRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    val environment: StateFlow<EnvironmentStatus> = environmentMonitor.status

    /** Live link stats, polled every 2 s while the dashboard is visible. */
    val liveLink: StateFlow<LinkInfo?> = flow {
        while (true) {
            emit(runCatching { linkSource.currentLink() }.getOrNull())
            delay(LIVE_POLL_MS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var job: Job? = null

    fun runDiagnosis() {
        if (_uiState.value.running) return
        val env = environmentMonitor.refresh()
        if (!env.canDiagnose) {
            _uiState.update { it.copy(message = env.issues.first { i -> i.blocking }.message) }
            return
        }
        _uiState.value = DashboardUiState(running = true)
        job = viewModelScope.launch {
            try {
                val settings = settingsRepository.settings.first()
                val engine = engineFactory.create(settings)
                engine.run(settings.toRunConfig(metered = env.wifiMetered)).collect { event -> onEvent(event) }
            } catch (e: CancellationException) {
                _uiState.update { s ->
                    s.copy(
                        running = false,
                        throughput = null,
                        steps = s.steps.map { if (it.state == StepState.RUNNING) it.copy(state = StepState.PENDING) else it },
                        message = "Diagnosis cancelled",
                    )
                }
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(running = false, message = "Diagnosis failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun messageShown() = _uiState.update { it.copy(message = null) }

    private suspend fun onEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.StepStarted -> updateStep(event.layer, StepState.RUNNING, null)
            is EngineEvent.StepFinished -> {
                updateStep(event.layer, event.state, event.summary)
                if (event.layer == Layer.THROUGHPUT) _uiState.update { it.copy(throughput = null) }
            }
            is EngineEvent.Throughput -> _uiState.update { it.copy(throughput = event.progress) }
            is EngineEvent.Finished -> {
                val id = repository.save(RunRecord.from(event.measurements, event.diagnosis))
                _uiState.update { it.copy(running = false, diagnosis = event.diagnosis, savedRunId = id, throughput = null) }
            }
        }
    }

    private fun updateStep(layer: Layer, state: StepState, summary: String?) = _uiState.update { s ->
        s.copy(steps = s.steps.map { if (it.layer == layer) StepUi(layer, state, summary) else it })
    }

    private companion object {
        const val LIVE_POLL_MS = 2_000L
    }
}
