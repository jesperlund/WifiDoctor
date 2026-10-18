package com.example.wifidoctor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.wifidoctor.core.engine.StepState
import com.example.wifidoctor.core.model.Diagnosis
import com.example.wifidoctor.core.model.Severity
import com.example.wifidoctor.core.wifi.SignalQuality
import com.example.wifidoctor.ui.dashboard.StepUi
import com.example.wifidoctor.ui.theme.LocalStatusColors

/** Icon + colour + word for a severity, so status is never conveyed by colour alone. */
data class StatusStyle(val icon: ImageVector, val color: Color, val label: String)

@Composable
fun severityStyle(severity: Severity): StatusStyle {
    val c = LocalStatusColors.current
    return when (severity) {
        Severity.OK -> StatusStyle(Icons.Filled.CheckCircle, c.ok, "OK")
        Severity.WARN -> StatusStyle(Icons.Filled.Warning, c.warn, "Warning")
        Severity.FAIL -> StatusStyle(Icons.Filled.Clear, c.fail, "Problem")
    }
}

@Composable
fun signalColor(quality: SignalQuality): Color {
    val c = LocalStatusColors.current
    return when (quality) {
        SignalQuality.EXCELLENT, SignalQuality.GOOD -> c.ok
        SignalQuality.FAIR -> c.warn
        SignalQuality.WEAK, SignalQuality.VERY_WEAK -> c.fail
        SignalQuality.UNKNOWN -> c.neutral
    }
}

@Composable
fun SeverityBadge(severity: Severity, modifier: Modifier = Modifier) {
    val style = severityStyle(severity)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(style.icon, contentDescription = null, tint = style.color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(4.dp))
        Text(style.label, color = style.color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.semantics { heading() },
    )
}

/** Semicircular RSSI gauge from -100 to -30 dBm with the value and quality word in the middle. */
@Composable
fun RssiGauge(rssiDbm: Int?, modifier: Modifier = Modifier, size: Dp = 140.dp) {
    val quality = SignalQuality.of(rssiDbm)
    val fraction = SignalQuality.gaugeFraction(rssiDbm)
    val color = signalColor(quality)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val description = if (rssiDbm == null) "Signal strength unknown" else "Signal strength $rssiDbm dBm, ${quality.label}"
    Box(modifier.size(size, size * 0.65f).clearAndSetSemantics { contentDescription = description }, contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.size(size)) {
            val stroke = 14.dp.toPx()
            val arcSize = Size(this.size.width - stroke, this.size.width - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)
            drawArc(track, 180f, 180f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (rssiDbm != null) {
                drawArc(color, 180f, 180f * fraction.coerceAtLeast(0.02f), false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 4.dp)) {
            Text(rssiDbm?.let { "$it dBm" } ?: "–", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(quality.label, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

/** Four bars, filled according to signal quality, with an accessible description. */
@Composable
fun SignalBars(rssiDbm: Int?, modifier: Modifier = Modifier, barWidth: Dp = 6.dp, height: Dp = 24.dp) {
    val quality = SignalQuality.of(rssiDbm)
    val color = signalColor(quality)
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val description = "${quality.label} signal, ${quality.bars} of 4 bars" + (rssiDbm?.let { ", $it dBm" } ?: "")
    Canvas(modifier.size(barWidth * 4 + 9.dp, height).semantics { contentDescription = description }) {
        val w = barWidth.toPx()
        val gap = 3.dp.toPx()
        for (i in 0 until 4) {
            val h = this.size.height * (i + 1) / 4f
            drawRoundRect(
                color = if (i < quality.bars) color else empty,
                topLeft = Offset(i * (w + gap), this.size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
    }
}

/** Vertical stepper showing each diagnostic layer's progress and result. */
@Composable
fun LayerStepper(steps: List<StepUi>, modifier: Modifier = Modifier) {
    val c = LocalStatusColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEachIndexed { index, step ->
            val (label, color) = when (step.state) {
                StepState.PENDING -> "Waiting" to c.neutral
                StepState.RUNNING -> "Checking…" to MaterialTheme.colorScheme.primary
                StepState.OK -> "OK" to c.ok
                StepState.WARN -> "Warning" to c.warn
                StepState.FAIL -> "Problem" to c.fail
                StepState.SKIPPED -> "Skipped" to c.neutral
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Step ${index + 1}, ${step.layer.label}: $label" + (step.summary?.let { ", $it" } ?: "")
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    when (step.state) {
                        StepState.RUNNING -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                        StepState.OK -> Icon(Icons.Filled.CheckCircle, null, tint = color)
                        StepState.WARN -> Icon(Icons.Filled.Warning, null, tint = color)
                        StepState.FAIL -> Icon(Icons.Filled.Clear, null, tint = color)
                        StepState.SKIPPED -> Icon(Icons.Filled.KeyboardArrowRight, null, tint = color)
                        StepState.PENDING -> Text("${index + 1}", color = color, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(step.layer.label, style = MaterialTheme.typography.bodyLarge)
                    step.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text(label, color = color, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Verdict, explanation and ordered fixes. */
@Composable
fun DiagnosisCard(diagnosis: Diagnosis, modifier: Modifier = Modifier, title: String? = null) {
    val style = severityStyle(diagnosis.severity)
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Icon(style.icon, contentDescription = style.label, tint = style.color, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(diagnosis.verdict, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        style.label + (diagnosis.layer?.let { " · ${it.label}" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = style.color,
                    )
                }
            }
            Text(diagnosis.explanation, style = MaterialTheme.typography.bodyMedium)
            if (diagnosis.fixes.isNotEmpty()) {
                SectionTitle("What to try")
                diagnosis.fixes.forEachIndexed { i, fix ->
                    Row {
                        Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                        Text(fix, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** Inline notice for environment problems (Wi-Fi off, location off, metered...). */
@Composable
fun NoticeCard(title: String, message: String, blocking: Boolean, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = LocalStatusColors.current
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (blocking) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (blocking) Icons.Filled.Warning else Icons.Filled.Info,
                contentDescription = if (blocking) "Problem" else "Information",
                tint = if (blocking) MaterialTheme.colorScheme.onErrorContainer else c.neutral,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
            action?.invoke()
        }
    }
}
