package com.example.wifidoctor.core.wifi

import com.example.wifidoctor.core.model.Band
import com.example.wifidoctor.core.model.CongestionResult
import com.example.wifidoctor.core.model.LinkInfo
import com.example.wifidoctor.core.model.ScanEntry
import com.example.wifidoctor.core.model.WifiStandard

/** Frequency/channel arithmetic for 2.4, 5 and 6 GHz Wi-Fi. */
object WifiMath {

    fun bandFor(frequencyMhz: Int?): Band = when (frequencyMhz) {
        null -> Band.UNKNOWN
        in 2400..2500 -> Band.GHZ_2_4
        in 4900..5924 -> Band.GHZ_5
        in 5925..7125 -> Band.GHZ_6
        else -> Band.UNKNOWN
    }

    fun channelFor(frequencyMhz: Int?): Int? {
        val f = frequencyMhz ?: return null
        return when (bandFor(f)) {
            Band.GHZ_2_4 -> if (f == 2484) 14 else (f - 2407) / 5
            Band.GHZ_5 -> if (f < 5000) (f - 4000) / 5 else (f - 5000) / 5
            Band.GHZ_6 -> if (f == 5935) 2 else (f - 5950) / 5
            Band.UNKNOWN -> null
        }
    }

    /**
     * Frequency range (MHz) occupied by a network. For 5/6 GHz bonded channels the block is aligned
     * to the standard channelisation, so the primary channel alone is enough to locate it.
     */
    fun occupiedRange(frequencyMhz: Int, widthMhz: Int?): IntRange {
        val width = (widthMhz ?: 20).coerceAtLeast(20)
        return when (bandFor(frequencyMhz)) {
            // 2.4 GHz channels are ~22 MHz wide on a 5 MHz grid; a 40 MHz network is treated as extending upwards.
            Band.GHZ_2_4 -> (frequencyMhz - 11) until (frequencyMhz - 11 + width + 2)
            Band.GHZ_5, Band.GHZ_6 -> {
                // Bonded blocks start at ch36 (5170 MHz), restart at ch149 (5735 MHz) in UNII-3, and at ch1 in 6 GHz.
                val base = when {
                    bandFor(frequencyMhz) == Band.GHZ_6 -> 5945
                    frequencyMhz >= 5745 -> 5735
                    else -> 5170
                }
                val offset = frequencyMhz - 10 - base
                val start = base + Math.floorDiv(offset, width) * width
                start until start + width
            }
            Band.UNKNOWN -> frequencyMhz until frequencyMhz + 1
        }
    }

    fun sameChannel(a: Int, b: Int): Boolean = bandFor(a) == bandFor(b) && channelFor(a) == channelFor(b)

    fun overlaps(aFreq: Int, aWidth: Int?, bFreq: Int, bWidth: Int?): Boolean {
        if (bandFor(aFreq) != bandFor(bFreq) || bandFor(aFreq) == Band.UNKNOWN) return false
        val a = occupiedRange(aFreq, aWidth)
        val b = occupiedRange(bFreq, bWidth)
        return a.first <= b.last && b.first <= a.last
    }

    /**
     * Counts neighbours that share or overlap our channel. Access points belonging to our own network
     * (same BSSID) are ignored.
     */
    fun congestion(link: LinkInfo, scan: List<ScanEntry>): CongestionResult? {
        val freq = link.frequencyMhz ?: return null
        val others = scan.filter { it.bssid == null || !it.bssid.equals(link.bssid, ignoreCase = true) }
        var same = 0
        var overlapping = 0
        for (entry in others) {
            when {
                sameChannel(freq, entry.frequencyMhz) -> same++
                overlaps(freq, link.channelWidthMhz, entry.frequencyMhz, entry.channelWidthMhz) -> overlapping++
            }
        }
        return CongestionResult(sameChannel = same, overlapping = overlapping, totalVisible = others.size)
    }

    /**
     * Typical maximum PHY rate (Mbps) for a two-stream client, which is what most phones are.
     * Used to judge whether the negotiated link speed is unusually low.
     */
    fun typicalMaxLinkSpeed(band: Band, standard: WifiStandard, widthMhz: Int?): Int? {
        val width = widthMhz ?: defaultWidth(band, standard)
        return when (standard) {
            WifiStandard.LEGACY -> 54
            WifiStandard.N -> if (width >= 40) 300 else 144
            WifiStandard.AC -> when {
                width >= 160 -> 1733
                width >= 80 -> 867
                width >= 40 -> 400
                else -> 173
            }
            WifiStandard.AX -> when {
                width >= 160 -> 2402
                width >= 80 -> 1201
                width >= 40 -> 574
                else -> 287
            }
            WifiStandard.BE -> when {
                width >= 320 -> 5765
                width >= 160 -> 2882
                width >= 80 -> 1441
                width >= 40 -> 688
                else -> 344
            }
            WifiStandard.UNKNOWN -> when (band) {
                Band.GHZ_2_4 -> 144
                Band.GHZ_5 -> 867
                Band.GHZ_6 -> 1201
                Band.UNKNOWN -> null
            }
        }
    }

    private fun defaultWidth(band: Band, standard: WifiStandard): Int = when {
        band == Band.GHZ_2_4 -> 20
        standard == WifiStandard.N -> 40
        else -> 80
    }
}
