package com.example.wifidoctor.core.wifi

import com.example.wifidoctor.core.model.Band
import com.example.wifidoctor.core.model.LinkInfo
import com.example.wifidoctor.core.model.ScanEntry

/** A channel (primary 20 MHz channel + width) that could be configured on the router. */
data class ChannelOption(
    val channel: Int,
    val widthMhz: Int,
    /** Interference score: weighted count of neighbouring networks sharing the spectrum. Lower is better. */
    val score: Double,
    val dfs: Boolean = false,
    /** Channel numbers covered by the (bonded) channel, e.g. 36..48 for 36 at 80 MHz. */
    val span: IntRange = channel..channel,
) {
    val label: String
        get() = if (span.first == span.last) "$channel ($widthMhz MHz)" else "$channel ($widthMhz MHz, ${span.first}–${span.last})"
}

/** Per-band channel survey and recommendation. */
data class BandAnalysis(
    val band: Band,
    /** Networks seen on this band, excluding your own router. */
    val neighbours: Int,
    /** Your router's channel on this band, if it was seen. */
    val current: ChannelOption?,
    /** Whether the current channel is permitted in the detected region (null if unknown/unreliable). */
    val currentAllowed: Boolean?,
    val recommended: ChannelOption?,
    val alternatives: List<ChannelOption>,
    /** True if switching to [recommended] is clearly worthwhile (or the current channel is not permitted). */
    val shouldChange: Boolean,
    /** Interference score per permitted primary channel, for the occupancy chart. */
    val occupancy: Map<Int, Double>,
    /** True if this is the band the phone is connected on. */
    val connected: Boolean,
)

data class ChannelAnalysis(val region: Region, val bands: List<BandAnalysis>) {
    fun band(band: Band): BandAnalysis? = bands.firstOrNull { it.band == band }
}

/**
 * Scores every channel the region permits on each band by how much it overlaps with nearby
 * networks (stronger neighbours count more), then recommends the quietest.
 */
object ChannelPlanner {

    /** Minimum improvement before advising a change: switching channels briefly disconnects everyone. */
    const val MIN_IMPROVEMENT = 1.0
    private const val DFS_PENALTY = 0.75
    private const val CH12_13_PENALTY = 0.5 // many US-market devices cannot see 12/13
    private val preferred24 = setOf(1, 6, 11)

    /** Strong neighbours interfere more: -70 dBm counts 1.0, -40 dBm 2.0, -95 dBm ~0.17. */
    fun weight(rssiDbm: Int): Double = ((rssiDbm + 100) / 30.0).coerceIn(0.1, 2.0)

    fun analyze(link: LinkInfo?, scan: List<ScanEntry>, region: Region): ChannelAnalysis {
        val ownSsid = link?.ssid
        val own = scan.filter { e ->
            (ownSsid != null && e.ssid == ownSsid) || (link?.bssid != null && e.bssid.equals(link.bssid, ignoreCase = true))
        }.toSet()
        val neighbours = scan.filter { it !in own }
        val bands = buildList {
            add(analyzeBand(Band.GHZ_2_4, link, own, neighbours, region))
            add(analyzeBand(Band.GHZ_5, link, own, neighbours, region))
            val uses6 = link?.band == Band.GHZ_6 || scan.any { it.band == Band.GHZ_6 }
            if (region.domain.channels6 != null && uses6) add(analyzeBand(Band.GHZ_6, link, own, neighbours, region))
        }
        return ChannelAnalysis(region, bands)
    }

    private fun analyzeBand(
        band: Band,
        link: LinkInfo?,
        own: Set<ScanEntry>,
        allNeighbours: List<ScanEntry>,
        region: Region,
    ): BandAnalysis {
        val neighbours = allNeighbours.filter { it.band == band }
        val connected = link?.band == band
        // Our router on this band: the connected link, else our SSID's strongest BSSID here.
        val connectedLink = link?.takeIf { connected && it.frequencyMhz != null }
        val currentChannel: Int?
        val currentWidth: Int?
        if (connectedLink != null) {
            currentChannel = connectedLink.channel
            currentWidth = connectedLink.channelWidthMhz
        } else {
            val ownHere = own.filter { it.band == band }.maxByOrNull { it.rssiDbm }
            currentChannel = ownHere?.channel
            currentWidth = ownHere?.channelWidthMhz
        }

        val candidates = candidates(band, region.domain)
        val scored = candidates.map { (ch, width) -> score(band, ch, width, neighbours) }
        val ranked = scored.sortedWith(
            compareBy<ChannelOption> { it.score }
                .thenBy { if (band == Band.GHZ_2_4 && it.channel in preferred24) 0 else 1 }
                .thenBy { it.channel },
        )
        val best = ranked.firstOrNull()
        val current = currentChannel?.let { ch ->
            val width = (currentWidth ?: defaultWidth(band)).coerceAtLeast(20)
            score(band, ch, width, neighbours, applyPenalties = false)
        }
        val currentAllowed = if (current == null || !region.reliable) null else isAllowed(band, current.channel, region.domain)
        val clearlyBetter = current != null && best != null && best.channel != current.channel &&
            current.score - best.score >= MIN_IMPROVEMENT && current.score >= best.score * 1.5
        val occupancy = candidates.map { it.first }.distinct().associateWith { ch ->
            neighbours.filter { WifiMath.overlaps(frequencyOf(band, ch), 20, it.frequencyMhz, it.channelWidthMhz) }
                .sumOf { weight(it.rssiDbm) }
        }
        return BandAnalysis(
            band = band,
            neighbours = neighbours.size,
            current = current,
            currentAllowed = currentAllowed,
            recommended = best,
            alternatives = ranked.drop(1).filter { it.channel != current?.channel }.take(2),
            shouldChange = clearlyBetter || currentAllowed == false,
            occupancy = occupancy,
            connected = connected,
        )
    }

    fun isAllowed(band: Band, channel: Int, domain: RegDomain): Boolean = when (band) {
        Band.GHZ_2_4 -> domain.allowed24(channel)
        Band.GHZ_5 -> domain.allowed5(channel)
        Band.GHZ_6 -> domain.allowed6(channel)
        Band.UNKNOWN -> false
    }

    private fun defaultWidth(band: Band) = if (band == Band.GHZ_2_4) 20 else 80

    /**
     * Candidate (primary channel, width) pairs. 2.4 GHz uses 20 MHz (wider is not neighbour-friendly).
     * 5/6 GHz use the widest of 80/40/20 MHz whose whole block is permitted, so e.g. a lone channel 165
     * is offered at 20 MHz while 36 gets 80 MHz.
     */
    fun candidates(band: Band, domain: RegDomain): List<Pair<Int, Int>> = when (band) {
        Band.GHZ_2_4 -> domain.channels24.map { it to 20 }
        Band.GHZ_5 -> domain.channels5.map { ch -> ch to widestAllowed(band, ch, domain) }
        Band.GHZ_6 -> domain.channels6?.filter { (it - 5) % 16 == 0 } // preferred scanning channels
            ?.map { it to widestAllowed(band, it, domain) }.orEmpty()
        Band.UNKNOWN -> emptyList()
    }

    private fun widestAllowed(band: Band, channel: Int, domain: RegDomain): Int =
        listOf(80, 40).firstOrNull { width -> blockChannels(band, channel, width).all { isAllowed(band, it, domain) } } ?: 20

    /** 20 MHz channel numbers inside the bonded block containing [channel]. */
    fun blockChannels(band: Band, channel: Int, widthMhz: Int): List<Int> {
        val range = WifiMath.occupiedRange(frequencyOf(band, channel), widthMhz)
        val step = 4 // 20 MHz in channel numbers on 5/6 GHz
        val first = WifiMath.channelFor(range.first + 10) ?: return listOf(channel)
        return (0 until widthMhz / 20).map { first + it * step }
    }

    fun frequencyOf(band: Band, channel: Int): Int = when (band) {
        Band.GHZ_2_4 -> if (channel == 14) 2484 else 2407 + channel * 5
        Band.GHZ_5 -> 5000 + channel * 5
        Band.GHZ_6 -> if (channel == 2) 5935 else 5950 + channel * 5
        Band.UNKNOWN -> 0
    }

    private fun score(band: Band, channel: Int, widthMhz: Int, neighbours: List<ScanEntry>, applyPenalties: Boolean = true): ChannelOption {
        val freq = frequencyOf(band, channel)
        var score = 0.0
        for (n in neighbours) {
            if (!WifiMath.overlaps(freq, widthMhz, n.frequencyMhz, n.channelWidthMhz)) continue
            val factor = when {
                n.channel == channel -> 1.0 // co-channel: shares airtime politely
                band == Band.GHZ_2_4 -> 1.5 // partial overlap on 2.4 GHz: raw interference, worst case
                else -> 0.8 // secondary-channel overlap of bonded 5/6 GHz channels
            }
            score += weight(n.rssiDbm) * factor
        }
        val dfs = band == Band.GHZ_5 && blockChannels(band, channel, widthMhz).any { RegDomain.isDfs(it) }
        if (applyPenalties) {
            if (dfs) score += DFS_PENALTY
            if (band == Band.GHZ_2_4 && channel >= 12) score += CH12_13_PENALTY
        }
        val block = if (band == Band.GHZ_2_4) listOf(channel) else blockChannels(band, channel, widthMhz)
        return ChannelOption(channel, widthMhz, score, dfs, block.first()..block.last())
    }
}
