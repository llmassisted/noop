package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.noop.R

/** Smoothed display of five-minute medians with each interval's full sample range drawn separately. */
@Composable
internal fun OuraOvernightChart(
    readings: List<OvernightVitalReading>, key: String, color: Color, format: (Double) -> String, is24h: Boolean,
) {
    val buckets = remember(readings) { OuraOvernightReadings.chartBuckets(readings) }
    if (buckets.isEmpty()) return
    var selected by remember(buckets) { mutableIntStateOf(-1) }
    val from = buckets.first().start
    val to = buckets.last().end
    val span = (to - from).toDouble()
    val low = buckets.minOf { it.low }
    val high = buckets.maxOf { it.high }
    // A fixed percentage scale keeps nights comparable. Temperature remains in its stored Celsius scale;
    // every label, including the axis, goes through the user's unit formatter.
    val floor = if (key == "spo2") minOf(85.0, low) else low - maxOf((high - low) * 0.1, 0.1)
    val ceiling = if (key == "spo2") maxOf(100.0, high) else high + maxOf((high - low) * 0.1, 0.1)
    val ticks = if (key == "spo2") listOf(ceiling, 95.0, 90.0, floor) else listOf(ceiling, (ceiling + floor) / 2, floor)
    val legend = uiString(R.string.oura_overnight_chart_legend)
    val readout = buckets.getOrNull(selected)?.let {
        uiString(R.string.oura_overnight_chart_selection, clockTimeLabel(it.start, is24h), clockTimeLabel(it.end, is24h),
            format(it.median), format(it.low), format(it.high), it.count)
    } ?: uiString(R.string.oura_overnight_chart_hint)
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Text(legend, style = NoopType.footnote, color = Palette.textSecondary)
        // Layout text separately from Canvas: font scaling and long time labels cannot overlap the plot.
        Box(Modifier.fillMaxWidth().heightIn(min = Metrics.space24 * 2), contentAlignment = Alignment.CenterStart) {
            Text(readout, style = NoopType.footnote, color = Palette.textPrimary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
            Column(modifier = Modifier.height(Metrics.chartHeight).padding(vertical = Metrics.space6),
                verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                ticks.forEach { Text(format(it), style = NoopType.footnote, color = Palette.textTertiary) }
            }
            Column(modifier = Modifier.weight(1f)) {
                Canvas(modifier = Modifier.fillMaxWidth().height(Metrics.chartHeight).clipToBounds()
                    .semantics { contentDescription = readout }
                    .pointerInput(buckets) {
                        detectTapGestures { position ->
                            if (size.width > 0) {
                                val ts = from + (position.x / size.width * span).toLong().coerceIn(0, to - from - 1)
                                selected = OuraOvernightReadings.chartBucketAt(buckets, ts)
                            }
                        }
                    }
                    .pointerInput(buckets) {
                        fun select(x: Float) {
                            if (size.width <= 0) return
                            val ts = from + (x / size.width * span).toLong().coerceIn(0, to - from - 1)
                            selected = OuraOvernightReadings.chartBucketAt(buckets, ts)
                        }
                        detectHorizontalDragGestures(onDragStart = { select(it.x) }) { change, _ ->
                            select(change.position.x)
                            change.consume()
                        }
                    },
                ) {
                    val pad = Metrics.space6.toPx()
                    val plotHeight = (size.height - 2 * pad).coerceAtLeast(1f)
                    fun x(ts: Long) = ((ts - from) / span * size.width).toFloat()
                    fun y(value: Double) = pad + ((ceiling - value) / (ceiling - floor) * plotHeight).toFloat()
                    val thin = Metrics.divider.toPx()
                    ticks.forEach { drawLine(Palette.hairline, Offset(0f, y(it)), Offset(size.width, y(it)), thin) }
                    val path = Path()
                    buckets.forEachIndexed { index, bucket ->
                        val left = x(bucket.start)
                        val right = x(bucket.end)
                        // The range is not smoothed: even a single brief dip remains visible in its interval.
                        drawRect(color.copy(alpha = StrandAlpha.selectedFill), Offset(left, y(bucket.high)),
                            Size(right - left, (y(bucket.low) - y(bucket.high)).coerceAtLeast(thin)))
                        val center = (left + right) / 2
                        val cy = y(bucket.median)
                        val previous = buckets.getOrNull(index - 1)
                        if (previous != null && previous.end == bucket.start) {
                            val px = x(previous.start + (previous.end - previous.start) / 2)
                            val py = y(previous.median)
                            val mid = (px + center) / 2
                            // Horizontal tangents stay within the two medians: no spline overshoot or invented dip.
                            path.cubicTo(mid, py, mid, cy, center, cy)
                        } else path.moveTo(center, cy)
                        if ((previous == null || previous.end != bucket.start) &&
                            buckets.getOrNull(index + 1)?.start != bucket.end) {
                            drawCircle(color, Metrics.space2.toPx(), Offset(center, cy))
                        }
                    }
                    drawPath(path, color, style = Stroke(Metrics.space2.toPx(), cap = StrokeCap.Round))
                    buckets.getOrNull(selected)?.let {
                        val center = x(it.start + (it.end - it.start) / 2)
                        drawLine(color.copy(alpha = StrandAlpha.chartMarker), Offset(center, pad),
                            Offset(center, size.height - pad), thin)
                        drawCircle(color, Metrics.space4.toPx(), Offset(center, y(it.median)))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(clockTimeLabel(from, is24h), style = NoopType.footnote, color = Palette.textTertiary)
                    Text(clockTimeLabel(to, is24h), style = NoopType.footnote, color = Palette.textTertiary)
                }
            }
        }
    }
}
