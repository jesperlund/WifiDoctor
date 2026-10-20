package io.github.jesperlund.wifidoctor.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

object AppPermissions {
    /** Runtime permissions needed for SSID/BSSID and Wi-Fi scans on this device. */
    fun wifiPermissions(sdk: Int = Build.VERSION.SDK_INT): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION) // Android 12+ requires asking for both together
        if (sdk >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    fun notificationPermission(sdk: Int = Build.VERSION.SDK_INT): String? =
        if (sdk >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
}

@Singleton
class PermissionChecker @Inject constructor(@ApplicationContext private val context: Context) {
    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasLocation(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasNearbyWifi(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.NEARBY_WIFI_DEVICES)

    /** startScan()/getScanResults() need fine location (and nearby devices on 13+). */
    fun canScan(): Boolean = hasLocation() && hasNearbyWifi()

    fun canNotify(): Boolean = AppPermissions.notificationPermission()?.let { granted(it) } ?: true
}
