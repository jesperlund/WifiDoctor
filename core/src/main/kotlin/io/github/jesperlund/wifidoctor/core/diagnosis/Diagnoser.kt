package io.github.jesperlund.wifidoctor.core.diagnosis

import io.github.jesperlund.wifidoctor.core.model.Diagnosis
import io.github.jesperlund.wifidoctor.core.model.Finding
import io.github.jesperlund.wifidoctor.core.model.Layer
import io.github.jesperlund.wifidoctor.core.model.Measurements
import io.github.jesperlund.wifidoctor.core.model.Severity

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
    /** Upload needed for HD video calls (Teams recommends ~1.5–4 Mbps). */
    val slowUploadMbps: Double = 2.0,
    val verySlowUploadMbps: Double = 0.5,
    /** Latency increase under load. Roughly bufferbloat grade C (+60 ms) and D (+200 ms). */
    val bufferbloatWarnMs: Double = 60.0,
    val bufferbloatFailMs: Double = 200.0,
    val loadedLossWarnPercent: Double = 5.0,
    /** Call-quality targets for the UDP path, based on Microsoft's Teams network guidance. */
    val udpRttWarnMs: Double = 150.0,
    val udpJitterWarnMs: Double = 30.0,
    val udpLossWarnPercent: Double = 1.0,
    val udpLossFailPercent: Double = 5.0,
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
            .sortedWith(byPriority)

    fun diagnose(m: Measurements): Diagnosis =
        assemble(findings(m), "Your Wi-Fi looks healthy", healthyExplanation(m))

    /** Worst severity found for [layer], or OK. Used for the per-layer progress stepper. */
    fun layerSeverity(m: Measurements, layer: Layer): Severity =
        findings(m).filter { it.layer == layer }.maxOfOrNull { it.severity } ?: Severity.OK

    companion object {
        /**
         * Builds a diagnosis from findings sorted by priority: the first is the verdict, the rest are
         * mentioned, and all fixes are merged in order without duplicates.
         */
        fun assemble(findings: List<Finding>, okVerdict: String, okExplanation: String): Diagnosis {
            val primary = findings.firstOrNull()
                ?: return Diagnosis(okVerdict, Severity.OK, null, okExplanation, emptyList(), emptyList())
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
            return Diagnosis(primary.title, primary.severity, primary.layer, explanation, fixes.toList(), findings)
        }

        val byPriority: Comparator<Finding> = compareByDescending<Finding> { it.severity }.thenBy { it.layer.ordinal }
    }

    private fun healthyExplanation(m: Measurements): String = buildString {
        append("Signal, router, internet and DNS all responded normally.")
        m.link?.rssiDbm?.let { append(" Signal strength is $it dBm.") }
        m.internetSummary?.avgMs?.let { append(" Internet latency averaged ${it.toInt()} ms.") }
        m.throughput?.mbps?.let { append(" Download speed was ${"%.1f".format(it)} Mbps.") }
    }
}
