package com.example.wifidoctor.platform

import com.example.wifidoctor.core.model.WifiStandard

/** Converts raw Android Wi-Fi values into domain values. Pure functions, unit tested on the JVM. */
object WifiMappers {
    private const val UNKNOWN_SSID = "<unknown ssid>"
    private const val REDACTED_BSSID = "02:00:00:00:00:00"

    /** WifiInfo wraps UTF-8 SSIDs in quotes and returns a placeholder when it is redacted. */
    fun ssid(raw: String?): String? {
        if (raw.isNullOrEmpty() || raw == UNKNOWN_SSID) return null
        return if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) raw.substring(1, raw.length - 1) else raw
    }

    fun bssid(raw: String?): String? =
        raw?.lowercase()?.takeIf { it.isNotBlank() && it != REDACTED_BSSID && it != "00:00:00:00:00:00" }

    /** WifiInfo returns -127 (or MIN_RSSI) when the value is invalid. */
    fun rssi(raw: Int): Int? = raw.takeIf { it in -126..-1 }

    fun linkSpeed(raw: Int): Int? = raw.takeIf { it > 0 }

    fun frequency(raw: Int): Int? = raw.takeIf { it > 0 }

    /** android.net.wifi.ScanResult.WIFI_STANDARD_* values. */
    fun standard(raw: Int): WifiStandard = when (raw) {
        1 -> WifiStandard.LEGACY
        4 -> WifiStandard.N
        5 -> WifiStandard.AC
        6 -> WifiStandard.AX
        8 -> WifiStandard.BE
        else -> WifiStandard.UNKNOWN // includes 0 (unknown) and 7 (11ad)
    }

    /** android.net.wifi.ScanResult.CHANNEL_WIDTH_* values to MHz. */
    fun channelWidthMhz(raw: Int): Int? = when (raw) {
        0 -> 20
        1 -> 40
        2 -> 80
        3 -> 160
        4 -> 160 // 80+80
        5 -> 320
        else -> null
    }

    /**
     * 802.11d Country information element (ID 7): the first two bytes are the ISO country code.
     * Returns null for missing/garbage values.
     */
    fun countryFromElement(bytes: ByteArray?): String? {
        if (bytes == null || bytes.size < 2) return null
        val code = String(bytes, 0, 2, Charsets.US_ASCII).uppercase()
        return code.takeIf { it.all { c -> c in 'A'..'Z' } }
    }

    const val ELEMENT_ID_COUNTRY = 7

    /** DhcpInfo stores IPv4 addresses as little-endian ints. */
    fun ipv4FromInt(value: Int): String? {
        if (value == 0) return null
        return listOf(value and 0xFF, value shr 8 and 0xFF, value shr 16 and 0xFF, value shr 24 and 0xFF).joinToString(".")
    }
}
