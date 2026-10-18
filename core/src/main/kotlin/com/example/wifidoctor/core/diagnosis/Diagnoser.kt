package com.example.wifidoctor.core.diagnosis

import com.example.wifidoctor.core.model.Diagnosis
import com.example.wifidoctor.core.model.Finding
import com.example.wifidoctor.core.model.Layer
import com.example.wifidoctor.core.model.Measurements
import com.example.wifidoctor.core.model.Severity

/** Tunable thresholds shared by the rules. */
data class DiagnoserConfig(
    val weakRssiDbm: Int = -70,
    val veryWeakRssiDbm: Int = -80,
    val strongRssiDbm: Int = -60,
    val gatewayHighLatencyMs: Double = 30.0,
    val gatewayHighJitterMs: Double = 20.0,
    val gatewayLossWarnPercent: Double = 5.0,
    val gatewayLossFailPercent: Double = 10.0,
    val internetHighLatencyMs: Double = 150.0,
    val internetHighJitterMs: Double = 50.0,
    val internetLossWarnPercent: Double = 5.0,
    val internetLossFailPercent: Double = 20.0,
    /** System resolver is "much slower" if slower than reference × ratio AND by at least the margin. */
    val dnsSlowRatio: Double = 2.0,
    val dnsSlowMarginMs: Double = 50.0,
    /** A link below this fraction of the typical maximum for its band/standard/width is "low". */
    val lowLinkSpeedFraction: Double = 0.25,
    val bandSteeringMinRssiDbm: Int = -67,
    val congestedSameChannel: Int = 4,
    val congestedOverlapping: Int = 6,
    val slowThroughputMbps: Double = 5.0,
)

/** One diagnostic rule. Returns null when the rule does not apply. */
fun interface DiagnosisRule {
    fun evaluate(m: Measurements, config: DiagnoserConfig): Finding?
}

/**
 * Turns [Measurements] into a [Diagnosis]. All rules run; the primary finding is the most severe,
 * ties broken by the earliest layer, so a FAIL at the link beats a FAIL further up the stack.
 */
class Diagnoser(
    private val config: DiagnoserConfig = DiagnoserConfig(),
    private val rules: List<DiagnosisRule> = Rules.all,
) {
    fun findings(m: Measurements): List<Finding> =
        rules.mapNotNull { it.evaluate(m, config) }
            .sortedWith(compareByDescending<Finding> { it.severity }.thenBy { it.layer.ordinal })

    fun diagnose(m: Measurements): Diagnosis {
        val findings = findings(m)
        val primary = findings.firstOrNull()
            ?: return Diagnosis(
                verdict = "Your Wi-Fi looks healthy",
                severity = Severity.OK,
                layer = null,
                explanation = healthyExplanation(m),
                fixes = emptyList(),
                findings = emptyList(),
            )
        val fixes = LinkedHashSet<String>()
        findings.forEach { fixes += it.fixes }
        val others = findings.drop(1)
        val explanation = buildString {
            append(primary.explanation)
            if (others.isNotEmpty()) {
                append("\n\nAlso noticed: ")
                append(others.joinToString("; ") { it.title.replaceFirstChar(Char::lowercase) })
                append('.')
            }
        }
        return Diagnosis(
            verdict = primary.title,
            severity = primary.severity,
            layer = primary.layer,
            explanation = explanation,
            fixes = fixes.toList(),
            findings = findings,
        )
    }

    /** Worst severity found for [layer], or OK. Used for the per-layer progress stepper. */
    fun layerSeverity(m: Measurements, layer: Layer): Severity =
        findings(m).filter { it.layer == layer }.maxOfOrNull { it.severity } ?: Severity.OK

    private fun healthyExplanation(m: Measurements): String = buildString {
        append("Signal, router, internet and DNS all responded normally.")
        m.link?.rssiDbm?.let { append(" Signal strength is $it dBm.") }
        m.internetSummary?.avgMs?.let { append(" Internet latency averaged ${it.toInt()} ms.") }
        m.throughput?.mbps?.let { append(" Download speed was ${"%.1f".format(it)} Mbps.") }
    }
}
