package com.example.wifidoctor.core.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeMathTest {

    @Test
    fun `stats over mixed samples`() {
        val s = ProbeMath.stats("gw", listOf(10.0, null, 20.0, 30.0, null))
        assertEquals(5, s.sent)
        assertEquals(3, s.received)
        assertEquals(20.0, s.avgMs!!, 1e-9)
        assertEquals(10.0, s.minMs!!, 1e-9)
        assertEquals(30.0, s.maxMs!!, 1e-9)
        assertEquals(40.0, s.lossPercent, 1e-9)
        assertEquals(10.0, s.jitterMs!!, 1e-9)
    }

    @Test
    fun `all lost`() {
        val s = ProbeMath.stats("gw", List(10) { null })
        assertTrue(s.allLost)
        assertEquals(100.0, s.lossPercent, 0.0)
        assertNull(s.avgMs)
        assertNull(s.jitterMs)
    }

    @Test
    fun `no samples means zero loss`() {
        val s = ProbeMath.stats("gw", emptyList())
        assertEquals(0.0, s.lossPercent, 0.0)
        assertEquals(false, s.allLost)
    }

    @Test
    fun `jitter is mean absolute consecutive difference`() {
        assertEquals(5.0, ProbeMath.jitter(listOf(10.0, 15.0, 10.0, 15.0))!!, 1e-9)
        assertEquals(0.0, ProbeMath.jitter(listOf(7.0, 7.0))!!, 1e-9)
        assertNull(ProbeMath.jitter(listOf(7.0)))
    }

    @Test
    fun mbps() {
        assertEquals(80.0, ProbeMath.mbps(10_000_000, 1_000)!!, 1e-9)
        assertEquals(8.0, ProbeMath.mbps(5_000_000, 5_000)!!, 1e-9)
        assertNull(ProbeMath.mbps(0, 1_000))
        assertNull(ProbeMath.mbps(1_000, 0))
    }

    @Test
    fun median() {
        assertEquals(2.0, ProbeMath.median(listOf(3.0, 1.0, 2.0))!!, 0.0)
        assertEquals(2.5, ProbeMath.median(listOf(4.0, 1.0, 2.0, 3.0))!!, 0.0)
        assertNull(ProbeMath.median(emptyList()))
    }
}
