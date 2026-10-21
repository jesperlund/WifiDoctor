package io.github.jesperlund.wifidoctor.ui.monitor

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jesperlund.wifidoctor.monitor.MonitorRunner
import io.github.jesperlund.wifidoctor.monitor.MonitorState
import io.github.jesperlund.wifidoctor.platform.EnvironmentMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class MonitorUiOptions(
    val durationMinutes: Int = 5,
    val message: String? = null,
)

@HiltViewModel
class MonitorViewModel @Inject constructor(
    private val runner: MonitorRunner,
    private val environment: EnvironmentMonitor,
) : ViewModel() {

    val state: StateFlow<MonitorState> = runner.state

    private val _options = MutableStateFlow(MonitorUiOptions())
    val options: StateFlow<MonitorUiOptions> = _options.asStateFlow()

    fun setDuration(minutes: Int) = _options.update { it.copy(durationMinutes = minutes) }

    fun start() {
        val env = environment.refresh()
        if (!env.canDiagnose) {
            _options.update { it.copy(message = env.issues.first { i -> i.blocking }.message) }
            return
        }
        runner.start(_options.value.durationMinutes * 60_000L)
    }

    fun stop() = runner.stop()

    fun newSession() = runner.reset()

    fun messageShown() = _options.update { it.copy(message = null) }

    companion object {
        val DURATIONS = listOf(2, 5, 15, 30)
    }
}
