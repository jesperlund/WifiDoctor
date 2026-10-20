package com.example.wifidoctor.core.wifi

import com.example.wifidoctor.core.Fixtures
import com.example.wifidoctor.core.Fixtures.scanEntry
import com.example.wifidoctor.core.diagnosis.DiagnoserConfig
import com.example.wifidoctor.core.diagnosis.Rules
import com.example.wifidoctor.core.model.Band
import com.example.wifidoctor.core.model.CongestionResult
import com.example.wifidoctor.core.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegulatoryTest {

    @Test
    fun `countries map to regulatory domains`() {
        assertEquals(RegDomain.FCC, RegDomain.forCountry("us"))
        assertEquals(RegDomain.ETSI, RegDomain.forCountry("DE"))
        assertEquals(RegDomain.ETSI, RegDomain.forCountry("NO"))
        assertEquals(RegDomain.UK, RegDomain.forCountry("GB"))
        assertEquals(RegDomain.JAPAN, RegDomain.forCountry("JP"))
        assertEquals(RegDomain.ISED, RegDomain.forCountry("CA"))
        assertEquals(RegDomain.WORLD, RegDomain.forCountry("QQ"))
        assertEquals(RegDomain.WORLD, RegDomain.forCountry(null))
    }

    @Test
    fun `channel tables follow the main regional rules`() {
        assertFalse("12/13 are not allowed in the US", RegDomain.FCC.allowed24(12))
        assertTrue(RegDomain.ETSI.allowed24(13))
        assertFalse("14 is excluded everywhere (11b only, Japan)", RegDomain.JAPAN.allowed24(14))
        assertTrue(RegDomain.FCC.allowed5(149))
        assertFalse("UNII-3 is not a normal Wi-Fi band under ETSI", RegDomain.ETSI.allowed5(149))
        assertTrue("Ofcom allows 5.8 GHz indoors", RegDomain.UK.allowed5(149))
        assertFalse(RegDomain.ETSI.allowed5(144))
        assertTrue(RegDomain.FCC.allowed5(144))
        assertFalse("Canada protects weather radar on 120-128", RegDomain.ISED.allowed5(124))
        assertFalse(RegDomain.CHINA.allowed5(100))
        assertTrue(RegDomain.FCC.allowed6(229))
        assertFalse("Europe only has the lower 6 GHz band", RegDomain.ETSI.allowed6(101))
        assertNull(RegDomain.CHINA.channels6)
        assertTrue(RegDomain.isDfs(52))
        assertTrue(RegDomain.isDfs(144))
        assertFalse(RegDomain.isDfs(48))
        assertFalse(RegDomain.isDfs(149))
    }

    @Test
    fun `region resolution prefers settings, then access points, then telephony, then locale`() {
        val hints = RegionHints(override = null, networkCountry = "us", simCountry = "ca", localeCountry = "GB")
        assertEquals(RegionSource.SETTINGS, RegionResolver.resolve(hints.copy(override = "jp"), listOf("DE")).source)
        val ap = RegionResolver.resolve(hints, listOf("DE", "DE", "FR", null, "XX"))
        assertEquals("DE", ap.countryCode)
        assertEquals(RegionSource.ACCESS_POINTS, ap.source)
        assertEquals(RegDomain.ETSI, ap.domain)
        assertEquals(RegionSource.MOBILE_NETWORK, RegionResolver.resolve(hints, emptyList()).source)
        assertEquals("CA", RegionResolver.resolve(hints.copy(networkCountry = ""), emptyList()).countryCode)
        assertEquals(RegionSource.DEVICE_LOCALE, RegionResolver.resolve(RegionHints(localeCountry = "gb"), emptyList()).source)
        val none = RegionResolver.resolve(RegionHints(override = "Germany"), listOf("00"))
        assertEquals(RegDomain.WORLD, none.domain)
        assertFalse(none.reliable)
    }

    @Test
    fun `region descriptions`() {
        val de = Region("DE", RegDomain.ETSI, RegionSource.ACCESS_POINTS)
        assertEquals("Germany", de.countryName)
        assertTrue(de.description.contains("Germany (nearby access points)"))
        assertTrue(Region(null, RegDomain.WORLD, RegionSource.UNKNOWN).description.startsWith("Region unknown"))
    }
}

class ChannelPlannerTest {
    private val us = Region("US", RegDomain.FCC, RegionSource.ACCESS_POINTS)
    private val de = Region("DE", RegDomain.ETSI, RegionSource.ACCESS_POINTS)

    private fun n(freq: Int, rssi: Int = -60, width: Int = 20, id: Int = freq) =
        scanEntry(ssid = "N$id", bssid = "nb:$id:$rssi", freq = freq, rssi = rssi, width = width)

    private val ownRouter24 = scanEntry(ssid = Fixtures.SSID, bssid = Fixtures.BSSID_24, freq = 2462, rssi = -45, width = 20) // ch 11

    @Test
    fun `2_4 GHz is analysed even when connected on 5 GHz`() {
        val scan = listOf(ownRouter24, n(2462, id = 1), n(2462, id = 2), n(2462, id = 3), n(2457, id = 4), n(2437, rssi = -85))
        val a = ChannelPlanner.analyze(Fixtures.link(), scan, us)
        val b24 = a.band(Band.GHZ_2_4)!!
        assertFalse(b24.connected)
        assertEquals(11, b24.current!!.channel)
        assertEquals(5, b24.neighbours)
        assertEquals(1, b24.recommended!!.channel)
        assertTrue(b24.shouldChange)
        assertEquals(true, b24.currentAllowed)
        assertTrue(a.band(Band.GHZ_5)!!.connected)
    }

    @Test
    fun `empty 2_4 GHz band prefers 1, 6 or 11`() {
        val b24 = ChannelPlanner.analyze(Fixtures.link(), emptyList(), us).band(Band.GHZ_2_4)!!
        assertEquals(1, b24.recommended!!.channel)
        assertEquals(20, b24.recommended!!.widthMhz)
        assertNull(b24.current)
        assertFalse(b24.shouldChange)
    }

    @Test
    fun `partial overlap on 2_4 GHz counts more than sharing a channel`() {
        // One neighbour on ch 6, one on ch 3 (overlapping 1 and 6). Channel 11 is clean.
        val b24 = ChannelPlanner.analyze(null, listOf(n(2437), n(2422)), us).band(Band.GHZ_2_4)!!
        assertEquals(11, b24.recommended!!.channel)
        assertTrue(b24.occupancy.getValue(4) > b24.occupancy.getValue(11))
    }

    @Test
    fun `channels 12 and 13 are only suggested where permitted`() {
        // Lower and middle of the band crowded, one faint network on 11: only 13 is really clear.
        val busy = listOf(1, 4, 6, 8).flatMap { ch -> List(3) { i -> n(2407 + ch * 5, rssi = -50, id = ch * 10 + i) } } +
            n(2462, rssi = -95, id = 99)
        val usBest = ChannelPlanner.analyze(null, busy, us).band(Band.GHZ_2_4)!!.recommended!!.channel
        val deBest = ChannelPlanner.analyze(null, busy, de).band(Band.GHZ_2_4)!!.recommended!!.channel
        assertTrue(usBest <= 11)
        assertEquals(13, deBest)
    }

    @Test
    fun `5 GHz suggestion uses the widest permitted block and avoids DFS when it can`() {
        val scan = listOf(n(5180, rssi = -50, width = 80), n(5200, rssi = -55, width = 80))
        val b5 = ChannelPlanner.analyze(null, scan, us).band(Band.GHZ_5)!!
        val best = b5.recommended!!
        assertEquals(149, best.channel)
        assertEquals(80, best.widthMhz)
        assertEquals(149..161, best.span)
        assertFalse(best.dfs)
        assertEquals("149 (80 MHz, 149–161)", best.label)
    }

    @Test
    fun `in Europe 5 GHz suggestions stay within ETSI channels`() {
        val scan = listOf(n(5180, rssi = -50, width = 80))
        val best = ChannelPlanner.analyze(null, scan, de).band(Band.GHZ_5)!!.recommended!!
        assertTrue(RegDomain.ETSI.allowed5(best.channel))
        assertTrue("only DFS blocks are free", best.dfs)
        assertEquals(80, best.widthMhz)
    }

    @Test
    fun `block widths shrink where the full block is not permitted`() {
        assertEquals(listOf(165 to 20), ChannelPlanner.candidates(Band.GHZ_5, RegDomain.FCC).filter { it.first == 165 })
        assertEquals(listOf(140 to 20), ChannelPlanner.candidates(Band.GHZ_5, RegDomain.ETSI).filter { it.first == 140 })
        assertEquals(listOf(36 to 80), ChannelPlanner.candidates(Band.GHZ_5, RegDomain.WORLD).filter { it.first == 36 })
        assertEquals(listOf(36, 40, 44, 48), ChannelPlanner.blockChannels(Band.GHZ_5, 44, 80))
        assertEquals(listOf(1, 5, 9, 13), ChannelPlanner.blockChannels(Band.GHZ_6, 5, 80))
    }

    @Test
    fun `channel not permitted in the region is flagged`() {
        val ownOn13 = scanEntry(ssid = Fixtures.SSID, bssid = Fixtures.BSSID_24, freq = 2472, rssi = -50)
        val b24 = ChannelPlanner.analyze(Fixtures.link(), listOf(ownOn13), us).band(Band.GHZ_2_4)!!
        assertEquals(false, b24.currentAllowed)
        assertTrue(b24.shouldChange)
        // ...but not when the region is a guess.
        val unknown = Region(null, RegDomain.WORLD, RegionSource.UNKNOWN)
        assertNull(ChannelPlanner.analyze(Fixtures.link(), listOf(ownOn13), unknown).band(Band.GHZ_2_4)!!.currentAllowed)
    }

    @Test
    fun `6 GHz is analysed only where permitted and in use`() {
        val six = n(5975, width = 80) // ch 5
        assertNotNull(ChannelPlanner.analyze(null, listOf(six), us).band(Band.GHZ_6))
        assertNull(ChannelPlanner.analyze(null, emptyList(), us).band(Band.GHZ_6))
        assertNull(ChannelPlanner.analyze(null, listOf(six), Region("CN", RegDomain.CHINA, RegionSource.SIM)).band(Band.GHZ_6))
    }

    @Test
    fun `small differences do not trigger a change`() {
        val scan = listOf(ownRouter24, n(2462, rssi = -85))
        assertFalse(ChannelPlanner.analyze(Fixtures.link(), scan, us).band(Band.GHZ_2_4)!!.shouldChange)
    }

    @Test
    fun weights() {
        assertEquals(1.0, ChannelPlanner.weight(-70), 1e-9)
        assertEquals(2.0, ChannelPlanner.weight(-30), 1e-9)
        assertEquals(0.1, ChannelPlanner.weight(-110), 1e-9)
    }
}

class ChannelPlanRuleTest {
    private val c = DiagnoserConfig()
    private val us = Region("US", RegDomain.FCC, RegionSource.ACCESS_POINTS)

    private fun crowded24(ownFreq: Int = 2462, ownWidth: Int = 20): List<com.example.wifidoctor.core.model.ScanEntry> =
        listOf(scanEntry(ssid = Fixtures.SSID, bssid = Fixtures.BSSID_24, freq = ownFreq, rssi = -45, width = ownWidth)) +
            List(4) { scanEntry(ssid = "N$it", bssid = "n$it", freq = 2462, rssi = -55) }

    @Test
    fun `suggests a better 2_4 GHz channel with the region`() {
        val m = Fixtures.healthy(channels = ChannelPlanner.analyze(Fixtures.link(), crowded24(), us))
        val f = Rules.ChannelPlanRule.evaluate(m, c)!!
        assertEquals(Severity.WARN, f.severity)
        assertEquals("Better Wi-Fi channel available", f.title)
        assertTrue(f.explanation.contains("2.4 GHz: your router uses channel 11"))
        assertTrue(f.fixes.first().contains("2.4 GHz channel to 1 (20 MHz), which is permitted in United States"))
    }

    @Test
    fun `flags a channel that is not permitted`() {
        val m = Fixtures.healthy(channels = ChannelPlanner.analyze(Fixtures.link(), crowded24(ownFreq = 2472), us))
        val f = Rules.ChannelPlanRule.evaluate(m, c)!!
        assertEquals("Router uses a channel not permitted here", f.title)
        assertTrue(f.explanation.contains("channel 13 is not permitted in United States"))
    }

    @Test
    fun `flags 40 MHz on a busy 2_4 GHz band`() {
        val scan = listOf(scanEntry(ssid = Fixtures.SSID, bssid = Fixtures.BSSID_24, freq = 2412, rssi = -45, width = 40)) +
            List(3) { scanEntry(ssid = "N$it", bssid = "n$it", freq = 2462, rssi = -85) }
        val m = Fixtures.healthy(channels = ChannelPlanner.analyze(Fixtures.link(), scan, us))
        val f = Rules.ChannelPlanRule.evaluate(m, c)!!
        assertTrue(f.fixes.contains("In the router's settings, set the 2.4 GHz channel width to 20 MHz."))
    }

    @Test
    fun `quiet bands produce no finding`() {
        val m = Fixtures.healthy(channels = ChannelPlanner.analyze(Fixtures.link(), emptyList(), us))
        assertNull(Rules.ChannelPlanRule.evaluate(m, c))
    }

    @Test
    fun `congestion advice names the suggested channel for the connected band`() {
        val link = Fixtures.link24()
        val scan = List(5) { scanEntry(ssid = "N$it", bssid = "n$it", freq = 2437, rssi = -55) }
        val m = Fixtures.healthy(link = link, congestion = CongestionResult(5, 0, 5), channels = ChannelPlanner.analyze(link, scan, us))
        val f = Rules.CongestionRule.evaluate(m, c)!!
        assertTrue(f.fixes.first(), f.fixes.first().startsWith("In the router's settings, set the 2.4 GHz channel to 1"))
    }
}
