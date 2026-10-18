package com.example.wifidoctor.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wifidoctor.core.history.CsvExporter
import com.example.wifidoctor.data.AppSettings
import com.example.wifidoctor.data.RunRepository
import com.example.wifidoctor.data.SettingsRepository
import com.example.wifidoctor.data.SettingsValidation
import com.example.wifidoctor.platform.DocumentWriter
import com.example.wifidoctor.work.CheckScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val urlError: String? = null,
    val targetsError: String? = null,
    val countryError: String? = null,
    val message: String? = null,
)

private data class Transient(
    val urlError: String? = null,
    val targetsError: String? = null,
    val countryError: String? = null,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val scheduler: CheckScheduler,
    private val repository: RunRepository,
    private val writer: DocumentWriter,
) : ViewModel() {

    private val transient = MutableStateFlow(Transient())

    val uiState: StateFlow<SettingsUiState> = combine(settingsRepository.settings, transient) { s, t ->
        SettingsUiState(s, t.urlError, t.targetsError, t.countryError, t.message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private fun edit(transform: (AppSettings) -> AppSettings) = viewModelScope.launch {
        settingsRepository.update(transform)
    }

    fun setScheduleEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.update { it.copy(scheduleEnabled = enabled) }
        val s = settingsRepository.settings.first()
        scheduler.apply(s.scheduleEnabled, s.intervalMinutes)
    }

    fun setInterval(minutes: Long) = viewModelScope.launch {
        settingsRepository.update { it.copy(intervalMinutes = minutes.coerceAtLeast(15)) }
        val s = settingsRepository.settings.first()
        if (s.scheduleEnabled) scheduler.apply(true, s.intervalMinutes)
    }

    fun setNotifyOnlyOnProblems(value: Boolean) = edit { it.copy(notifyOnlyOnProblems = value) }
    fun setUsePingFallback(value: Boolean) = edit { it.copy(usePingFallback = value) }
    fun setSpeedTestOnMetered(value: Boolean) = edit { it.copy(speedTestOnMetered = value) }
    fun setSpeedTestInBackground(value: Boolean) = edit { it.copy(speedTestInBackground = value) }

    /** Returns true if the URL was valid and saved. */
    fun setTestServerUrl(url: String): Boolean {
        if (!SettingsValidation.isValidUrl(url)) {
            transient.update { it.copy(urlError = "Enter a full http:// or https:// URL") }
            return false
        }
        transient.update { it.copy(urlError = null, message = "Test server saved") }
        edit { it.copy(testServerUrl = url.trim()) }
        return true
    }

    fun setProbeTargets(text: String): Boolean {
        val targets = SettingsValidation.parseTargets(text)
        if (targets == null) {
            transient.update { it.copy(targetsError = "Enter 1–5 IP addresses or host names, separated by commas") }
            return false
        }
        transient.update { it.copy(targetsError = null, message = "Probe targets saved") }
        edit { it.copy(probeTargets = targets) }
        return true
    }

    /** Blank means "detect automatically". */
    fun setCountryOverride(code: String): Boolean {
        if (!SettingsValidation.isValidCountry(code)) {
            transient.update { it.copy(countryError = "Enter a two-letter country code such as US, DE or GB, or leave empty") }
            return false
        }
        val normalized = code.trim().uppercase()
        transient.update { it.copy(countryError = null, message = if (normalized.isEmpty()) "Country set to automatic" else "Country set to $normalized") }
        edit { it.copy(countryOverride = normalized) }
        return true
    }

    fun resetDefaults() = viewModelScope.launch {
        settingsRepository.update { current ->
            AppSettings(scheduleEnabled = current.scheduleEnabled, intervalMinutes = current.intervalMinutes)
        }
        transient.value = Transient(message = "Defaults restored")
    }

    fun exportCsv(uri: String) = viewModelScope.launch {
        try {
            val runs = repository.all()
            writer.write(uri, CsvExporter.export(runs))
            transient.update { it.copy(message = "Exported ${runs.size} runs") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            transient.update { it.copy(message = "Export failed: ${e.message}") }
        }
    }

    fun clearHistory() = viewModelScope.launch {
        repository.clear()
        transient.update { it.copy(message = "History cleared") }
    }

    fun messageShown() = transient.update { it.copy(message = null) }
}
