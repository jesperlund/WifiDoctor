package io.github.jesperlund.wifidoctor.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jesperlund.wifidoctor.core.history.RunRecord
import io.github.jesperlund.wifidoctor.core.model.Diagnosis
import io.github.jesperlund.wifidoctor.ui.components.DiagnosisCard
import io.github.jesperlund.wifidoctor.ui.components.SectionTitle
import io.github.jesperlund.wifidoctor.ui.components.SeverityBadge
import io.github.jesperlund.wifidoctor.ui.theme.LocalStatusColors
import java.text.DateFormat
import java.util.Date

fun RunRecord.toDiagnosis() = Diagnosis(verdict, severity, layer, explanation, fixes)

private fun formatTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val runs = state.runs
    val selectedIndex = state.selected?.let { sel -> runs.indexOfFirst { it.id == sel.id } }?.takeIf { it >= 0 }
    val dropIndices = runs.indices.filter { runs[it].id in state.drops }.toSet()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (runs.isEmpty()) {
            item {
                Text(
                    "No runs yet. Run a diagnosis, do a walk test or turn on scheduled checks in Settings to build up history.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@LazyColumn
        }

        item {
            Text(
                "${runs.size} runs from ${formatTime(runs.first().timestampMs)} to ${formatTime(runs.last().timestampMs)}. " +
                    "Tap a point to see that run's diagnosis.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { DropSummary(state) }

        Metric.entries.forEach { metric ->
            item(key = metric.name) {
                val values = state.values(metric)
                val present = values.filterNotNull()
                val description = if (present.isEmpty()) {
                    "${metric.label} chart: no data"
                } else {
                    "${metric.label} chart over ${runs.size} runs. Minimum ${present.min().toInt()} ${metric.unit}, " +
                        "maximum ${present.max().toInt()} ${metric.unit}, latest ${present.last().toInt()} ${metric.unit}. " +
                        "${dropIndices.size} intermittent drops marked with triangles."
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        SectionTitle("${metric.label} (${metric.unit})")
                        LineChart(
                            values = values,
                            highlighted = dropIndices,
                            selectedIndex = selectedIndex,
                            onSelect = { viewModel.select(runs[it].id) },
                            description = description,
                            unit = metric.unit,
                            floorAtZero = metric != Metric.RSSI,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        state.selected?.let { run ->
            item(key = "selected") {
                DiagnosisCard(run.toDiagnosis(), title = "Run of ${formatTime(run.timestampMs)}" + (run.roomLabel?.let { " · $it" } ?: ""))
            }
        }

        item { SectionTitle("Recent runs", Modifier.padding(top = 8.dp)) }
        items(runs.asReversed().take(30), key = { it.id }) { run ->
            RunRow(run, isDrop = run.id in state.drops, isSelected = run.id == state.selected?.id) { viewModel.select(run.id) }
        }
    }
}

@Composable
private fun DropSummary(state: HistoryUiState) {
    val fail = LocalStatusColors.current.fail
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.drops.isEmpty()) {
                Text("No intermittent drops detected.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = fail)
                Spacer(Modifier.width(8.dp))
                val reasons = state.drops.values.flatMap { it.reasons }.groupingBy { it.label }.eachCount()
                    .entries.joinToString(", ") { "${it.value}× ${it.key.lowercase()}" }
                Text(
                    "${state.drops.size} intermittent drops ($reasons). They are marked with ▲ in the charts.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun RunRow(run: RunRecord, isDrop: Boolean, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { selected = isSelected }
            .clickable(role = Role.Button, onClickLabel = "Show diagnosis", onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                formatTime(run.timestampMs) + (run.roomLabel?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                run.verdict + (if (isDrop) " · ▲ drop" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SeverityBadge(run.severity)
    }
}
