package cn.toside.music.nativeapp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
import cn.toside.music.nativeapp.player.EqualizerState
import kotlin.math.ln

/** Plots the configured band gains on a logarithmic frequency axis. */
@SuppressLint("ViewConstructor") // The chart is created with its palette and bands in code.
internal class EqualizerGainChart(
    context: Context,
    private val styles: LxStyles,
    private val palette: UiPalette,
    private val accent: Int,
    state: EqualizerState,
    private val frequencyLabels: List<String>,
    private val gainLabel: (Int) -> String,
    wide: Boolean,
) : View(context) {
    private val range = state.gainRange
    private val gains = state.bands.map { it.gainMillibels }.toIntArray()
    private val order = state.bands.indices.sortedBy { state.bands[it].frequencyMillihertz }
    private val frequencies = order.map { ln(state.bands[it].frequencyMillihertz.coerceAtLeast(1).toDouble()) }
    private val ticks = (if (wide) listOf(range.last, range.last / 2, 0, range.first / 2, range.first)
        else listOf(range.last, 0, range.first)).distinct()
    private val tickLabels = ticks.map(gainLabel)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labels = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.blendARGB(palette.foreground, palette.surface, .18f)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, if (wide) 11f else 10f, resources.displayMetrics)
    }
    private val area = Paint(Paint.ANTI_ALIAS_FLAG)
    private val curve = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.STROKE
        strokeWidth = styles.dp(2).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val point = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePath = Path()
    private val fillPath = Path()
    private val xPositions = FloatArray(frequencies.size)
    private val labelWidths = frequencyLabels.map(labels::measureText)
    private val left = tickLabels.maxOf(labels::measureText) + styles.dp(17)
    private val top = styles.dp(12).toFloat()
    private var right = left
    private var bottom = top

    init {
        tag = "settings-equalizer-chart"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun updateGain(index: Int, gain: Int) {
        if (gains[index] == gain) return
        gains[index] = gain
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        right = w - styles.dp(10) - labelWidths[order.last()] / 2f
        bottom = h - labels.fontSpacing - styles.dp(11)
        if (right <= left || bottom <= top) return
        val low = frequencies.first()
        val span = frequencies.last() - low
        frequencies.forEachIndexed { index, frequency ->
            val fraction = if (span == 0.0) .5f else ((frequency - low) / span).toFloat()
            xPositions[index] = left + fraction * (right - left)
        }
        area.shader = LinearGradient(0f, top, 0f, bottom, ColorUtils.setAlphaComponent(accent, if (palette.theme.dark) 55 else 35),
            ColorUtils.setAlphaComponent(accent, 2), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (right <= left || bottom <= top) return
        labels.textAlign = Paint.Align.RIGHT
        ticks.forEachIndexed { index, tick ->
            val y = yForGain(tick)
            grid.color = if (tick == 0) ColorUtils.blendARGB(palette.divider, accent, .35f) else palette.divider
            canvas.drawLine(left, y, right, y, grid)
            canvas.drawText(tickLabels[index], left - styles.dp(7), y - (labels.ascent() + labels.descent()) / 2f, labels)
        }
        labels.textAlign = Paint.Align.CENTER
        grid.color = palette.divider
        val endLabelLeft = xPositions.last() - labelWidths[order.last()] / 2f
        var previousLabelRight = Float.NEGATIVE_INFINITY
        xPositions.forEachIndexed { index, x ->
            canvas.drawLine(x, top, x, bottom, grid)
            val labelIndex = order[index]
            val halfWidth = labelWidths[labelIndex] / 2f
            if (index == xPositions.lastIndex || (x - halfWidth >= previousLabelRight + styles.dp(5) &&
                    x + halfWidth + styles.dp(5) <= endLabelLeft)) {
                canvas.drawText(frequencyLabels[labelIndex], x, bottom + styles.dp(6) - labels.ascent(), labels)
                previousLabelRight = x + halfWidth
            }
        }

        linePath.rewind()
        linePath.moveTo(left, yForGain(gains[order.first()]))
        linePath.lineTo(xPositions.first(), yForGain(gains[order.first()]))
        for (index in 1 until order.size) {
            val previousX = xPositions[index - 1]
            val x = xPositions[index]
            val middle = (previousX + x) / 2f
            val y = yForGain(gains[order[index]])
            linePath.cubicTo(middle, yForGain(gains[order[index - 1]]), middle, y, x, y)
        }
        linePath.lineTo(right, yForGain(gains[order.last()]))
        fillPath.set(linePath)
        fillPath.lineTo(right, bottom)
        fillPath.lineTo(left, bottom)
        fillPath.close()
        canvas.drawPath(fillPath, area)
        canvas.drawPath(linePath, curve)
        xPositions.forEachIndexed { index, x ->
            val y = yForGain(gains[order[index]])
            point.color = accent
            canvas.drawCircle(x, y, styles.dp(4).toFloat(), point)
            point.color = palette.surface
            canvas.drawCircle(x, y, styles.dp(2).toFloat(), point)
        }
    }

    private fun yForGain(gain: Int) = bottom - (gain - range.first).toFloat() / (range.last - range.first) * (bottom - top)
}
