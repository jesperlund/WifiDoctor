package com.example.wifidoctor.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.example.wifidoctor.core.model.EnvironmentStatus
import com.example.wifidoctor.core.model.Transport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Device conditions that affect diagnosis: Wi-Fi on/off, connection, location, permissions, metering. */
interface EnvironmentMonitor {
    val status: StateFlow<EnvironmentStatus>
    fun refresh(): EnvironmentStatus
}

@Singleton
class AndroidEnvironmentMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wifiManager: WifiManager,
    private val connectivityManager: ConnectivityManager,
    private val locationManager: LocationManager,
    private val tracker: WifiNetworkTracker,
    private val permissions: PermissionChecker,
) : EnvironmentMonitor {

    private val _status = MutableStateFlow(EnvironmentStatus.Unknown)
    override val status: StateFlow<EnvironmentStatus> = _status.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    /** Registers listeners once for the lifetime of the process. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        tracker.start()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                refresh()
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refreshAsync()
                override fun onLost(network: Network) = refreshAsync()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refreshAsync()
            })
        }
        scope.launch { tracker.state.collect { refresh() } }
        refresh()
    }

    private fun refreshAsync() {
        scope.launch { refresh() }
    }

    override fun refresh(): EnvironmentStatus {
        val wifi = tracker.state.value
        val defaultCaps = runCatching {
            connectivityManager.activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        }.getOrNull()
        val transport = when {
            defaultCaps == null -> Transport.NONE
            defaultCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            defaultCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            defaultCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
            else -> Transport.OTHER
        }
        val wifiMetered = wifi.capabilities?.let {
            !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } ?: false
        val status = EnvironmentStatus(
            wifiEnabled = runCatching { wifiManager.isWifiEnabled }.getOrDefault(true),
            wifiConnected = wifi.connected,
            locationEnabled = runCatching { LocationManagerCompat.isLocationEnabled(locationManager) }.getOrDefault(true),
            locationPermission = permissions.hasLocation(),
            nearbyWifiPermission = permissions.hasNearbyWifi(),
            defaultTransport = transport,
            wifiMetered = wifiMetered,
        )
        _status.value = status
        return status
    }
}
