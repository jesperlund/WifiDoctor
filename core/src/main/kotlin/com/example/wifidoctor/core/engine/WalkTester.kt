package com.example.wifidoctor.core.engine

import com.example.wifidoctor.core.diagnosis.Diagnoser
import com.example.wifidoctor.core.model.Diagnosis
import com.example.wifidoctor.core.model.LinkInfo
import com.example.wifidoctor.core.model.Measurements
import com.example.wifidoctor.core.probe.LatencyProber
import com.example.wifidoctor.core.probe.ProbeMath
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.roundToInt

sealed interface WalkEvent {
    data class Progress(val elapsedMs: Long, val totalMs: Long, val latestRssiDbm: Int?) : WalkEvent
    data class Finished(val result: WalkResult) : WalkEvent
}

data class WalkResult(
    val roomName: String,
    val rssiSamples: List<Int>,
    val measurements: Measurements,
    val diagnosis: Diagnosis,
)

/**
 * "Walk test": samples signal strength and one gateway probe every [intervalMs] for [durationMs]
 * while the user stands in a room, then summarises it like a short diagnostic run.
 */
class WalkTester(
    private val linkSource: LinkSource,
    private val prober: LatencyProber,
    private val diagnoser: Diagnoser = Diagnoser(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun run(roomName: String, durationMs: Long = 10_000, intervalMs: Long = 1_000, probeTimeoutMs: Int = 800): Flow<WalkEvent> = flow {
        val samplesWanted = (durationMs / intervalMs).toInt().coerceAtLeast(1)
        val rssi = mutableListOf<Int>()
        val speeds = mutableListOf<Int>()
        val gatewaySamples = mutableListOf<Double?>()
        var lastLink: LinkInfo? = null
        for (i in 0 until samplesWanted) {
            val link = linkSource.currentLink()
            if (link != null) {
                lastLink = link
                link.rssiDbm?.let { rssi += it }
                link.linkSpeedMbps?.takeIf { it > 0 }?.let { speeds += it }
                link.gatewayIp?.let { gatewaySamples += prober.probe(it, 1, probeTimeoutMs).avgMs }
            }
            emit(WalkEvent.Progress((i + 1) * intervalMs, durationMs, link?.rssiDbm))
            if (i < samplesWanted - 1) delay(intervalMs)
        }
        val summaryLink = lastLink?.copy(
            rssiDbm = rssi.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            linkSpeedMbps = ProbeMath.median(speeds.map { it.toDouble() })?.roundToInt(),
        )
        val gateway = lastLink?.gatewayIp?.takeIf { gatewaySamples.isNotEmpty() }?.let { ProbeMath.stats(it, gatewaySamples) }
        val m = Measurements(timestampMs = clock(), link = summaryLink, gateway = gateway)
        emit(WalkEvent.Finished(WalkResult(roomName.trim(), rssi, m, diagnoser.diagnose(m))))
    }
}
