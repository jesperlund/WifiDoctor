package io.github.jesperlund.wifidoctor.monitor

import io.github.jesperlund.wifidoctor.core.engine.LinkSource
import io.github.jesperlund.wifidoctor.core.history.RunRecord
import io.github.jesperlund.wifidoctor.core.model.Diagnosis
import io.github.jesperlund.wifidoctor.core.monitor.CallMonitor
import io.github.jesperlund.wifidoctor.core.monitor.MonitorAnalyzer
import io.github.jesperlund.wifidoctor.core.monitor.MonitorConfig
import io.github.jesperlund.wifidoctor.core.monitor.MonitorProgress
import io.github.jesperlund.wifidoctor.core.monitor.MonitorReport
import io.github.jesperlund.wifidoctor.core.probe.RttProbe
import io.github.jesperlund.wifidoctor.data.AppSettings
import io.github.jesperlund.wifidoctor.data.RunRepository
import io.github.jesperlund.wifidoctor.data.SettingsRepository
import io.github.jesperlund.wifidoctor.di.ApplicationScope
import io.github.jesperlund.wifidoctor.di.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface MonitorState {
    data object Idle : MonitorState
    data class Running(val durationMs: Long, val progress: MonitorProgress? = null) : MonitorState
    data class Finished(val report: MonitorReport, val diagnosis: Diagnosis, val savedRunId: Long?) : MonitorState
    data class Failed(val message: String) : MonitorState
}

/** Runs one call-monitor session at a time, independent of any screen. */
interface MonitorRunner {
    val state: StateFlow<MonitorState>
    fun start(durationMs: Long)
    fun stop()
    fun reset()
}

/** Keeps the process in the foreground (with a notification) while a session runs. */
interface ForegroundKeeper {
    fun start()
    fun stop()
}

@Singleton
class CallMonitorController @Inject constructor(
    private val linkSource: LinkSource,
    private val probe: RttProbe,
    private val repository: RunRepository,
    private val settings: SettingsRepository,
    private val foreground: ForegroundKeeper,
    @ApplicationScope private val scope: CoroutineScope,
    private val clock: Clock,
) : MonitorRunner {

    private val _state = MutableStateFlow<MonitorState>(MonitorState.Idle)
    override val state: StateFlow<MonitorState> = _state.asStateFlow()

    @Volatile private var stopRequested = false
    private var job: Job? = null

    @Synchronized
    override fun start(durationMs: Long) {
        if (_state.value is MonitorState.Running) return
        stopRequested = false
        _state.value = MonitorState.Running(durationMs)
        foreground.start()
        job = scope.launch {
            try {
                val target = settings.settings.first().probeTargets.firstOrNull() ?: AppSettings.DEFAULT_TARGETS.first()
                val monitor = CallMonitor(linkSource, probe, clock::now)
                val report = monitor.run(
                    MonitorConfig(durationMs = durationMs, internetTarget = target),
                    stop = { stopRequested },
                    onProgress = { _state.value = MonitorState.Running(durationMs, it) },
                )
                val diagnosis = MonitorAnalyzer.diagnose(report)
                val id = if (report.samples.isEmpty()) null else repository.save(RunRecord.fromMonitor(report, diagnosis))
                _state.value = MonitorState.Finished(report, diagnosis, id)
            } catch (e: CancellationException) {
                _state.value = MonitorState.Idle
                throw e
            } catch (e: Exception) {
                _state.value = MonitorState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                foreground.stop()
            }
        }
    }

    /** Ends the session early; what was measured so far is still analysed and saved. */
    override fun stop() {
        stopRequested = true
    }

    override fun reset() {
        if (_state.value !is MonitorState.Running) _state.value = MonitorState.Idle
    }
}
