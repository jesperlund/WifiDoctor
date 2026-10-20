package com.example.wifidoctor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.example.wifidoctor.core.model.Band
import com.example.wifidoctor.core.wifi.BandAnalysis
import com.example.wifidoctor.core.wifi.ChannelAnalysis
import com.example.wifidoctor.ui.theme.LocalStatusColors

/** Plain-language summary of one band's channel situation. */
fun BandAnalysis.summary(): String = buildString {
    val cur = current
    when {
        cur == null -> append("Your router was not seen on this band. ")
        currentAllowed == false -> append("Your router uses channel ${cur.channel}, which is not permitted here. ")
        else -> append("Your router uses channel ${cur.channel} (${cur.widthMhz} MHz). ")
    }
    append("$neighbours other ${if (neighbours == 1) "network" else "networks"} nearby. ")
    val best = recommended ?: return@buildString
    when {
        cur != null && best.channel == cur.channel -> append("That is already the best channel.")
        shouldChange -> append("Suggested: channel ${best.label}.")
        cur != null -> append("Best would be ${best.label}, but the gain is too small to bother switching.")
        else -> append("Best channel: ${best.label}.")
    }
    if (best.dfs && (cur == null || best.channel != cur.channel)) append(" DFS channel (shared with radar).")
}

@Composable
fun ChannelAdviceCard(analysis: ChannelAnalysis, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Channel advice")
            Text(
                "Region: ${analysis.region.description}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            analysis.bands.forEachIndexed { i, band ->
                if (i > 0) HorizontalDivider()
                BandSection(band)
            }
        }
    }
}

@Composable
private fun BandSection(band: BandAnalysis) {
    val status = LocalStatusColors.current
    val needsChange = band.shouldChange
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Icon(
                if (needsChange) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                contentDescription = if (needsChange) "Change suggested" else "OK",
                tint = if (needsChange) status.warn else status.ok,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                band.band.label + if (band.connected) " (connected)" else "",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(band.summary(), style = MaterialTheme.typography.bodyMedium)
        if (band.band == Band.GHZ_2_4 || band.occupancy.size <= 30) OccupancyChart(band)
    }
}

/**
 * Bar per permitted channel showing how busy it is. The current channel is labelled "you" and the
 * suggestion "best" (plus a triangle), so the chart does not rely on colour.
 */
@Composable
private fun OccupancyChart(band: BandAnalysis) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val barColor = MaterialTheme.colorScheme.primary
    val bestColor = LocalStatusColors.current.ok
    val currentColor = MaterialTheme.colorScheme.onSurface
    val channels = band.occupancy.keys.sorted()
    if (channels.isEmpty()) return
    val max = (band.occupancy.values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val best = band.recommended?.channel
    val current = band.current?.channel
    val description = "${band.band.label} channel occupancy: " + channels.joinToString("; ") { ch ->
        "channel $ch " + "%.1f".format(band.occupancy.getValue(ch)) +
            (if (ch == current) ", yours" else "") + (if (ch == best) ", best" else "")
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .semantics { contentDescription = description },
    ) {
        val labelH = 14.dp.toPx()
        val markH = 12.dp.toPx()
        val chartTop = markH
        val chartBottom = size.height - labelH
        val slot = size.width / channels.size
        val barW = (slot * 0.6f).coerceAtMost(18.dp.toPx())
        val labelEvery = if (channels.size > 14) 2 else 1
        channels.forEachIndexed { i, ch ->
            val cx = slot * i + slot / 2
            val h = ((band.occupancy.getValue(ch) / max) * (chartBottom - chartTop)).toFloat().coerceAtLeast(2f)
            drawRect(
                color = if (ch == best) bestColor else barColor.copy(alpha = 0.6f),
                topLeft = Offset(cx - barW / 2, chartBottom - h),
                size = Size(barW, h),
            )
            if (ch == best) {
                val path = Path().apply {
                    moveTo(cx, chartTop - 2f)
                    lineTo(cx - markH / 2, 0f)
                    lineTo(cx + markH / 2, 0f)
                    close()
                }
                drawPath(path, bestColor)
            }
            if (ch == current) {
                drawRect(currentColor, Offset(cx - barW / 2 - 2f, chartBottom - h - 2f), Size(barW + 4f, h + 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            }
            if (i % labelEvery == 0 || ch == best || ch == current) {
                val text = when (ch) {
                    best -> "$ch★"
                    current -> "$ch•"
                    else -> "$ch"
                }
                val layout = measurer.measure(text, labelStyle)
                drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, chartBottom + 1f))
            }
        }
    }
    Text(
        "★ suggested channel  • your router  (taller bar = busier)",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
