package com.example.wifidoctor.core.probe

import com.example.wifidoctor.core.model.ProbeMethod
import com.example.wifidoctor.core.model.ProbeStats
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Secondary prober using the platform `ping` binary (Android ships `/system/bin/ping` with the
 * setuid/capability needed for ICMP, which apps cannot open directly). Not available everywhere.
 */
class PingProber(
    private val pingPath: String = "/system/bin/ping",
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LatencyProber {

    fun isAvailable(): Boolean = File(pingPath).canExecute()

    override suspend fun probe(host: String, count: Int, timeoutMs: Int): ProbeStats {
        if (!isAvailable()) return ProbeStats(host, 0, 0, null, null, null, null, ProbeMethod.NONE)
        val timeoutSec = ((timeoutMs + 999) / 1000).coerceAtLeast(1)
        val command = listOf(pingPath, "-n", "-c", count.toString(), "-i", "0.2", "-W", timeoutSec.toString(), host)
        val output = runInterruptible(dispatcher) {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            try {
                val deadline = count * (timeoutSec + 1L) + 5
                if (!process.waitFor(deadline, TimeUnit.SECONDS)) process.destroy()
                process.inputStream.bufferedReader().readText()
            } finally {
                // Also runs when the coroutine is cancelled (interrupt), so no ping is left behind.
                process.destroy()
            }
        }
        return ProbeMath.stats(host, PingOutputParser.samples(output, count), ProbeMethod.PING)
    }
}

/** Parses the output of iputils/toybox `ping`. */
object PingOutputParser {
    private val reply = Regex("""icmp_seq=(\d+).*?time[=<]([\d.]+)\s*ms""")

    /**
     * Returns one entry per sent probe: the RTT in ms, or null if no reply was seen for that
     * sequence number. Duplicate replies are ignored.
     */
    fun samples(output: String, count: Int): List<Double?> {
        val bySeq = HashMap<Int, Double>()
        reply.findAll(output).forEach { m ->
            val seq = m.groupValues[1].toInt()
            val rtt = m.groupValues[2].toDoubleOrNull() ?: return@forEach
            bySeq.putIfAbsent(seq, rtt)
        }
        // iputils starts at icmp_seq=1; some builds start at 0.
        val firstSeq = if (bySeq.containsKey(0)) 0 else 1
        return List(count) { i -> bySeq[firstSeq + i] }
    }
}
