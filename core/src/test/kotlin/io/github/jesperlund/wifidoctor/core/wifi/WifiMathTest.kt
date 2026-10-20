package io.github.jesperlund.wifidoctor.core.wifi

import io.github.jesperlund.wifidoctor.core.Fixtures
import io.github.jesperlund.wifidoctor.core.Fixtures.scanEntry
import io.github.jesperlund.wifidoctor.core.model.Band
import io.github.jesperlund.wifidoctor.core.model.WifiStandard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiMathTest {

    @Test
    fun bands() {
        assertEquals(Band.GHZ_2_4, WifiMath.bandFor(2412))
        assertEquals(Band.GHZ_5, WifiMath.bandFor(5180))
        assertEquals(Band.GHZ_5, WifiMath.bandFor(5825))
        assertEquals(Band.GHZ_6, WifiMath.bandFor(5955))
        assertEquals(Band.GHZ_6, WifiMath.bandFor(7115))
        assertEquals(Band.UNKNOWN, WifiMath.bandFor(null))
        assertEquals(Band.UNKNOWN, WifiMath.bandFor(60_000))
    }

    @Test
    fun channels() {
        assertEquals(1, WifiMath.channelFor(2412))
        assertEquals(6, WifiMath.channelFor(2437))
        assertEquals(13, WifiMath.channelFor(2472))
        assertEquals(14, WifiMath.channelFor(2484))
        assertEquals(36, WifiMath.channelFor(5180))
        assertEquals(149, WifiMath.channelFor(5745))
        assertEquals(1, WifiMath.channelFor(5955))
        assertEquals(2, WifiMath.channelFor(5935))
        assertEquals(233, WifiMath.channelFor(7115))
        assertNull(WifiMath.channelFor(null))
    }

    @Test
    fun `2_4 GHz channels within four of each other overlap, 1 6 11 do not`() {
        assertTrue(WifiMath.overlaps(2412, 20, 2432, 20)) // 1 and 5
        assertFalse(WifiMath.overlaps(2412, 20, 2437, 20)) // 1 and 6
        assertFalse(WifiMath.overlaps(2437, 20, 2462, 20)) // 6 and 11
        assertTrue(WifiMath.overlaps(2437, 20, 2447, 20)) // 6 and 8
    }

    @Test
    fun `5 GHz bonded channels overlap within their block`() {
        assertTrue(WifiMath.overlaps(5180, 80, 5240, 20)) // 36/80 covers 48
        assertFalse(WifiMath.overlaps(5180, 80, 5260, 20)) // 52 is the next block
        assertFalse(WifiMath.overlaps(5180, 20, 5200, 20)) // 36 and 40 at 20 MHz
        assertTrue(WifiMath.overlaps(5180, 20, 5200, 40)) // 40/40 covers 36
        assertTrue(WifiMath.overlaps(5745, 80, 5805, 20)) // UNII-3 block 149-161
        assertFalse(WifiMath.overlaps(5745, 80, 5825, 20)) // 165 is outside it
        assertFalse(WifiMath.overlaps(2412, 20, 5180, 20)) // different bands
    }

    @Test
    fun `congestion counts same and overlapping channels and ignores our own BSSID`() {
        val link = Fixtures.link24()
        val scan = listOf(
            scanEntry(bssid = Fixtures.BSSID_24, freq = 2437), // us
            scanEntry(bssid = "01", freq = 2437),
            scanEntry(bssid = "02", freq = 2437),
            scanEntry(bssid = "03", freq = 2442), // ch 7 overlaps
            scanEntry(bssid = "04", freq = 2462), // ch 11 clear
            scanEntry(bssid = "05", freq = 5180),
        )
        val c = WifiMath.congestion(link, scan)!!
        assertEquals(2, c.sameChannel)
        assertEquals(1, c.overlapping)
        assertEquals(5, c.totalVisible)
    }

    @Test
    fun `congestion is unknown without a frequency`() {
        assertNull(WifiMath.congestion(Fixtures.link(frequency = null), emptyList()))
    }

    @Test
    fun `typical link speeds`() {
        assertEquals(144, WifiMath.typicalMaxLinkSpeed(Band.GHZ_2_4, WifiStandard.N, 20))
        assertEquals(867, WifiMath.typicalMaxLinkSpeed(Band.GHZ_5, WifiStandard.AC, 80))
        assertEquals(1201, WifiMath.typicalMaxLinkSpeed(Band.GHZ_5, WifiStandard.AX, null))
        assertEquals(287, WifiMath.typicalMaxLinkSpeed(Band.GHZ_2_4, WifiStandard.AX, null))
        assertEquals(54, WifiMath.typicalMaxLinkSpeed(Band.GHZ_2_4, WifiStandard.LEGACY, null))
        assertNull(WifiMath.typicalMaxLinkSpeed(Band.UNKNOWN, WifiStandard.UNKNOWN, null))
    }

    @Test
    fun `signal quality labels and bars`() {
        assertEquals(SignalQuality.EXCELLENT, SignalQuality.of(-40))
        assertEquals(SignalQuality.GOOD, SignalQuality.of(-60))
        assertEquals(SignalQuality.FAIR, SignalQuality.of(-70))
        assertEquals(SignalQuality.WEAK, SignalQuality.of(-75))
        assertEquals(SignalQuality.VERY_WEAK, SignalQuality.of(-90))
        assertEquals(SignalQuality.UNKNOWN, SignalQuality.of(null))
        assertEquals(0f, SignalQuality.gaugeFraction(-120), 0f)
        assertEquals(1f, SignalQuality.gaugeFraction(-20), 0f)
        assertEquals(0.5f, SignalQuality.gaugeFraction(-65), 0.001f)
    }
}
