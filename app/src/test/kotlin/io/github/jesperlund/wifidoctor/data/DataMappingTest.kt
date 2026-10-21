package io.github.jesperlund.wifidoctor.data

import io.github.jesperlund.wifidoctor.core.history.RunRecord
import io.github.jesperlund.wifidoctor.core.model.Band
import io.github.jesperlund.wifidoctor.core.model.Layer
import io.github.jesperlund.wifidoctor.core.model.Severity
import io.github.jesperlund.wifidoctor.core.model.WifiStandard
import io.github.jesperlund.wifidoctor.data.db.toEntity
import io.github.jesperlund.wifidoctor.data.db.toRecord
import io.github.jesperlund.wifidoctor.platform.AppPermissions
import io.github.jesperlund.wifidoctor.platform.WifiMappers
import io.github.jesperlund.wifidoctor.ui.history.ChartMath
import io.github.jesperlund.wifidoctor.ui.settings.intervalLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataMappingTest {

    @Test
    fun `run record survives a round trip through the entity`() {
        val r = RunRecord(
            id = 7, timestampMs = 123, roomLabel = "Office", ssid = "Home", bssid = "aa:bb", rssiDbm = -61,
            linkSpeedMbps = 400, frequencyMhz = 5180, band = Band.GHZ_5, channel = 36, channelWidthMhz = 80,
            standard = WifiStandard.AX, gatewayIp = "192.168.0.1", gatewayAvgMs = 3.5, gatewayLossPercent = 0.0,
            internetTarget = "1.1.1.1", internetAvgMs = 18.0, dnsSystemMs = 40.0, dnsReferenceMs = 15.0,
            throughputMbps = 210.5, sameChannelNetworks = 2, overlappingNetworks = 1, countryCode = "DE",
            routerChannel24 = 11, suggestedChannel24 = 1, routerChannel5 = 36, suggestedChannel5 = 100, suggestedWidth5 = 80,
            kind = io.github.jesperlund.wifidoctor.core.history.RunKind.CALL_MONITOR, uploadMbps = 12.5,
            loadedDownIncreaseMs = 40.0, loadedUpIncreaseMs = 220.0, udpRttMs = 25.0, udpJitterMs = 3.0, udpLossPercent = 0.0,
            natBehavior = "SYMMETRIC", internetP95Ms = 80.0, dropouts = 2, monitorDurationMs = 300_000,
            severity = Severity.WARN,
            layer = Layer.DNS, verdict = "Slow DNS", explanation = "why", fixes = listOf("one", "two"),
        )
        assertEquals(r, r.toEntity().toRecord())
    }

    @Test
    fun `unknown enum names fall back safely`() {
        val e = RunRecord(timestampMs = 1, severity = Severity.OK, verdict = "", explanation = "").toEntity()
            .copy(band = "GHZ_60", standard = "WIFI_9", severity = "???", layer = "NOPE", fixes = "")
        val r = e.toRecord()
        assertEquals(Band.UNKNOWN, r.band)
        assertEquals(WifiStandard.UNKNOWN, r.standard)
        assertEquals(Severity.OK, r.severity)
        assertNull(r.layer)
        assertTrue(r.fixes.isEmpty())
    }

    @Test
    fun `multi-line fixes are flattened so they stay one per line`() {
        val r = RunRecord(timestampMs = 1, severity = Severity.OK, verdict = "", explanation = "", fixes = listOf("a\nb", "c"))
        assertEquals(listOf("a b", "c"), r.toEntity().toRecord().fixes)
    }

    @Test
    fun `settings validation`() {
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), SettingsValidation.parseTargets("1.1.1.1, 8.8.8.8, 1.1.1.1"))
        assertEquals(listOf("dns.google"), SettingsValidation.parseTargets(" dns.google "))
        assertEquals(listOf("2606:4700:4700::1111"), SettingsValidation.parseTargets("2606:4700:4700::1111"))
        assertNull(SettingsValidation.parseTargets("999.1.1.1"))
        assertNull(SettingsValidation.parseTargets("bad_host!"))
        assertNull(SettingsValidation.parseTargets("a.com b.com c.com d.com e.com f.com"))
        assertTrue(SettingsValidation.isValidUrl("https://speed.cloudflare.com/__down?bytes=1000"))
        assertTrue(SettingsValidation.isValidUrl("http://192.168.1.10:8080/file.bin"))
        assertFalse(SettingsValidation.isValidUrl("ftp://example.com/file"))
        assertFalse(SettingsValidation.isValidUrl("not a url"))
        assertFalse(SettingsValidation.isValidUrl("https://"))
        assertTrue(SettingsValidation.isValidCountry(""))
        assertTrue(SettingsValidation.isValidCountry("gb"))
        assertFalse(SettingsValidation.isValidCountry("GBR"))
    }

    @Test
    fun `run config respects metered and background preferences`() {
        val s = AppSettings(probeTargets = listOf("9.9.9.9"))
        assertTrue(s.toRunConfig(metered = false).runThroughput)
        assertFalse(s.toRunConfig(metered = true).runThroughput)
        assertTrue(s.copy(speedTestOnMetered = true).toRunConfig(metered = true).runThroughput)
        assertFalse(s.toRunConfig(metered = false, background = true).runThroughput)
        assertTrue(s.copy(speedTestInBackground = true).toRunConfig(metered = false, background = true).runThroughput)
        assertEquals(listOf("9.9.9.9"), s.toRunConfig(false).internetTargets)
        assertEquals(AppSettings.DEFAULT_TARGETS, s.copy(probeTargets = emptyList()).toRunConfig(false).internetTargets)
        val custom = s.copy(uploadUrl = "https://up.example.com/post", udpTestEnabled = false).toRunConfig(false)
        assertEquals("https://up.example.com/post", custom.uploadUrl)
        assertFalse(custom.runRealtime)
        val hints = io.github.jesperlund.wifidoctor.core.wifi.RegionHints(override = "FR")
        assertEquals(hints, s.toRunConfig(false, regionHints = hints).regionHints)
    }

    @Test
    fun `wifi value mapping`() {
        assertEquals("Home Net", WifiMappers.ssid("\"Home Net\""))
        assertEquals("deadbeef", WifiMappers.ssid("deadbeef"))
        assertNull(WifiMappers.ssid("<unknown ssid>"))
        assertNull(WifiMappers.ssid(null))
        assertNull(WifiMappers.bssid("02:00:00:00:00:00"))
        assertEquals("aa:bb:cc:dd:ee:ff", WifiMappers.bssid("AA:BB:CC:DD:EE:FF"))
        assertNull(WifiMappers.rssi(-127))
        assertEquals(-60, WifiMappers.rssi(-60))
        assertNull(WifiMappers.linkSpeed(-1))
        assertEquals(WifiStandard.AX, WifiMappers.standard(6))
        assertEquals(WifiStandard.BE, WifiMappers.standard(8))
        assertEquals(WifiStandard.UNKNOWN, WifiMappers.standard(7))
        assertEquals(80, WifiMappers.channelWidthMhz(2))
        assertEquals(320, WifiMappers.channelWidthMhz(5))
        assertNull(WifiMappers.channelWidthMhz(-1))
        assertEquals("192.168.1.1", WifiMappers.ipv4FromInt(0x0101A8C0))
        assertEquals("DE", WifiMappers.countryFromElement("DE ".toByteArray() + byteArrayOf(1, 13, 20)))
        assertEquals("US", WifiMappers.countryFromElement("usO".toByteArray()))
        assertNull(WifiMappers.countryFromElement(byteArrayOf(0, 0, 0)))
        assertNull(WifiMappers.countryFromElement(byteArrayOf(68)))
        assertNull(WifiMappers.countryFromElement(null))
        assertNull(WifiMappers.ipv4FromInt(0))
    }

    @Test
    fun `permissions depend on the android version`() {
        assertEquals(2, AppPermissions.wifiPermissions(sdk = 30).size)
        assertTrue(AppPermissions.wifiPermissions(sdk = 33).contains("android.permission.NEARBY_WIFI_DEVICES"))
        assertNull(AppPermissions.notificationPermission(sdk = 32))
        assertEquals("android.permission.POST_NOTIFICATIONS", AppPermissions.notificationPermission(sdk = 34))
    }

    @Test
    fun `chart math`() {
        assertNull(ChartMath.range(listOf(null, null), floorAtZero = true))
        val r = ChartMath.range(listOf(10.0, null, 20.0), floorAtZero = true)!!
        assertEquals(9.0, r.min, 1e-9)
        assertEquals(21.0, r.max, 1e-9)
        val flat = ChartMath.range(listOf(-60.0, -60.0), floorAtZero = false)!!
        assertTrue(flat.max > flat.min)
        assertEquals(0.0, ChartMath.range(listOf(0.0, 1.0), floorAtZero = true)!!.min, 0.0)
        assertEquals(0, ChartMath.nearestIndex(10f, 300f, 4))
        assertEquals(1, ChartMath.nearestIndex(110f, 300f, 4))
        assertEquals(3, ChartMath.nearestIndex(500f, 300f, 4))
        assertEquals(0, ChartMath.nearestIndex(-20f, 300f, 4))
        assertEquals(0, ChartMath.nearestIndex(250f, 300f, 1))
        assertNull(ChartMath.nearestIndex(10f, 300f, 0))
    }

    @Test
    fun `interval labels`() {
        assertEquals("15 min", intervalLabel(15))
        assertEquals("1 h", intervalLabel(60))
        assertEquals("6 h", intervalLabel(360))
        assertEquals("Daily", intervalLabel(1440))
    }
}
