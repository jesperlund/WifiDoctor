package com.example.wifidoctor.platform

import android.content.Context
import android.telephony.TelephonyManager
import com.example.wifidoctor.core.wifi.RegionHints
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Country hints for Wi-Fi channel rules. None of these need a permission: the mobile network's
 * country reflects where the phone physically is; the SIM and locale are weaker fallbacks.
 * Access points' own country elements are added later from the Wi-Fi scan.
 */
fun interface RegionHintsProvider {
    fun hints(override: String?): RegionHints
}

@Singleton
class AndroidRegionHintsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : RegionHintsProvider {
    override fun hints(override: String?): RegionHints {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return RegionHints(
            override = override?.takeIf { it.isNotBlank() },
            networkCountry = runCatching { telephony?.networkCountryIso }.getOrNull(),
            simCountry = runCatching { telephony?.simCountryIso }.getOrNull(),
            localeCountry = Locale.getDefault().country,
        )
    }
}
