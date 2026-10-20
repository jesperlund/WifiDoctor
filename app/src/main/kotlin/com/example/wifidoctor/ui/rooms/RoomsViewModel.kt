package com.example.wifidoctor.ui.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wifidoctor.core.engine.WalkEvent
import com.example.wifidoctor.core.engine.WalkTester
import com.example.wifidoctor.core.history.RoomRanking
import com.example.wifidoctor.core.history.RoomSummary
import com.example.wifidoctor.core.history.RunRecord
import com.example.wifidoctor.core.model.Diagnosis
import com.example.wifidoctor.data.RunRepository
import com.example.wifidoctor.platform.EnvironmentMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface WalkPhase {
    data object Idle : WalkPhase
    data class Measuring(val room: String, val elapsedMs: Long, val totalMs: Long, val latestRssi: Int?) : WalkPhase {
        val fraction: Float get() = if (totalMs == 0L) 0f else (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f)
        val secondsLeft: Long get() = ((totalMs - elapsedMs).coerceAtLeast(0) + 999) / 1000
    }
    data class Done(val room: String, val diagnosis: Diagnosis) : WalkPhase
}

data class RoomsUiState(
    val roomName: String = "",
    val phase: WalkPhase = WalkPhase.Idle,
    val nameError: String? = null,
    val message: String? = null,
)

@HiltViewModel
class RoomsViewModel @Inject constructor(
    private val walkTester: WalkTester,
    private val repository: RunRepository,
    private val environmentMonitor: EnvironmentMonitor,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoomsUiState())
    val uiState: StateFlow<RoomsUiState> = _uiState.asStateFlow()

    /** Latest walk test per room, best first. */
    val rooms: StateFlow<List<RoomSummary>> = repository.observeRoomRuns()
        .map { RoomRanking.rank(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var job: Job? = null

    fun onRoomNameChange(name: String) = _uiState.update { it.copy(roomName = name.take(MAX_NAME), nameError = null) }

    fun startWalk(durationMs: Long = WALK_MS) {
        if (_uiState.value.phase is WalkPhase.Measuring) return
        val name = _uiState.value.roomName.trim()
        if (name.isEmpty()) {
            _uiState.update { it.copy(nameError = "Enter a room name first") }
            return
        }
        val env = environmentMonitor.refresh()
        if (!env.canDiagnose) {
            _uiState.update { it.copy(message = env.issues.first { i -> i.blocking }.message) }
            return
        }
        _uiState.update { it.copy(phase = WalkPhase.Measuring(name, 0, durationMs, null), nameError = null) }
        job = viewModelScope.launch {
            try {
                walkTester.run(name, durationMs).collect { event ->
                    when (event) {
                        is WalkEvent.Progress -> _uiState.update {
                            it.copy(phase = WalkPhase.Measuring(name, event.elapsedMs, event.totalMs, event.latestRssiDbm))
                        }
                        is WalkEvent.Finished -> {
                            val r = event.result
                            repository.save(RunRecord.from(r.measurements, r.diagnosis, roomLabel = r.roomName))
                            _uiState.update { it.copy(phase = WalkPhase.Done(r.roomName, r.diagnosis), roomName = "") }
                        }
                    }
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(phase = WalkPhase.Idle, message = "Walk test cancelled") }
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(phase = WalkPhase.Idle, message = "Walk test failed: ${e.message}") }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun deleteRoom(name: String) {
        viewModelScope.launch { repository.deleteRoom(name) }
    }

    fun messageShown() = _uiState.update { it.copy(message = null) }

    companion object {
        const val WALK_MS = 10_000L
        private const val MAX_NAME = 40
    }
}
