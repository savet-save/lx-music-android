package cn.toside.music.nativeapp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.SeekBar
import androidx.core.graphics.ColorUtils
import kotlin.math.abs

/** A rotated native SeekBar with its progress actions and keyboard handling. */
@SuppressLint("ViewConstructor") // The settings panel supplies the active palette in code.
internal class EqualizerFader(
    context: Context,
    private val styles: LxStyles,
    private val palette: UiPalette,
    private val accent: Int,
) : SeekBar(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var startX = 0f
    private var startY = 0f
    private var choosingDirection = false
    private var scrollingBank = false

    init {
        background = null
        thumb = null
        progressDrawable = null
        layoutDirection = LAYOUT_DIRECTION_LTR
        setPadding(styles.dp(9), 0, styles.dp(9), 0)
        rotation = -90f
    }

    override fun onDraw(canvas: Canvas) {
        val start = paddingLeft.toFloat()
        val end = (width - paddingRight).toFloat()
        if (end <= start || max <= min) return
        val center = height / 2f
        fun position(gain: Int) = start + (gain - min).toFloat() / (max - min) * (end - start)
        val zero = position(0)
        val value = position(progress)
        paint.color = ColorUtils.blendARGB(palette.divider, palette.muted, .25f)
        paint.strokeWidth = styles.dp(1).toFloat()
        for (tick in 0..8) {
            val x = start + (end - start) * tick / 8f
            canvas.drawLine(x, center - styles.dp(8), x, center + styles.dp(8), paint)
        }
        paint.color = ColorUtils.blendARGB(palette.divider, palette.muted, .48f)
        canvas.drawLine(zero, center - styles.dp(11), zero, center + styles.dp(11), paint)
        paint.strokeWidth = styles.dp(3).toFloat()
        canvas.drawLine(start, center, end, center, paint)
        paint.color = accent
        canvas.drawLine(zero, center, value, center, paint)
        if (isPressed || isFocused) {
            paint.color = ColorUtils.setAlphaComponent(accent, 55)
            canvas.drawCircle(value, center, styles.dp(12).toFloat(), paint)
            paint.color = accent
        }
        canvas.drawCircle(value, center, styles.dp(7).toFloat(), paint)
        paint.color = palette.surface
        canvas.drawCircle(value, center, styles.dp(5).toFloat(), paint)
        paint.color = accent
        canvas.drawCircle(value, center, styles.dp(2).toFloat(), paint)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility") // SeekBar supplies progress accessibility actions.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                choosingDirection = true
                scrollingBank = false
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (choosingDirection) {
                // Motion is mapped into the rotated slider's coordinates by its parent.
                val alongRail = abs(event.x - startX)
                val acrossBank = abs(event.y - startY)
                if (maxOf(alongRail, acrossBank) > touchSlop) {
                    choosingDirection = false
                    if (acrossBank > alongRail) {
                        scrollingBank = true
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        super.onTouchEvent(cancel)
                        cancel.recycle()
                        parent.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        return scrollingBank || super.onTouchEvent(event)
    }
}
