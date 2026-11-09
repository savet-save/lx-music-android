package cn.toside.music.nativeapp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.doOnLayout
import androidx.core.view.isEmpty
import kotlin.math.abs

@SuppressLint("ViewConstructor") // This view is built in code with its playback callbacks.
internal class PlayerLyricScroll(
    context: Context,
    private val previewColor: Int,
    private val onPreviewChanged: () -> Unit,
    private val onSeek: (Int) -> Unit,
) : ScrollView(context) {
    val lines = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    var previewIndex: Int? = null
        private set

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var tracking = false
    private var moved = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var initialScroll = 0
    private var followingIndex = -1

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(lines, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Give the first and last lines room to reach the selection point in the middle.
        val inset = MeasureSpec.getSize(heightMeasureSpec) / 2
        if (lines.paddingTop != inset) lines.setPadding(lines.paddingLeft, inset, lines.paddingRight, inset)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                downX = event.x
                downY = event.y
                initialScroll = scrollY
            }
            MotionEvent.ACTION_MOVE -> if (tracking && !moved) {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                moved = maxOf(dx, dy) > touchSlop
                dragging = moved && dy >= dx
            }
            MotionEvent.ACTION_POINTER_DOWN -> cancelPreview()
        }
        val handled = super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (dragging) updatePreview(centeredLine())
            MotionEvent.ACTION_UP -> {
                val target = previewIndex.takeIf { dragging && scrollY != initialScroll }
                val tap = tracking && !moved
                cancelPreview()
                if (target != null) onSeek(target) else {
                    follow(followingIndex)
                    if (tap) performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelPreview()
                follow(followingIndex)
            }
        }
        return handled
    }

    override fun performClick(): Boolean = super.performClick()

    // The gesture selects a timestamp at release, so no inertial scroll is needed.
    override fun fling(velocityY: Int) = Unit

    fun cancelPreview() {
        tracking = false
        moved = false
        dragging = false
        updatePreview(null)
    }

    fun follow(index: Int) {
        followingIndex = index
        val row = lines.getChildAt(index) ?: return
        if (tracking || !isShown) return
        doOnLayout {
            if (!tracking && isShown && row.parent === lines) {
                smoothScrollTo(0, (row.top + row.height / 2 - height / 2).coerceAtLeast(0))
            }
        }
    }

    private fun centeredLine(): Int? {
        if (lines.isEmpty()) return null
        val center = scrollY + height / 2
        var first = 0
        var last = lines.childCount - 1
        while (first < last) {
            val middle = (first + last) / 2
            if (lines.getChildAt(middle).bottom <= center) first = middle + 1 else last = middle
        }
        return first
    }

    private fun updatePreview(index: Int?) {
        if (previewIndex == index) return
        previewIndex?.let { lines.getChildAt(it)?.background = null }
        previewIndex = index
        index?.let { lines.getChildAt(it)?.setBackgroundColor(previewColor) }
        onPreviewChanged()
    }
}
