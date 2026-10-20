package io.github.jesperlund.wifidoctor.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jesperlund.wifidoctor.core.history.Drop
import io.github.jesperlund.wifidoctor.core.history.DropDetector
import io.github.jesperlund.wifidoctor.core.history.RunRecord
import io.github.jesperlund.wifidoctor.data.RunRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class Metric(val label: String, val unit: String) {
    LATENCY("Latency", "ms"),
    LOSS("Packet loss", "%"),
    RSSI("Signal", "dBm"),
}

data class HistoryUiState(
    val runs: List<RunRecord> = emptyList(),
    val drops: Map<Long, Drop> = emptyMap(),
    val selected: RunRecord? = null,
) {
    fun values(metric: Metric): List<Double?> = runs.map { metric.valueOf(it) }
}

fun Metric.valueOf(r: RunRecord): Double? = when (this) {
    Metric.LATENCY -> r.internetAvgMs ?: r.gatewayAvgMs
    Metric.LOSS -> listOfNotNull(r.gatewayLossPercent, r.internetLossPercent).maxOrNull()
    Metric.RSSI -> r.rssiDbm?.toDouble()
}

@HiltViewModel
class HistoryViewModel @Inject constructor(repository: RunRepository) : ViewModel() {

    private val selectedId = MutableStateFlow<Long?>(null)

    val uiState: StateFlow<HistoryUiState> = combine(repository.observeRuns(), selectedId) { all, selected ->
        val runs = all.takeLast(MAX_POINTS)
        HistoryUiState(
            runs = runs,
            drops = DropDetector.detect(runs).associateBy { it.recordId },
            selected = runs.firstOrNull { it.id == selected },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun select(id: Long?) {
        selectedId.value = if (selectedId.value == id) null else id
    }

    private companion object {
        const val MAX_POINTS = 200
    }
}
