package io.github.jesperlund.wifidoctor.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jesperlund.wifidoctor.data.AppSettings
import io.github.jesperlund.wifidoctor.platform.AppPermissions
import io.github.jesperlund.wifidoctor.ui.components.SectionTitle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun intervalLabel(minutes: Long): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 1440 == 0L -> if (minutes == 1440L) "Daily" else "${minutes / 1440} days"
    minutes % 60 == 0L -> "${minutes / 60} h"
    else -> "$minutes min"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(snackbar: SnackbarHostState, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val s = state.settings
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { viewModel.exportCsv(it.toString()) }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Scheduling works either way; without the permission problems are only visible in History.
        viewModel.setScheduleEnabled(true)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Scheduled checks")
                SwitchRow("Check Wi-Fi in the background", s.scheduleEnabled) { enabled ->
                    val permission = AppPermissions.notificationPermission()
                    if (enabled && permission != null) notificationLauncher.launch(permission) else viewModel.setScheduleEnabled(enabled)
                }
                Text("How often", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppSettings.INTERVAL_CHOICES.forEach { minutes ->
                        FilterChip(
                            selected = s.intervalMinutes == minutes,
                            onClick = { viewModel.setInterval(minutes) },
                            label = { Text(intervalLabel(minutes)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                SwitchRow("Notify only when there is a problem", s.notifyOnlyOnProblems, viewModel::setNotifyOnlyOnProblems)
                SwitchRow("Include speed test in background checks", s.speedTestInBackground, viewModel::setSpeedTestInBackground)
                Text(
                    "Android may delay background checks to save battery, and limits background Wi-Fi scans to one every 30 minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Tests")
                var url by rememberSaveable(s.testServerUrl) { mutableStateOf(s.testServerUrl) }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Speed test download URL") },
                    isError = state.urlError != null,
                    supportingText = { Text(state.urlError ?: "A large file. Data used per test: up to ~10 s of download.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { viewModel.setTestServerUrl(url) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save URL") }

                var targets by rememberSaveable(s.probeTargets) { mutableStateOf(s.probeTargets.joinToString(", ")) }
                OutlinedTextField(
                    value = targets,
                    onValueChange = { targets = it },
                    label = { Text("Internet probe targets") },
                    isError = state.targetsError != null,
                    supportingText = { Text(state.targetsError ?: "IP addresses or host names, comma separated") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { viewModel.setProbeTargets(targets) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save targets") }

                var country by rememberSaveable(s.countryOverride) { mutableStateOf(s.countryOverride) }
                OutlinedTextField(
                    value = country,
                    onValueChange = { country = it.take(2) },
                    label = { Text("Country for channel rules") },
                    placeholder = { Text("Automatic") },
                    isError = state.countryError != null,
                    supportingText = {
                        Text(
                            state.countryError
                                ?: "Leave empty to detect it from nearby routers or the mobile network. Use a code such as US, DE or GB.",
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { viewModel.setCountryOverride(country) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Save country")
                }

                SwitchRow("Use ping as a fallback probe", s.usePingFallback, viewModel::setUsePingFallback)
                SwitchRow("Run speed test on metered Wi-Fi", s.speedTestOnMetered, viewModel::setSpeedTestOnMetered)
                TextButton(onClick = { viewModel.resetDefaults() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Restore default test settings") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Data")
                Button(
                    onClick = {
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
                        exportLauncher.launch("wifidoctor-$stamp.csv")
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Export history as CSV") }
                OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Clear history")
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    "WifiDoctor has no analytics and no ads. It only connects to your router, the probe targets, the DNS servers " +
                        "being compared and the speed test URL above. All results stay on this device unless you export them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all history?") },
            text = { Text("All diagnostic runs and walk tests will be deleted. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { viewModel.clearHistory(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
