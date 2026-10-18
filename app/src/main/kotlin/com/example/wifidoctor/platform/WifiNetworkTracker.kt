package com.example.wifidoctor.platform

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Latest state of the Wi-Fi network (if any), kept current by a NetworkCallback. */
data class WifiNetworkState(
    val network: Network? = null,
    val capabilities: NetworkCapabilities? = null,
    val linkProperties: LinkProperties? = null,
) {
    val connected: Boolean get() = network != null
}

@Singleton
class WifiNetworkTracker @Inject constructor(
    private val connectivityManager: ConnectivityManager,
) {
    private val _state = MutableStateFlow(WifiNetworkState())
    val state: StateFlow<WifiNetworkState> = _state.asStateFlow()

    private var started = false

    private val callback: ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Without this flag Android 12+ redacts SSID/BSSID in the WifiInfo we receive.
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) = onNetwork(network)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onCaps(network, caps)
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = onLink(network, lp)
                override fun onLost(network: Network) = onGone(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onNetwork(network)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onCaps(network, caps)
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = onLink(network, lp)
                override fun onLost(network: Network) = onGone(network)
            }
        }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // Track Wi-Fi even when it has no internet: that is exactly what we want to diagnose.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivityManager.registerNetworkCallback(request, callback) }
            .onFailure { started = false }
    }

    private fun onNetwork(network: Network) {
        _state.value = WifiNetworkState(
            network = network,
            capabilities = runCatching { connectivityManager.getNetworkCapabilities(network) }.getOrNull(),
            linkProperties = runCatching { connectivityManager.getLinkProperties(network) }.getOrNull(),
        )
    }

    private fun onCaps(network: Network, caps: NetworkCapabilities) {
        val current = _state.value
        _state.value = if (current.network == network) current.copy(capabilities = caps) else WifiNetworkState(network, caps, null)
    }

    private fun onLink(network: Network, lp: LinkProperties) {
        val current = _state.value
        _state.value = if (current.network == network) current.copy(linkProperties = lp) else WifiNetworkState(network, null, lp)
    }

    private fun onGone(network: Network) {
        if (_state.value.network == network) _state.value = WifiNetworkState()
    }
}
