package io.github.jesperlund.wifidoctor.platform

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.jesperlund.wifidoctor.core.engine.LinkSource
import io.github.jesperlund.wifidoctor.core.engine.ScanSource
import io.github.jesperlund.wifidoctor.core.model.LinkInfo
import io.github.jesperlund.wifidoctor.core.model.ScanEntry
import io.github.jesperlund.wifidoctor.core.model.ScanSnapshot
import io.github.jesperlund.wifidoctor.core.model.WifiStandard
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton

/** Reads the current association from WifiManager / ConnectivityManager. */
@Singleton
class AndroidLinkSource @Inject constructor(
    private val wifiManager: WifiManager,
    private val tracker: WifiNetworkTracker,
    private val permissions: PermissionChecker,
) : LinkSource {

    override suspend fun currentLink(): LinkInfo? = withContext(Dispatchers.Default) {
        if (!wifiManager.isWifiEnabled) return@withContext null
        val state = tracker.state.value
        val info = wifiInfo(state) ?: return@withContext null
        // networkId == -1 and no tracked network means we are not associated.
        if (state.network == null && info.networkIdCompat() == -1) return@withContext null

        val lp = state.linkProperties
        val gateway = lp?.routes
            ?.filter { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.firstNotNullOfOrNull { it.gateway?.hostAddress }
            ?: dhcpGateway()
        val bssid = WifiMappers.bssid(info.bssid)
        LinkInfo(
            ssid = WifiMappers.ssid(info.ssid),
            bssid = bssid,
            rssiDbm = WifiMappers.rssi(info.rssi),
            linkSpeedMbps = WifiMappers.linkSpeed(info.linkSpeed),
            txLinkSpeedMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiMappers.linkSpeed(info.txLinkSpeedMbps) else null,
            rxLinkSpeedMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiMappers.linkSpeed(info.rxLinkSpeedMbps) else null,
            frequencyMhz = WifiMappers.frequency(info.frequency),
            channelWidthMhz = bssid?.let { cachedWidthFor(it) },
            standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) WifiMappers.standard(info.wifiStandard) else WifiStandard.UNKNOWN,
            gatewayIp = gateway,
            dnsServers = lp?.dnsServers.orEmpty().mapNotNull { it.hostAddress },
        )
    }

    private fun wifiInfo(state: WifiNetworkState): WifiInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (state.capabilities?.transportInfo as? WifiInfo)?.let { return it }
        }
        @Suppress("DEPRECATION")
        return runCatching { wifiManager.connectionInfo }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun WifiInfo.networkIdCompat(): Int = networkId

    @Suppress("DEPRECATION")
    private fun dhcpGateway(): String? = runCatching { WifiMappers.ipv4FromInt(wifiManager.dhcpInfo.gateway) }.getOrNull()

    /** WifiInfo has no channel width, so look up our BSSID in the last scan results. */
    @SuppressLint("MissingPermission")
    private fun cachedWidthFor(bssid: String): Int? {
        if (!permissions.canScan()) return null
        return runCatching {
            wifiManager.scanResults.firstOrNull { it.BSSID.equals(bssid, ignoreCase = true) }
                ?.let { WifiMappers.channelWidthMhz(it.channelWidth) }
        }.getOrNull()
    }
}

/**
 * Scans for nearby networks. Android throttles foreground apps to 4 scans per 2 minutes (and
 * background apps to 1 per 30 minutes); when a scan is refused we fall back to cached results and
 * say so.
 */
@Singleton
class AndroidScanSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wifiManager: WifiManager,
    private val permissions: PermissionChecker,
) : ScanSource {

    @SuppressLint("MissingPermission")
    override suspend fun scan(): ScanSnapshot? {
        if (!wifiManager.isWifiEnabled || !permissions.canScan()) return null
        val done = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                done.complete(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val fresh = try {
            @Suppress("DEPRECATION")
            val started = runCatching { wifiManager.startScan() }.getOrDefault(false)
            started && (withTimeoutOrNull(SCAN_TIMEOUT_MS) { done.await() } ?: false)
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
        val results = runCatching { wifiManager.scanResults }.getOrNull() ?: return null
        val now = SystemClock.elapsedRealtime()
        val newest = results.maxOfOrNull { it.timestamp / 1000 } // timestamp is µs since boot
        return ScanSnapshot(
            entries = results.map { it.toEntry() },
            throttled = !fresh,
            ageMs = newest?.let { (now - it).coerceAtLeast(0) },
        )
    }

    @Suppress("DEPRECATION")
    private fun ScanResult.toEntry() = ScanEntry(
        ssid = WifiMappers.ssid(SSID),
        bssid = WifiMappers.bssid(BSSID),
        frequencyMhz = frequency,
        rssiDbm = level,
        channelWidthMhz = WifiMappers.channelWidthMhz(channelWidth),
        countryCode = countryCode(),
    )

    /** The AP's advertised regulatory country (Android 11+ exposes information elements). */
    private fun ScanResult.countryCode(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            informationElements.firstOrNull { it.id == WifiMappers.ELEMENT_ID_COUNTRY }?.let { ie ->
                val buffer = ie.bytes.duplicate()
                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                WifiMappers.countryFromElement(bytes)
            }
        }.getOrNull()
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 10_000L
    }
}
