package com.nutrime.app.ui.trends

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nutrime.app.ui.theme.DesignTokens.Motion
import com.nutrime.app.ui.theme.NutriTheme
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

data class BarPoint(val label: String, val segments: List<Pair<Double, Color>>) {
    val total get() = segments.sumOf { it.first }
}

/** "Nice" axis step so there are at most [maxTicks] gridlines (1, 2, 2.5, 5 × 10^n). */
fun niceStep(maxValue: Double, maxTicks: Int = 4): Double {
    if (maxValue <= 0) return 1.0
    val raw = maxValue / maxTicks
    val mag = 10.0.pow(floor(log10(raw)))
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
}

fun shortNumber(v: Double): String = when {
    v >= 1000 -> (if (v % 1000 == 0.0) "%.0fk" else "%.1fk").format(v / 1000)
    v % 1 == 0.0 -> "%.0f".format(v)
    else -> "%.1f".format(v)
}

private fun DrawScope.label(tm: TextMeasurer, text: String, style: TextStyle, x: Float, y: Float, alignRight: Boolean = false, center: Boolean = false) {
    val layout = tm.measure(text, style)
    val dx = when {
        alignRight -> -layout.size.width.toFloat()
        center -> -layout.size.width / 2f
        else -> 0f
    }
    drawText(layout, topLeft = Offset(x + dx, y - layout.size.height / 2f))
}

/** Stacked bars (one per day) with a dashed target line; tapping a bar selects it. */
@Composable
fun StackedBarChart(
    points: List<BarPoint>,
    target: Double?,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
) {
    val c = NutriTheme.colors
    val tm = rememberTextMeasurer()
    val axisStyle = NutriTheme.type.caption.copy(color = c.textTertiary)
    val progress by animateFloatAsState(1f, tween(Motion.DurationSlow, easing = Motion.EaseOut), label = "bars")
    if (points.isEmpty()) {
        Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) { Text("No data yet", style = NutriTheme.type.small, color = c.textTertiary) }
        return
    }
    val maxV = max(points.maxOf { it.total }, target ?: 0.0).coerceAtLeast(1.0)
    val step = niceStep(maxV)
    val top = ceil(maxV / step) * step
    Canvas(
        modifier.fillMaxWidth().height(height).pointerInput(points.size) {
            detectTapGestures { pos ->
                val left = 36.dp.toPx()
                val slot = (size.width - left) / points.size
                val i = ((pos.x - left) / slot).toInt()
                if (i in points.indices) onSelect(i)
            }
        },
    ) {
        val left = 36.dp.toPx()
        val bottomSpace = 20.dp.toPx()
        val chartH = size.height - bottomSpace - 6.dp.toPx()
        val y0 = 6.dp.toPx()
        fun y(v: Double) = (y0 + chartH - (v / top * chartH)).toFloat()
        var t = 0.0
        while (t <= top + 0.001) {
            drawLine(c.border, Offset(left, y(t)), Offset(size.width, y(t)), 1.dp.toPx())
            label(tm, shortNumber(t), axisStyle, left - 8.dp.toPx(), y(t), alignRight = true)
            t += step
        }
        val slot = (size.width - left) / points.size
        val barW = (slot * 0.62f).coerceAtMost(26.dp.toPx())
        val labelEvery = max(1, ceil(points.size / 7.0).toInt())
        val radius = 3.dp.toPx()
        points.forEachIndexed { i, p ->
            val cx = left + slot * i + slot / 2
            val dim = selected != null && selected != i
            var base = 0.0
            val visible = p.segments.filter { it.first > 0 }
            visible.forEachIndexed { si, (v, color) ->
                val yTop = y((base + v) * progress)
                val yBottom = y(base * progress)
                val h = yBottom - yTop
                if (h > 0.5f) {
                    val isTop = si == visible.lastIndex
                    val rr = RoundRect(
                        left = cx - barW / 2, top = yTop, right = cx + barW / 2, bottom = yBottom - if (si > 0) 1.dp.toPx() else 0f,
                        topLeftCornerRadius = if (isTop) CornerRadius(radius) else CornerRadius.Zero,
                        topRightCornerRadius = if (isTop) CornerRadius(radius) else CornerRadius.Zero,
                    )
                    drawPath(Path().apply { addRoundRect(rr) }, if (dim) color.copy(alpha = 0.35f) else color)
                }
                base += v
            }
            if (i % labelEvery == 0 || i == points.lastIndex && points.size <= 7) {
                label(tm, p.label, axisStyle.copy(color = if (selected == i) c.text else c.textTertiary), cx, size.height - bottomSpace / 2, center = true)
            }
        }
        target?.let {
            drawLine(
                c.textTertiary, Offset(left, y(it)), Offset(size.width, y(it)), 1.2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            )
        }
    }
}

/** Line chart with a soft gradient area underneath (weight over time). x values are day offsets. */
@Composable
fun AreaLineChart(
    values: List<Pair<Float, Double>>,
    xLabels: List<Pair<Float, String>>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
) {
    val c = NutriTheme.colors
    val tm = rememberTextMeasurer()
    val axisStyle = NutriTheme.type.caption.copy(color = c.textTertiary)
    if (values.isEmpty()) {
        Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) { Text("No data yet", style = NutriTheme.type.small, color = c.textTertiary) }
        return
    }
    val minV = values.minOf { it.second }
    val maxV = values.maxOf { it.second }
    val step = niceStep((maxV - minV).coerceAtLeast(1.0), 2)
    val lo = floor(minV / step) * step
    val hi = (ceil(maxV / step) * step).let { if (it == lo) lo + step else it }
    Canvas(modifier.fillMaxWidth().height(height)) {
        val left = 36.dp.toPx()
        val right = 8.dp.toPx()
        val bottomSpace = 20.dp.toPx()
        val y0 = 8.dp.toPx()
        val chartH = size.height - bottomSpace - y0
        val chartW = size.width - left - right
        val minX = values.minOf { it.first }
        val maxX = values.maxOf { it.first }.let { if (it == minX) minX + 1 else it }
        fun x(v: Float) = left + (v - minX) / (maxX - minX) * chartW
        fun y(v: Double) = (y0 + chartH - ((v - lo) / (hi - lo) * chartH)).toFloat()
        var t = lo
        while (t <= hi + 0.001) {
            drawLine(c.border, Offset(left, y(t)), Offset(size.width - right, y(t)), 1.dp.toPx())
            label(tm, shortNumber(t), axisStyle, left - 8.dp.toPx(), y(t), alignRight = true)
            t += step
        }
        xLabels.forEach { (xv, text) ->
            val layout = tm.measure(text, axisStyle)
            val px = (x(xv) - layout.size.width / 2f).coerceIn(left, size.width - layout.size.width)
            drawText(layout, topLeft = Offset(px, size.height - bottomSpace / 2 - layout.size.height / 2f))
        }
        val line = Path()
        values.forEachIndexed { i, (xv, v) -> if (i == 0) line.moveTo(x(xv), y(v)) else line.lineTo(x(xv), y(v)) }
        val area = Path().apply {
            addPath(line)
            lineTo(x(values.last().first), y0 + chartH)
            lineTo(x(values.first().first), y0 + chartH)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.22f), c.accent.copy(alpha = 0f)), startY = y0, endY = y0 + chartH))
        drawPath(line, c.accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        values.forEach { (xv, v) ->
            drawCircle(c.surface, 3.5.dp.toPx(), Offset(x(xv), y(v)))
            drawCircle(c.accent, 3.5.dp.toPx(), Offset(x(xv), y(v)), style = Stroke(1.6.dp.toPx()))
        }
    }
}

/**
 * Calm daily bars: one neutral color, the selected day in the accent, a dashed target line and no
 * axis. A day with no data is drawn as a short stub. Tapping a bar selects it.
 */
@Composable
fun MiniBarChart(
    values: List<Double?>,
    labels: List<String>,
    target: Double?,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 112.dp,
    /** A target for each bar (a weekday calorie schedule); replaces the single target line. */
    targets: List<Double?>? = null,
    /** An upper limit, drawn as a red dashed line. */
    limit: Double? = null,
) {
    val c = NutriTheme.colors
    val tm = rememberTextMeasurer()
    val axisStyle = NutriTheme.type.caption.copy(color = c.textTertiary)
    val selStyle = axisStyle.copy(color = c.text, fontWeight = NutriTheme.type.bodyMedium.fontWeight)
    val progress by animateFloatAsState(1f, tween(Motion.DurationSlow, easing = Motion.EaseOut), label = "bars")
    if (values.isEmpty()) return
    val top = (maxOf(values.maxOf { it ?: 0.0 }, target ?: 0.0, targets?.maxOfOrNull { it ?: 0.0 } ?: 0.0, limit ?: 0.0) * 1.06).coerceAtLeast(1.0)
    val neutral = c.textTertiary.copy(alpha = if (c.isDark) 0.42f else 0.32f)
    Canvas(
        modifier.fillMaxWidth().height(height).pointerInput(values.size) {
            detectTapGestures { pos ->
                val i = (pos.x / (size.width / values.size)).toInt()
                if (i in values.indices) onSelect(i)
            }
        },
    ) {
        val labelSpace = 18.dp.toPx()
        val chartH = size.height - labelSpace
        val slot = size.width / values.size
        val barW = (slot * 0.62f).coerceIn(1.5.dp.toPx(), 28.dp.toPx())
        val radius = CornerRadius((barW / 2).coerceAtMost(5.dp.toPx()))
        fun y(v: Double) = (chartH - v / top * chartH).toFloat()
        values.forEachIndexed { i, v ->
            val cx = slot * i + slot / 2
            val on = i == selected
            if (v == null || v <= 0) {
                val stub = 3.dp.toPx()
                drawRoundRect(c.track, Offset(cx - barW / 2, chartH - stub), androidx.compose.ui.geometry.Size(barW, stub), CornerRadius(stub / 2))
            } else {
                val yTop = y(v * progress)
                drawRoundRect(
                    if (on) c.accent else neutral, Offset(cx - barW / 2, yTop),
                    androidx.compose.ui.geometry.Size(barW, (chartH - yTop).coerceAtLeast(2.dp.toPx())), radius,
                )
            }
            // Up to a week: every day. Longer: only the first and last day, and the selected one.
            val ends = (i == 0 || i == values.lastIndex) && (selected == null || kotlin.math.abs(i - selected) > values.size / 6)
            if (labels.getOrNull(i) != null && (values.size <= 7 || on || ends)) {
                label(tm, labels[i], if (on) selStyle else axisStyle, (cx).coerceIn(14.dp.toPx(), size.width - 14.dp.toPx()), size.height - labelSpace / 2 + 2.dp.toPx(), center = true)
            }
        }
        val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
        if (targets != null && targets.size == values.size) {
            targets.forEachIndexed { i, t ->
                if (t != null) drawLine(c.textTertiary.copy(alpha = 0.8f), Offset(slot * i, y(t)), Offset(slot * (i + 1), y(t)), 1.2.dp.toPx(), pathEffect = dash)
            }
        } else target?.let {
            drawLine(c.textTertiary.copy(alpha = 0.8f), Offset(0f, y(it)), Offset(size.width, y(it)), 1.2.dp.toPx(), pathEffect = dash)
        }
        limit?.let { drawLine(c.danger.copy(alpha = 0.7f), Offset(0f, y(it)), Offset(size.width, y(it)), 1.2.dp.toPx(), pathEffect = dash) }
    }
}

/** A horizontal reference line on a [TrendLineChart] (goal weight, target). */
data class RefLine(val value: Double, val label: String, val danger: Boolean = false)

/**
 * A smooth trend line with an optional uncertainty band, raw points as dots, a thin comparison line,
 * horizontal reference lines and a dashed projection. x values are day offsets.
 */
@Composable
fun TrendLineChart(
    line: List<Pair<Float, Double>>,
    xLabels: List<Pair<Float, String>>,
    modifier: Modifier = Modifier,
    band: List<Triple<Float, Double, Double>> = emptyList(),
    dots: List<Pair<Float, Double>> = emptyList(),
    secondary: List<Pair<Float, Double>> = emptyList(),
    refs: List<RefLine> = emptyList(),
    projection: List<Pair<Float, Double>> = emptyList(),
    height: Dp = 190.dp,
    format: (Double) -> String = ::shortNumber,
) {
    val c = NutriTheme.colors
    val tm = rememberTextMeasurer()
    val axisStyle = NutriTheme.type.caption.copy(color = c.textTertiary)
    if (line.isEmpty()) {
        Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) { Text("No data yet", style = NutriTheme.type.small, color = c.textTertiary) }
        return
    }
    val all = line.map { it.second } + dots.map { it.second } + secondary.map { it.second } + refs.map { it.value } +
        projection.map { it.second } + band.flatMap { listOf(it.second, it.third) }
    val minV = all.min()
    val maxV = all.max()
    val step = niceStep((maxV - minV).coerceAtLeast(0.5), 3)
    val lo = floor(minV / step) * step
    val hi = (ceil(maxV / step) * step).let { if (it == lo) lo + step else it }
    val xs = line.map { it.first } + dots.map { it.first } + projection.map { it.first }
    val minX = xs.min()
    val maxX = xs.max().let { if (it == minX) minX + 1 else it }
    Canvas(modifier.fillMaxWidth().height(height)) {
        val left = 40.dp.toPx()
        val right = 8.dp.toPx()
        val bottomSpace = 20.dp.toPx()
        val y0 = 8.dp.toPx()
        val chartH = size.height - bottomSpace - y0
        val chartW = size.width - left - right
        fun x(v: Float) = left + (v - minX) / (maxX - minX) * chartW
        fun y(v: Double) = (y0 + chartH - ((v - lo) / (hi - lo) * chartH)).toFloat()
        var t = lo
        while (t <= hi + step * 0.001) {
            drawLine(c.border, Offset(left, y(t)), Offset(size.width - right, y(t)), 1.dp.toPx())
            label(tm, format(t), axisStyle, left - 8.dp.toPx(), y(t), alignRight = true)
            t += step
        }
        xLabels.forEach { (xv, text) ->
            val layout = tm.measure(text, axisStyle)
            val px = (x(xv) - layout.size.width / 2f).coerceIn(left, size.width - layout.size.width)
            drawText(layout, topLeft = Offset(px, size.height - bottomSpace / 2 - layout.size.height / 2f))
        }
        if (band.size >= 2) {
            val area = Path()
            band.forEachIndexed { i, (xv, _, h) -> if (i == 0) area.moveTo(x(xv), y(h)) else area.lineTo(x(xv), y(h)) }
            band.asReversed().forEach { (xv, l, _) -> area.lineTo(x(xv), y(l)) }
            area.close()
            drawPath(area, c.accent.copy(alpha = 0.14f))
        }
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
        refs.forEach { r ->
            val col = if (r.danger) c.danger.copy(alpha = 0.7f) else c.textTertiary
            drawLine(col, Offset(left, y(r.value)), Offset(size.width - right, y(r.value)), 1.2.dp.toPx(), pathEffect = dash)
            val layout = tm.measure(r.label, axisStyle)
            drawText(layout, topLeft = Offset(size.width - right - layout.size.width, y(r.value) - layout.size.height - 2.dp.toPx()))
        }
        if (secondary.size >= 2) {
            val p2 = Path()
            secondary.forEachIndexed { i, (xv, v) -> if (i == 0) p2.moveTo(x(xv), y(v)) else p2.lineTo(x(xv), y(v)) }
            drawPath(p2, c.textTertiary, style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
        }
        dots.forEach { (xv, v) -> drawCircle(c.textTertiary.copy(alpha = 0.55f), 2.4.dp.toPx(), Offset(x(xv), y(v))) }
        if (line.size >= 2) {
            val path = Path()
            line.forEachIndexed { i, (xv, v) -> if (i == 0) path.moveTo(x(xv), y(v)) else path.lineTo(x(xv), y(v)) }
            drawPath(path, c.accent, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (projection.size >= 2) {
            val pr = Path()
            projection.forEachIndexed { i, (xv, v) -> if (i == 0) pr.moveTo(x(xv), y(v)) else pr.lineTo(x(xv), y(v)) }
            drawPath(pr, c.accent.copy(alpha = 0.7f), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash))
        }
        line.last().let { (xv, v) ->
            drawCircle(c.surface, 4.dp.toPx(), Offset(x(xv), y(v)))
            drawCircle(c.accent, 3.dp.toPx(), Offset(x(xv), y(v)))
        }
    }
}

/** Small line without axes (weight on the Trends card); the last point is marked. */
@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier, height: Dp = 48.dp) {
    val c = NutriTheme.colors
    if (values.size < 2) return
    val lo = values.min()
    val hi = values.max().let { if (it - lo < 0.2) lo + 0.2 else it }
    Canvas(modifier.fillMaxWidth().height(height)) {
        val pad = 4.dp.toPx()
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        fun x(i: Int) = pad + w * i / (values.size - 1)
        fun y(v: Double) = (pad + h - (v - lo) / (hi - lo) * h).toFloat()
        val line = Path()
        values.forEachIndexed { i, v -> if (i == 0) line.moveTo(x(i), y(v)) else line.lineTo(x(i), y(v)) }
        val area = Path().apply {
            addPath(line)
            lineTo(x(values.lastIndex), size.height)
            lineTo(x(0), size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.18f), c.accent.copy(alpha = 0f))))
        drawPath(line, c.accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(c.surface, 4.dp.toPx(), Offset(x(values.lastIndex), y(values.last())))
        drawCircle(c.accent, 3.dp.toPx(), Offset(x(values.lastIndex), y(values.last())))
    }
}
