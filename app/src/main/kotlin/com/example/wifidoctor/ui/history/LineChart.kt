package com.example.wifidoctor.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.wifidoctor.ui.theme.LocalStatusColors
import kotlin.math.roundToInt

/** Pure geometry for the chart, kept separate so it can be unit tested. */
object ChartMath {
    data class Range(val min: Double, val max: Double) {
        val span: Double get() = (max - min).takeIf { it > 0 } ?: 1.0
    }

    /** Y range with 10% headroom; flat or empty series get a sensible non-zero span. */
    fun range(values: List<Double?>, floorAtZero: Boolean): Range? {
        val present = values.filterNotNull()
        if (present.isEmpty()) return null
        var min = present.min()
        var max = present.max()
        if (max == min) { min -= 1.0; max += 1.0 }
        val pad = (max - min) * 0.1
        min -= pad; max += pad
        if (floorAtZero) min = maxOf(0.0, min)
        return Range(min, max)
    }

    /** Index of the point nearest to [x] given [count] evenly spaced points across [width]. */
    fun nearestIndex(x: Float, width: Float, count: Int): Int? {
        if (count <= 0 || width <= 0f) return null
        if (count == 1) return 0
        val step = width / (count - 1)
        return (x / step).roundToInt().coerceIn(0, count - 1)
    }
}

/**
 * Minimal Canvas line chart. Null values break the line. Highlighted points (intermittent drops)
 * are drawn as triangles, the selected point as a large ring with a guide line, so neither relies
 * on colour alone. Tap anywhere to select the nearest run.
 */
@Composable
fun LineChart(
    values: List<Double?>,
    highlighted: Set<Int>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    description: String,
    unit: String,
    color: Color = MaterialTheme.colorScheme.primary,
    floorAtZero: Boolean = true,
    height: Dp = 160.dp,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val dropColor = LocalStatusColors.current.fail
    val selectedColor = MaterialTheme.colorScheme.onSurface
    val range = ChartMath.range(values, floorAtZero)
    val currentOnSelect by rememberUpdatedState(onSelect)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .pointerInput(values.size) {
                detectTapGestures { offset ->
                    val left = LEFT_GUTTER.toPx()
                    ChartMath.nearestIndex(offset.x - left, size.width - left - RIGHT_PAD.toPx(), values.size)?.let { currentOnSelect(it) }
                }
            },
    ) {
        val left = LEFT_GUTTER.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 8.dp.toPx()
        val right = size.width - RIGHT_PAD.toPx()
        val width = right - left

        // Grid + axis labels.
        for (i in 0..2) {
            val y = top + (bottom - top) * i / 2f
            drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1f)
            if (range != null) {
                val v = range.max - range.span * i / 2.0
                drawLabel(measurer, "${v.roundToInt()} $unit", Offset(0f, y - 6.dp.toPx()), labelStyle)
            }
        }
        if (range == null || values.isEmpty()) return@Canvas

        fun xAt(i: Int) = if (values.size == 1) left + width / 2 else left + width * i / (values.size - 1)
        fun yAt(v: Double) = (bottom - (v - range.min) / range.span * (bottom - top)).toFloat()

        selectedIndex?.takeIf { it in values.indices }?.let { i ->
            drawLine(
                selectedColor, Offset(xAt(i), top), Offset(xAt(i), bottom), strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
        }

        val path = Path()
        var penDown = false
        values.forEachIndexed { i, v ->
            if (v == null) { penDown = false; return@forEachIndexed }
            val p = Offset(xAt(i), yAt(v))
            if (penDown) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
            penDown = true
        }
        drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))

        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val p = Offset(xAt(i), yAt(v))
            when {
                i == selectedIndex -> drawCircle(selectedColor, 7.dp.toPx(), p, style = Stroke(3.dp.toPx()))
                i in highlighted -> drawTriangle(dropColor, p, 6.dp.toPx())
                values.size <= 60 -> drawCircle(color, 3.dp.toPx(), p)
            }
            if (i == selectedIndex && i in highlighted) drawTriangle(dropColor, p, 5.dp.toPx())
        }
    }
}

private val LEFT_GUTTER = 48.dp
private val RIGHT_PAD = 8.dp

private fun DrawScope.drawTriangle(color: Color, center: Offset, r: Float) {
    val path = Path().apply {
        moveTo(center.x, center.y - r)
        lineTo(center.x + r, center.y + r * 0.8f)
        lineTo(center.x - r, center.y + r * 0.8f)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawLabel(
    measurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    at: Offset,
    style: TextStyle,
) {
    drawText(measurer, text, topLeft = at, style = style)
}
