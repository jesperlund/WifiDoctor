package io.github.jesperlund.wifidoctor.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.jesperlund.wifidoctor.core.engine.RunConfig
import io.github.jesperlund.wifidoctor.core.wifi.RegionHints
import io.github.jesperlund.wifidoctor.core.wifi.RegionResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.net.InetAddress
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val scheduleEnabled: Boolean = false,
    val intervalMinutes: Long = 360,
    val notifyOnlyOnProblems: Boolean = true,
    val testServerUrl: String = RunConfig.DEFAULT_THROUGHPUT_URL,
    val probeTargets: List<String> = DEFAULT_TARGETS,
    val usePingFallback: Boolean = true,
    val speedTestOnMetered: Boolean = false,
    val speedTestInBackground: Boolean = false,
    /** ISO country code for channel rules; blank = detect automatically. */
    val countryOverride: String = "",
    val uploadUrl: String = RunConfig.DEFAULT_UPLOAD_URL,
    /** UDP/STUN test of the path calls use. */
    val udpTestEnabled: Boolean = true,
    /** Reachability/timing checks of Instagram's and Teams' servers. */
    val servicesTestEnabled: Boolean = true,
) {
    /** Builds the engine configuration for a run. Speed tests are skipped on metered Wi-Fi unless allowed. */
    fun toRunConfig(metered: Boolean, background: Boolean = false, regionHints: RegionHints = RegionHints()): RunConfig = RunConfig(
        internetTargets = probeTargets.ifEmpty { DEFAULT_TARGETS },
        regionHints = regionHints,
        throughputUrl = testServerUrl,
        uploadUrl = uploadUrl,
        runRealtime = udpTestEnabled,
        runServices = servicesTestEnabled,
        runThroughput = (!metered || speedTestOnMetered) && (!background || speedTestInBackground),
    )

    companion object {
        val DEFAULT_TARGETS = listOf("1.1.1.1", "8.8.8.8")

        /** Intervals offered in Settings; WorkManager's minimum is 15 minutes. */
        val INTERVAL_CHOICES = listOf(15L, 60L, 180L, 360L, 720L, 1440L)
    }
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

/** Input validation for the free-text settings. Pure, so it is unit tested. */
object SettingsValidation {
    private val hostname = Regex("""^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)(\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""")
    private val ipv4 = Regex("""^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")

    /** Splits a comma/space separated list of hosts. Returns null if any entry is invalid or the list is empty. */
    fun parseTargets(text: String): List<String>? {
        val items = text.split(',', ' ', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (items.isEmpty() || items.size > 5) return null
        return items.takeIf { list -> list.all { isValidHost(it) } }
    }

    fun isValidHost(host: String): Boolean =
        ipv4.matches(host) || (host.contains(':') && isIpv6Literal(host)) || (hostname.matches(host) && !host.all { it.isDigit() || it == '.' })

    private fun isIpv6Literal(host: String): Boolean =
        host.all { it.isLetterOrDigit() || it == ':' || it == '.' } &&
            runCatching { InetAddress.getByName(host) }.isSuccess // literal: no DNS lookup happens

    /** Blank (automatic) or a two-letter ISO 3166 country code. */
    fun isValidCountry(code: String): Boolean = code.isBlank() || RegionResolver.normalize(code) != null

    fun isValidUrl(url: String): Boolean = runCatching {
        val uri = URI(url.trim())
        (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) : SettingsRepository {
    private val store = context.dataStore

    private object Keys {
        val scheduleEnabled = booleanPreferencesKey("schedule_enabled")
        val interval = longPreferencesKey("interval_minutes")
        val notifyOnlyProblems = booleanPreferencesKey("notify_only_problems")
        val url = stringPreferencesKey("test_server_url")
        val targets = stringPreferencesKey("probe_targets")
        val ping = booleanPreferencesKey("use_ping_fallback")
        val metered = booleanPreferencesKey("speed_test_on_metered")
        val background = booleanPreferencesKey("speed_test_in_background")
        val country = stringPreferencesKey("country_override")
        val uploadUrl = stringPreferencesKey("upload_url")
        val udp = booleanPreferencesKey("udp_test_enabled")
        val services = booleanPreferencesKey("services_test_enabled")
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            scheduleEnabled = this[Keys.scheduleEnabled] ?: d.scheduleEnabled,
            intervalMinutes = this[Keys.interval] ?: d.intervalMinutes,
            notifyOnlyOnProblems = this[Keys.notifyOnlyProblems] ?: d.notifyOnlyOnProblems,
            testServerUrl = this[Keys.url] ?: d.testServerUrl,
            probeTargets = this[Keys.targets]?.split(',')?.filter { it.isNotBlank() } ?: d.probeTargets,
            usePingFallback = this[Keys.ping] ?: d.usePingFallback,
            speedTestOnMetered = this[Keys.metered] ?: d.speedTestOnMetered,
            speedTestInBackground = this[Keys.background] ?: d.speedTestInBackground,
            countryOverride = this[Keys.country] ?: d.countryOverride,
            uploadUrl = this[Keys.uploadUrl] ?: d.uploadUrl,
            udpTestEnabled = this[Keys.udp] ?: d.udpTestEnabled,
            servicesTestEnabled = this[Keys.services] ?: d.servicesTestEnabled,
        )
    }

    override val settings: Flow<AppSettings> = store.data.map { it.toSettings() }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val current = p.toSettings()
            val next = transform(current)
            p[Keys.scheduleEnabled] = next.scheduleEnabled
            p[Keys.interval] = next.intervalMinutes
            p[Keys.notifyOnlyProblems] = next.notifyOnlyOnProblems
            p[Keys.url] = next.testServerUrl
            p[Keys.targets] = next.probeTargets.joinToString(",")
            p[Keys.ping] = next.usePingFallback
            p[Keys.metered] = next.speedTestOnMetered
            p[Keys.background] = next.speedTestInBackground
            p[Keys.country] = next.countryOverride
            p[Keys.uploadUrl] = next.uploadUrl
            p[Keys.udp] = next.udpTestEnabled
            p[Keys.services] = next.servicesTestEnabled
        }
    }
}
