package cn.toside.music.nativeapp.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import cn.toside.music.nativeapp.player.PlaybackLevels
import kotlin.math.abs

/** A rotating cover over a stationary, audio-reactive record surround. */
internal class PlayerRecordView(
    context: Context,
    private val palette: UiPalette,
    placeholder: TextView,
    private val readLevel: () -> Float,
    private val onMotionChanged: (Boolean) -> Unit,
) : FrameLayout(context) {
    val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = View.GONE
    }
    private val disc = FrameLayout(context).apply {
        tag = "player-disc"
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(palette.placeholder) }
        clipToOutline = true
        addView(placeholder, LayoutParams(-1, -1))
        addView(image, LayoutParams(-1, -1))
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.blendARGB(palette.surface, palette.primary, if (palette.theme.dark) .12f else .07f)
    }
    private var motionVisible = false
    private var playing = false
    private var startAngle = 0f
    private var lastSample = -PlaybackLevels.INTERVAL_MS.toLong()
    private var target = 0f
    private var intensity = 0f
    private val rotation = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 30_000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { animator ->
            disc.rotation = (startAngle + animator.animatedValue as Float) % 360f
            val elapsed = animator.currentPlayTime
            if (elapsed - lastSample >= PlaybackLevels.INTERVAL_MS) {
                target = (readLevel() * 4f).coerceIn(0f, 1f)
                lastSample = elapsed
            }
            val change = target - intensity
            if (abs(change) > .002f) {
                intensity += change * if (change > 0) .28f else .12f
                invalidate()
            }
        }
    }

    init {
        setWillNotDraw(false)
        addView(disc, LayoutParams(0, 0, Gravity.CENTER))
    }

    fun setPlaying(value: Boolean) {
        playing = value
        updateMotion()
    }

    fun resetTrack() {
        stopMotion()
        disc.rotation = 0f
        updateMotion()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        motionVisible = isVisible
        updateMotion()
    }

    override fun onDetachedFromWindow() {
        motionVisible = false
        stopMotion()
        super.onDetachedFromWindow()
    }

    private fun updateMotion() {
        if (!playing || !motionVisible || !ValueAnimator.areAnimatorsEnabled()) {
            stopMotion()
        } else if (!rotation.isStarted) {
            startAngle = disc.rotation
            lastSample = -PlaybackLevels.INTERVAL_MS.toLong()
            onMotionChanged(true)
            rotation.start()
        }
    }

    private fun stopMotion() {
        if (!rotation.isStarted) return
        rotation.cancel()
        onMotionChanged(false)
        target = 0f
        intensity = 0f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val diameter = (minOf(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec)) * .72f).toInt()
        disc.layoutParams.apply { width = diameter; height = diameter }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (minOf(w, h) == 0) return
        val accent = palette.primary
        val highlight = ColorUtils.blendARGB(accent, palette.foreground, .24f)
        val diffuse = ColorUtils.setAlphaComponent(accent, if (palette.theme.dark) 200 else 150)
        glow.shader = RadialGradient(w / 2f, h / 2f, minOf(w, h) * .50f,
            intArrayOf(diffuse, diffuse, ColorUtils.setAlphaComponent(accent, 80), Color.TRANSPARENT),
            floatArrayOf(0f, .68f, .83f, 1f), Shader.TileMode.CLAMP)
        rim.shader = SweepGradient(w / 2f, h / 2f,
            intArrayOf(accent, highlight, accent, highlight, accent), floatArrayOf(0f, .18f, .46f, .77f, 1f))
    }

    override fun onDraw(canvas: Canvas) {
        val x = width / 2f
        val y = height / 2f
        val size = minOf(x, y) * 2f
        glow.alpha = (116f + 139f * intensity).toInt()
        canvas.drawCircle(x, y, size * .5f, glow)
        canvas.drawCircle(x, y, size * .435f, plate)
        for (index in 0..4) {
            val radius = size * (.370f + index * .015f + intensity * index * .003f)
            rim.strokeWidth = size * if (index == 4) .0035f else .002f
            rim.alpha = (if (index == 4) 125 else 58) + (intensity * 110).toInt()
            canvas.drawCircle(x, y, radius, rim)
        }
    }
}
