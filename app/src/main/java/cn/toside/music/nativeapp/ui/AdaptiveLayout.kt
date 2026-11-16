package cn.toside.music.nativeapp.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.TextView
import android.widget.FrameLayout

/** Use the current window, so split screen and freeform windows behave like smaller devices. */
internal data class AdaptiveLayout(val widthDp: Int, val heightDp: Int) {
    val expanded = widthDp >= 600
    val sidebarWidth = if (widthDp >= 840) 184 else 152
    val contentWidth = widthDp - if (expanded) sidebarWidth else 0
    val albumColumn = contentWidth >= 620
    val splitPlayer = expanded && heightDp >= 480
    val compactPlayer = heightDp < 480
}

/** Fit the square artwork during measurement, including the first frame after resizing. */
internal class AlbumCoverLayout(context: Context, private val expanded: Boolean = false) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        val metadata = getChildAt(1)?.takeIf { it.visibility != GONE }
        val maxWidth = (availableWidth * if (expanded) .72f else .80f).toInt()
        metadata?.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val reserve = if (expanded) (48 * density).toInt() + (metadata?.measuredHeight ?: 0) else (24 * density).toInt()
        val size = minOf(maxWidth, availableHeight - reserve, ((if (expanded) 340 else 420) * density).toInt()).coerceAtLeast(0)
        getChildAt(0)?.layoutParams?.apply { width = size; height = size }
        metadata?.layoutParams?.width = size
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val album = getChildAt(0) ?: return
        val x = (width - album.measuredWidth) / 2
        val y = if (expanded) (32 * resources.displayMetrics.density).toInt() else (height - album.measuredHeight) / 2
        album.layout(x, y, x + album.measuredWidth, y + album.measuredHeight)
        getChildAt(1)?.takeIf { it.visibility != GONE }?.let {
            val next = y + album.measuredHeight + (12 * resources.displayMetrics.density).toInt()
            it.layout(x, next, x + album.measuredWidth, next + it.measuredHeight)
        }
    }
}

/** Shared LX surfaces and touch feedback for the compact and expanded layouts. */
internal class LxStyles(private val context: Context, private val palette: UiPalette, private val icons: Typeface) {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    val selected get() = blend(palette.surface, palette.primary, if (palette.theme.dark) .22f else .10f)
    val panel get() = blend(palette.surface, palette.primary, if (palette.theme.dark) .06f else .025f)

    fun shape(color: Int, radius: Int = 6, border: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        border?.let { setStroke(dp(1), it) }
    }
    fun touch(color: Int = Color.TRANSPARENT, radius: Int = 6) = RippleDrawable(
        ColorStateList.valueOf(blend(palette.surface, palette.primary, .18f)), shape(color, radius), shape(Color.WHITE, radius)
    )
    fun label(value: CharSequence, size: Float = 14f, color: Int = palette.foreground) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    fun icon(code: Int, label: String, size: Float = 21f, click: () -> Unit) = label(code.toChar().toString(), size).apply {
        typeface = icons; gravity = Gravity.CENTER; contentDescription = label
        background = touch(); isFocusable = true; setOnClickListener { click() }
    }
    private fun blend(from: Int, to: Int, ratio: Float) = Color.rgb(
        (Color.red(from) * (1 - ratio) + Color.red(to) * ratio).toInt(),
        (Color.green(from) * (1 - ratio) + Color.green(to) * ratio).toInt(),
        (Color.blue(from) * (1 - ratio) + Color.blue(to) * ratio).toInt()
    )
}
