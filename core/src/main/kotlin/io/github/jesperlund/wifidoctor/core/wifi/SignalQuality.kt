package io.github.jesperlund.wifidoctor.core.wifi

/** Human label + bar count for an RSSI value, so status never relies on colour alone. */
enum class SignalQuality(val label: String, val bars: Int) {
    EXCELLENT("Excellent", 4),
    GOOD("Good", 3),
    FAIR("Fair", 2),
    WEAK("Weak", 1),
    VERY_WEAK("Very weak", 0),
    UNKNOWN("Unknown", 0);

    companion object {
        fun of(rssiDbm: Int?): SignalQuality = when {
            rssiDbm == null -> UNKNOWN
            rssiDbm >= -55 -> EXCELLENT
            rssiDbm >= -65 -> GOOD
            rssiDbm >= -70 -> FAIR
            rssiDbm >= -80 -> WEAK
            else -> VERY_WEAK
        }

        /** 0..1 position of [rssiDbm] on a -100..-30 dBm gauge. */
        fun gaugeFraction(rssiDbm: Int?): Float =
            if (rssiDbm == null) 0f else ((rssiDbm + 100) / 70f).coerceIn(0f, 1f)
    }
}
