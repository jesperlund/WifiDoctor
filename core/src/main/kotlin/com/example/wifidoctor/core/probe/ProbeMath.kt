package com.example.wifidoctor.core.probe

import com.example.wifidoctor.core.model.ProbeMethod
import com.example.wifidoctor.core.model.ProbeStats
import kotlin.math.abs

/** Statistics over probe samples. A null sample is a lost probe. */
object ProbeMath {

    fun stats(target: String, samplesMs: List<Double?>, method: ProbeMethod = ProbeMethod.TCP): ProbeStats {
        val ok = samplesMs.filterNotNull()
        return ProbeStats(
            target = target,
            sent = samplesMs.size,
            received = ok.size,
            avgMs = ok.takeIf { it.isNotEmpty() }?.average(),
            minMs = ok.minOrNull(),
            maxMs = ok.maxOrNull(),
            jitterMs = jitter(ok),
            method = method,
        )
    }

    /**
     * Mean absolute difference between consecutive successful samples (the RFC 3550 idea without
     * exponential smoothing). Needs at least two samples.
     */
    fun jitter(samplesMs: List<Double>): Double? {
        if (samplesMs.size < 2) return null
        return samplesMs.zipWithNext { a, b -> abs(b - a) }.average()
    }

    fun mbps(bytes: Long, durationMs: Long): Double? {
        if (durationMs <= 0 || bytes <= 0) return null
        return bytes * 8.0 / 1_000_000.0 / (durationMs / 1000.0)
    }

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }
}
