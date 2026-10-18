package com.example.wifidoctor.core.engine

import com.example.wifidoctor.core.Fixtures
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalkTesterTest {

    @Test
    fun `samples once per second for ten seconds and averages`() = runTest {
        val rssi = ArrayDeque((1..10).map { if (it % 2 == 0) -60 else -70 })
        val tester = WalkTester(
            linkSource = { Fixtures.link(rssi = rssi.removeFirst(), speed = 400) },
            prober = FakeProber(mapOf("192.168.1.1" to listOf(4.0))),
        )
        val events = tester.run("  Kitchen ").toList()
        val progress = events.filterIsInstance<WalkEvent.Progress>()
        assertEquals(10, progress.size)
        assertEquals(10_000L, progress.last().elapsedMs)
        assertEquals(9_000L, currentTime) // no delay after the last sample
        val result = (events.last() as WalkEvent.Finished).result
        assertEquals("Kitchen", result.roomName)
        assertEquals(-65, result.measurements.link!!.rssiDbm)
        assertEquals(400, result.measurements.link!!.linkSpeedMbps)
        assertEquals(10, result.measurements.gateway!!.sent)
        assertEquals(10, result.rssiSamples.size)
    }

    @Test
    fun `weak room is diagnosed`() = runTest {
        val tester = WalkTester({ Fixtures.link(rssi = -84) }, FakeProber(emptyMap()))
        val result = (tester.run("Garage", durationMs = 3_000).toList().last() as WalkEvent.Finished).result
        assertEquals("weak_signal", result.diagnosis.findings.first().ruleId)
    }

    @Test
    fun `disconnected the whole time`() = runTest {
        val tester = WalkTester({ null }, FakeProber(emptyMap()))
        val result = (tester.run("Shed", durationMs = 2_000).toList().last() as WalkEvent.Finished).result
        assertNull(result.measurements.link)
        assertEquals("not_connected", result.diagnosis.findings.single().ruleId)
    }
}
