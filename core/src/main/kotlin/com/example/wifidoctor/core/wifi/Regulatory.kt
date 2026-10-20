package com.example.wifidoctor.core.wifi

import java.util.Locale

/**
 * Simplified Wi-Fi regulatory domains for home (indoor) use: which 20 MHz channels may be used
 * on each band. Based on FCC, ISED, ETSI/CEPT, Ofcom, MIC, MIIT, ACMA, WPC and MSIT rules; edge
 * cases (outdoor limits, power levels, very recent changes) are deliberately ignored.
 */
enum class RegDomain(
    val label: String,
    val channels24: IntRange,
    val channels5: List<Int>,
    /** Allowed 6 GHz channel numbers, or null if 6 GHz Wi-Fi is not permitted. */
    val channels6: IntRange?,
) {
    FCC("United States (FCC)", 1..11, UNII1 + UNII2A + UNII2C_144 + UNII3, 1..233),
    ISED("Canada (ISED)", 1..11, UNII1 + UNII2A + (UNII2C_144 - listOf(120, 124, 128)) + UNII3, 1..233),
    ETSI("Europe (ETSI)", 1..13, UNII1 + UNII2A + UNII2C_140, 1..93),
    UK("United Kingdom (Ofcom)", 1..13, UNII1 + UNII2A + UNII2C_140 + UNII3, 1..93),
    JAPAN("Japan (MIC)", 1..13, UNII1 + UNII2A + UNII2C_144, 1..93),
    CHINA("China (MIIT)", 1..13, UNII1 + UNII2A + UNII3, null),
    AUSTRALIA("Australia / New Zealand (ACMA)", 1..13, UNII1 + UNII2A + (UNII2C_144 - listOf(120, 124, 128)) + UNII3, 1..93),
    INDIA("India (WPC)", 1..13, UNII1 + UNII2A + UNII3, null),
    KOREA("South Korea (MSIT)", 1..13, UNII1 + UNII2A + UNII2C_144 + UNII3, 1..233),

    /** Unknown country: only channels that are legal practically everywhere. */
    WORLD("Unknown region (conservative)", 1..11, UNII1, null);

    fun allowed24(channel: Int) = channel in channels24
    fun allowed5(channel: Int) = channel in channels5
    fun allowed6(channel: Int) = channels6?.contains(channel) == true

    companion object {
        private val etsiCountries = setOf(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT", "LV", "LT",
            "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE", // EU
            "IS", "LI", "NO", "CH", "TR", "RS", "ME", "MK", "AL", "BA", "MD", "AD", "MC", "SM", "VA", "FO", "GL",
        )

        fun forCountry(iso: String?): RegDomain = when (val c = iso?.uppercase()) {
            null -> WORLD
            "US", "PR", "GU", "VI", "AS", "MP", "UM", "MX", "BR" -> FCC
            "CA" -> ISED
            "GB", "IM", "JE", "GG" -> UK
            "JP" -> JAPAN
            "CN" -> CHINA
            "AU", "NZ" -> AUSTRALIA
            "IN" -> INDIA
            "KR" -> KOREA
            in etsiCountries -> ETSI
            else -> WORLD
        }

        /** Radar-sharing channels: the router must listen for radar first and vacate if it hears any. */
        fun isDfs(channel5: Int) = channel5 in 52..144
    }
}

private val UNII1 = listOf(36, 40, 44, 48)
private val UNII2A = listOf(52, 56, 60, 64)
private val UNII2C_140 = (100..140 step 4).toList()
private val UNII2C_144 = (100..144 step 4).toList()
private val UNII3 = listOf(149, 153, 157, 161, 165)

/** Where the country used for channel rules came from, most to least reliable. */
enum class RegionSource(val label: String) {
    SETTINGS("set in Settings"),
    ACCESS_POINTS("nearby access points"),
    MOBILE_NETWORK("mobile network"),
    SIM("SIM card"),
    DEVICE_LOCALE("device region setting"),
    UNKNOWN("not detected"),
}

/** Country hints gathered by the platform (all ISO 3166 alpha-2, any case, blank = unknown). */
data class RegionHints(
    val override: String? = null,
    val networkCountry: String? = null,
    val simCountry: String? = null,
    val localeCountry: String? = null,
)

data class Region(val countryCode: String?, val domain: RegDomain, val source: RegionSource) {
    val countryName: String?
        get() = countryCode?.let { Locale("", it).getDisplayCountry(Locale.ENGLISH).takeIf { n -> n.isNotBlank() && n != it } }

    /** True when the country is known well enough to call a channel "not permitted". */
    val reliable: Boolean get() = domain != RegDomain.WORLD && source != RegionSource.UNKNOWN

    val description: String
        get() = when {
            countryCode == null -> "Region unknown, using channels allowed everywhere"
            domain == RegDomain.WORLD -> "${countryName ?: countryCode} (${source.label}), rules not known, using channels allowed everywhere"
            else -> "${countryName ?: countryCode} (${source.label}) · ${domain.label}"
        }
}

object RegionResolver {
    private val bogus = setOf("XX", "ZZ", "00", "AA")

    fun normalize(code: String?): String? =
        code?.trim()?.uppercase()?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } && it !in bogus }

    /**
     * Picks the country in order of reliability: explicit setting, the 802.11d country element
     * broadcast by nearby access points (where you physically are), the mobile network, the SIM,
     * then the device locale.
     */
    fun resolve(hints: RegionHints, accessPointCountries: List<String?>): Region {
        normalize(hints.override)?.let { return Region(it, RegDomain.forCountry(it), RegionSource.SETTINGS) }
        val apVote = accessPointCountries.mapNotNull { normalize(it) }
            .groupingBy { it }.eachCount()
            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            ?.key
        apVote?.let { return Region(it, RegDomain.forCountry(it), RegionSource.ACCESS_POINTS) }
        normalize(hints.networkCountry)?.let { return Region(it, RegDomain.forCountry(it), RegionSource.MOBILE_NETWORK) }
        normalize(hints.simCountry)?.let { return Region(it, RegDomain.forCountry(it), RegionSource.SIM) }
        normalize(hints.localeCountry)?.let { return Region(it, RegDomain.forCountry(it), RegionSource.DEVICE_LOCALE) }
        return Region(null, RegDomain.WORLD, RegionSource.UNKNOWN)
    }
}
