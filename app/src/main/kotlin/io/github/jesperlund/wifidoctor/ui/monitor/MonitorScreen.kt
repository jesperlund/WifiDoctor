package io.github.jesperlund.wifidoctor.ui.monitor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jesperlund.wifidoctor.core.monitor.MonitorProgress
import io.github.jesperlund.wifidoctor.core.monitor.MonitorReport
import io.github.jesperlund.wifidoctor.core.monitor.MonitorSample
import io.github.jesperlund.wifidoctor.core.monitor.SeriesStats
import io.github.jesperlund.wifidoctor.monitor.MonitorState
import io.github.jesperlund.wifidoctor.platform.AppPermissions
import io.github.jesperlund.wifidoctor.ui.components.DiagnosisCard
import io.github.jesperlund.wifidoctor.ui.components.SectionTitle
import io.github.jesperlund.wifidoctor.ui.history.LineChart

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${"%02d".format(s % 60)}"
}

@Composable
fun MonitorScreen(snackbar: SnackbarHostState, viewModel: MonitorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()
    // The live notification needs this permission on Android 13+; the monitor runs either way.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.start() }

    LaunchedEffect(options.message) {
        options.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val s = state) {
            MonitorState.Idle, is MonitorState.Failed -> {
                item {
                    SetupCard(
                        selected = options.durationMinutes,
                        error = (s as? MonitorState.Failed)?.message,
                        onSelect = viewModel::setDuration,
                        onStart = {
                            val permission = AppPermissions.notificationPermission()
                            if (permission != null) notificationLauncher.launch(permission) else viewModel.start()
                        },
                    )
                }
            }
            is MonitorState.Running -> item { RunningCard(s, onStop = viewModel::stop) }
            is MonitorState.Finished -> {
                item { DiagnosisCard(s.diagnosis, title = "Call monitor result") }
                item { ReportCard(s.report) }
                item {
                    Button(onClick = viewModel::newSession, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text("New session")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupCard(selected: Int, error: String?, onSelect: (Int) -> Unit, onStart: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Monitor a call")
            Text(
                "1. Start the monitor.\n2. Switch to Teams (or WhatsApp, FaceTime, Instagram) and make your call or watch videos as usual.\n" +
                    "3. Come back when it ends, or stop it from the notification.\n\n" +
                    "WifiDoctor checks your router and the internet every 200 ms and records signal strength and access-point " +
                    "changes, so it can tell Wi-Fi problems from internet problems and catch drop-outs that a quick test misses. " +
                    "It uses very little data (about 1 MB per 10 minutes).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Duration", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MonitorViewModel.DURATIONS.forEach { minutes ->
                    FilterChip(
                        selected = minutes == selected,
                        onClick = { onSelect(minutes) },
                        label = { Text("$minutes min") },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            error?.let { Text("Last session failed: $it", color = MaterialTheme.colorScheme.error) }
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Start monitoring") }
        }
    }
}

@Composable
private fun RunningCard(state: MonitorState.Running, onStop: () -> Unit) {
    val p = state.progress
    val elapsed = p?.elapsedMs ?: 0
    val fraction = (elapsed.toFloat() / state.durationMs).coerceIn(0f, 1f)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Monitoring… you can switch to your call now")
            Text("${clock(elapsed)} of ${clock(state.durationMs)}", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Monitor progress"
                    stateDescription = "${clock(state.durationMs - elapsed)} left"
                },
            )
            if (p != null) {
                LiveStats(p)
                Text("Internet round trip, last minute (ms)", style = MaterialTheme.typography.labelLarge)
                LiveChart(p.recent)
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Stop and show results")
            }
        }
    }
}

@Composable
private fun LiveStats(p: MonitorProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatRow("Internet", p.internet)
        p.gateway?.let { StatRow("Router", it) }
        Text(
            "Drop-outs: ${p.dropouts} · Access-point switches: ${p.roams}" + (p.latestRssiDbm?.let { " · Signal: $it dBm" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun StatRow(label: String, s: SeriesStats) {
    Row(Modifier.semantics(mergeDescendants = true) {}) {
        Text("$label: ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(72.dp))
        Text(
            "median ${s.p50Ms?.toInt() ?: "–"} ms · worst 5% ${s.p95Ms?.toInt() ?: "–"} ms · loss ${"%.1f".format(s.lossPercent)}%",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun LiveChart(recent: List<MonitorSample>) {
    val values = recent.map { it.internetMs }
    val lost = values.indices.filter { values[it] == null }.toSet()
    val ok = values.filterNotNull()
    LineChart(
        values = values,
        highlighted = lost,
        selectedIndex = null,
        onSelect = {},
        description = "Live latency chart: ${ok.size} replies, ${lost.size} lost in the last minute" +
            (ok.maxOrNull()?.let { ", worst ${it.toInt()} ms" } ?: ""),
        unit = "ms",
    )
}

@Composable
private fun ReportCard(r: MonitorReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Details")
            Text(
                "Measured ${clock(r.durationMs)}" + (if (r.stoppedEarly) " (stopped early)" else "") +
                    " · ${r.samples.size} samples to ${r.internetTarget}" + (r.gatewayIp?.let { " and your router ($it)" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatRow("Internet", r.internet)
            r.gateway?.let { StatRow("Router", it) }
            Text(
                "Jitter ${r.internet.jitterMs?.toInt() ?: "–"} ms · worst ${r.internet.maxMs?.toInt() ?: "–"} ms" +
                    (r.rssiAvgDbm?.let { " · signal avg $it dBm, min ${r.rssiMinDbm} dBm" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Internet latency over the session (ms; ▲ = lost)", style = MaterialTheme.typography.labelLarge)
            val step = (r.samples.size / 300).coerceAtLeast(1) // keep the chart readable
            LiveChart(r.samples.filterIndexed { i, _ -> i % step == 0 })
            if (r.dropouts.isNotEmpty()) {
                SectionTitle("Drop-outs")
                r.dropouts.forEach { d ->
                    Text(
                        "At ${clock(d.startMs)}: ${"%.1f".format(d.durationMs / 1000.0)} s · ${d.scope.label} side · ${d.cause.label}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (r.roams.isNotEmpty()) {
                SectionTitle("Access-point switches")
                r.roams.forEach { roam ->
                    Text("At ${clock(roam.tMs)}: ${roam.fromBssid} → ${roam.toBssid}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
