package com.example.wifidoctor.ui.dashboard

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wifidoctor.core.model.EnvironmentIssue
import com.example.wifidoctor.core.model.EnvironmentStatus
import com.example.wifidoctor.core.model.LinkInfo
import com.example.wifidoctor.core.probe.ThroughputProgress
import com.example.wifidoctor.ui.components.DiagnosisCard
import com.example.wifidoctor.ui.components.LayerStepper
import com.example.wifidoctor.ui.components.NoticeCard
import com.example.wifidoctor.ui.components.RssiGauge
import com.example.wifidoctor.ui.components.SectionTitle

@Composable
fun DashboardScreen(
    snackbar: SnackbarHostState,
    onRequestPermissions: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val link by viewModel.liveLink.collectAsStateWithLifecycle()
    val env by viewModel.environment.collectAsStateWithLifecycle()

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(env.issues.size) { i -> EnvironmentNotice(env.issues[i], onRequestPermissions) }
        item { LinkCard(link, env) }
        item {
            RunButton(
                running = state.running,
                enabled = env.canDiagnose,
                onRun = viewModel::runDiagnosis,
                onCancel = viewModel::cancel,
            )
        }
        if (state.running || state.diagnosis != null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        SectionTitle("Checks")
                        Spacer(Modifier.height(8.dp))
                        LayerStepper(state.steps)
                        state.throughput?.let { ThroughputBar(it) }
                    }
                }
            }
        }
        state.diagnosis?.let { d -> item { DiagnosisCard(d, title = "Result") } }
    }
}

@Composable
private fun EnvironmentNotice(issue: EnvironmentIssue, onRequestPermissions: () -> Unit) {
    val context = LocalContext.current
    val action: (@Composable () -> Unit)? = when (issue) {
        EnvironmentIssue.WIFI_OFF, EnvironmentIssue.NOT_CONNECTED -> {
            {
                TextButton(onClick = {
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        Intent(Settings.Panel.ACTION_WIFI)
                    } else {
                        Intent(Settings.ACTION_WIFI_SETTINGS)
                    }
                    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Wi-Fi") }
            }
        }
        EnvironmentIssue.LOCATION_OFF -> {
            {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text("Turn on") }
            }
        }
        EnvironmentIssue.PERMISSION_MISSING -> { { TextButton(onClick = onRequestPermissions) { Text("Allow") } } }
        else -> null
    }
    NoticeCard(issue.title, issue.message, issue.blocking, action = action)
}

@Composable
private fun LinkCard(link: LinkInfo?, env: EnvironmentStatus) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("Live connection")
            Spacer(Modifier.height(8.dp))
            if (link == null) {
                Text(
                    if (!env.wifiEnabled) "Wi-Fi is off" else "Not connected to Wi-Fi",
                    style = MaterialTheme.typography.bodyLarge,
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RssiGauge(link.rssiDbm)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Stat("Network", link.ssid ?: "Hidden (no permission)")
                    Stat("Band", link.band.label + (link.channel?.let { " · ch $it" } ?: ""))
                    Stat("Link speed", link.linkSpeedMbps?.let { "$it Mbps" } ?: "–")
                    Stat("Width", link.channelWidthMhz?.let { "$it MHz" } ?: "–")
                    Stat("Standard", link.standard.label)
                }
            }
            link.bssid?.let {
                Text("BSSID $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.semantics(mergeDescendants = true) {}) {
        Text("$label: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RunButton(running: Boolean, enabled: Boolean, onRun: () -> Unit, onCancel: () -> Unit) {
    if (running) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Icon(Icons.Filled.Close, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Cancel diagnosis", style = MaterialTheme.typography.titleMedium)
        }
    } else {
        Button(onClick = onRun, enabled = enabled, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Run diagnosis", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ThroughputBar(p: ThroughputProgress) {
    val speed = p.mbps?.let { "%.1f Mbps".format(it) } ?: "measuring…"
    Column(Modifier.padding(top = 8.dp)) {
        Text("Download: $speed", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(
            progress = { p.fraction },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .semantics {
                    contentDescription = "Speed test progress"
                    stateDescription = "${(p.fraction * 100).toInt()} percent, $speed"
                },
        )
    }
}
