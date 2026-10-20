package io.github.jesperlund.wifidoctor.core.history

import io.github.jesperlund.wifidoctor.core.Fixtures
import io.github.jesperlund.wifidoctor.core.diagnosis.Diagnoser
import io.github.jesperlund.wifidoctor.core.model.EnvironmentIssue
import io.github.jesperlund.wifidoctor.core.model.EnvironmentStatus
import io.github.jesperlund.wifidoctor.core.model.Severity
import io.github.jesperlund.wifidoctor.core.model.Transport
import io.github.jesperlund.wifidoctor.core.wifi.SignalQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryAnalysisTest {

    private fun record(
        id: Long,
        t: Long = id * 1000,
        rssi: Int? = -55,
        latency: Double? = 20.0,
        loss: Double? = 0.0,
        room: String? = null,
    ) = RunRecord(
        id = id, timestampMs = t, roomLabel = room, rssiDbm = rssi, internetAvgMs = latency,
        internetLossPercent = loss, gatewayLossPercent = loss, gatewayAvgMs = latency?.div(4),
        severity = Severity.OK, verdict = "ok", explanation = "",
    )

    @Test
    fun `record from measurements and diagnosis`() {
        val m = Fixtures.healthy()
        val r = RunRecord.from(m, Diagnoser().diagnose(m), roomLabel = "  Office ")
        assertEquals("Office", r.roomLabel)
        assertEquals(-50, r.rssiDbm)
        assertEquals(36, r.channel)
        assertEquals("1.1.1.1", r.internetTarget)
        assertEquals(Severity.OK, r.severity)
        assertNull(RunRecord.from(m, Diagnoser().diagnose(m), roomLabel = " ").roomLabel)
    }

    @Test
    fun `detects loss, latency spikes and signal dips`() {
        val records = listOf(
            record(1), record(2), record(3, loss = 20.0), record(4, latency = 400.0), record(5, rssi = -78), record(6),
        )
        val drops = DropDetector.detect(records).associateBy { it.recordId }
        assertEquals(setOf(3L, 4L, 5L), drops.keys)
        assertEquals(setOf(DropReason.PACKET_LOSS), drops.getValue(3).reasons)
        assertEquals(setOf(DropReason.LATENCY_SPIKE), drops.getValue(4).reasons)
        assertEquals(setOf(DropReason.SIGNAL_DIP), drops.getValue(5).reasons)
    }

    @Test
    fun `steady history has no drops`() {
        assertTrue(DropDetector.detect((1L..20L).map { record(it, latency = 20.0 + it % 3) }).isEmpty())
        assertTrue(DropDetector.detect(emptyList()).isEmpty())
    }

    @Test
    fun `rooms are ranked best first using the latest run per room`() {
        val records = listOf(
            record(1, room = "Kitchen", rssi = -80),
            record(2, room = "kitchen ", rssi = -50), // newer run of the same room
            record(3, room = "Bedroom", rssi = -72),
            record(4, room = "Garage", rssi = -86),
            record(5, room = null, rssi = -40),
        )
        val ranked = RoomRanking.rank(records)
        assertEquals(listOf("kitchen", "Bedroom", "Garage"), ranked.map { it.roomName })
        assertEquals(SignalQuality.EXCELLENT, ranked.first().quality)
        assertTrue(ranked.last().isDeadZone)
        assertFalse(ranked.first().isDeadZone)
    }

    @Test
    fun `loss lowers a room's rank`() {
        val ranked = RoomRanking.rank(
            listOf(record(1, room = "A", rssi = -60, loss = 40.0), record(2, room = "B", rssi = -65)),
        )
        assertEquals("B", ranked.first().roomName)
        assertTrue(ranked.last().isDeadZone)
    }

    @Test
    fun `csv export escapes and guards against formula injection`() {
        val r = record(1, room = "Living room, \"main\"").copy(ssid = "=HYPERLINK(1)", fixes = listOf("a", "b"))
        val csv = CsvExporter.export(listOf(r)).lines()
        assertEquals(CsvExporter.header.joinToString(","), csv[0])
        assertTrue(csv[1].contains("\"Living room, \"\"main\"\"\""))
        assertTrue(csv[1].contains("'=HYPERLINK(1)"))
        assertTrue(csv[1].contains(",-55,"))
        assertTrue(csv[1].contains("20.00"))
        assertTrue(csv[1].contains("a | b"))
        assertEquals(CsvExporter.header.size, csv[1].split(Regex(""",(?=(?:[^"]*"[^"]*")*[^"]*$)""")).size)
    }

    @Test
    fun `environment issues`() {
        val ok = EnvironmentStatus.Unknown
        assertTrue(ok.issues.isEmpty())
        assertTrue(ok.canDiagnose)

        val off = ok.copy(wifiEnabled = false, wifiConnected = false)
        assertEquals(listOf(EnvironmentIssue.WIFI_OFF), off.issues)
        assertFalse(off.canDiagnose)

        val cellular = ok.copy(wifiConnected = false, defaultTransport = Transport.CELLULAR)
        assertEquals(listOf(EnvironmentIssue.NOT_CONNECTED, EnvironmentIssue.ON_CELLULAR), cellular.issues)

        val limited = ok.copy(locationEnabled = false, locationPermission = false, wifiMetered = true)
        assertEquals(
            listOf(EnvironmentIssue.PERMISSION_MISSING, EnvironmentIssue.LOCATION_OFF, EnvironmentIssue.METERED),
            limited.issues,
        )
        assertTrue(limited.canDiagnose)
        assertTrue(ok.copy(defaultTransport = Transport.CELLULAR).issues.contains(EnvironmentIssue.CELLULAR_DEFAULT))
    }
}
