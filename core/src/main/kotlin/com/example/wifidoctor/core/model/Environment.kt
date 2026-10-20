package com.example.wifidoctor.core.model

enum class Transport { WIFI, CELLULAR, ETHERNET, OTHER, NONE }

/** Device state that decides whether (and how well) a diagnosis can run. */
data class EnvironmentStatus(
    val wifiEnabled: Boolean,
    val wifiConnected: Boolean,
    val locationEnabled: Boolean,
    val locationPermission: Boolean,
    val nearbyWifiPermission: Boolean,
    val defaultTransport: Transport,
    val wifiMetered: Boolean,
) {
    val issues: List<EnvironmentIssue>
        get() = buildList {
            if (!wifiEnabled) add(EnvironmentIssue.WIFI_OFF)
            else if (!wifiConnected) add(EnvironmentIssue.NOT_CONNECTED)
            if (!locationPermission || !nearbyWifiPermission) add(EnvironmentIssue.PERMISSION_MISSING)
            if (!locationEnabled) add(EnvironmentIssue.LOCATION_OFF)
            if (wifiConnected && defaultTransport == Transport.CELLULAR) add(EnvironmentIssue.CELLULAR_DEFAULT)
            if (!wifiConnected && defaultTransport == Transport.CELLULAR) add(EnvironmentIssue.ON_CELLULAR)
            if (wifiConnected && wifiMetered) add(EnvironmentIssue.METERED)
        }

    val canDiagnose: Boolean get() = issues.none { it.blocking }

    companion object {
        val Unknown = EnvironmentStatus(
            wifiEnabled = true, wifiConnected = true, locationEnabled = true, locationPermission = true,
            nearbyWifiPermission = true, defaultTransport = Transport.WIFI, wifiMetered = false,
        )
    }
}

enum class EnvironmentIssue(val blocking: Boolean, val title: String, val message: String) {
    WIFI_OFF(true, "Wi-Fi is off", "Turn on Wi-Fi to diagnose your network."),
    NOT_CONNECTED(true, "Not connected", "Connect to your Wi-Fi network, then run the diagnosis."),
    PERMISSION_MISSING(
        false, "Limited details",
        "Without the location / nearby devices permission, Android hides the network name, BSSID and nearby networks.",
    ),
    LOCATION_OFF(
        false, "Location services are off",
        "Android needs location services on to show the network name and scan for nearby networks.",
    ),
    CELLULAR_DEFAULT(
        false, "Wi-Fi has no internet",
        "Android is sending traffic over mobile data because Wi-Fi failed its internet check. Tests stay on Wi-Fi.",
    ),
    ON_CELLULAR(false, "Using mobile data", "You are on mobile data. Connect to Wi-Fi to test it."),
    METERED(false, "Metered network", "This Wi-Fi is marked as metered; the speed test is skipped unless allowed in Settings."),
}
