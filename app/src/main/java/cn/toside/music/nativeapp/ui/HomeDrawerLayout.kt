package cn.toside.music.nativeapp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import cn.toside.music.nativeapp.R
import kotlin.math.abs

/** Covers the workspace while leaving the mini player outside the drawer's touch area. */
// Created in code; drawer position is an explicit physical left/right preference.
@SuppressLint("ViewConstructor", "RtlHardcoded")
internal class HomeDrawerLayout(context: Context, private val content: View, private val panel: View, private val onRight: Boolean) : FrameLayout(context) {
    private val scrim = View(context).apply {
        tag = "home-drawer-scrim"
        setBackgroundColor(Color.argb(140, 0, 0, 0))
        contentDescription = context.getString(R.string.layout_close_navigation)
        isFocusable = true
        setOnClickListener { close() }
    }
    private val overlay = FrameLayout(context).apply {
        tag = "home-drawer-overlay"; visibility = View.GONE
        addView(scrim, LayoutParams(-1, -1))
        addView(panel, LayoutParams(0, -1, if (onRight) Gravity.RIGHT else Gravity.LEFT))
    }
    private var previousFocus: View? = null
    private var backDispatcher: OnBackInvokedDispatcher? = null
    private val backCallback = if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { close() } else null
    private var startX = 0f
    private var startY = 0f
    private var swiping = false
    private var closing = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    val isOpen get() = overlay.visibility == View.VISIBLE

    init {
        tag = "home-drawer-host"
        panel.elevation = 8 * resources.displayMetrics.density
        panel.isClickable = true
        panel.isFocusableInTouchMode = true
        addView(content, LayoutParams(-1, -1))
        addView(overlay, LayoutParams(-1, -1))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val panelWidth = minOf((MeasureSpec.getSize(widthMeasureSpec) * .7f).toInt(), (320 * resources.displayMetrics.density).toInt())
        if (panel.layoutParams.width != panelWidth) panel.layoutParams.width = panelWidth
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun open(animate: Boolean = true) {
        if (isOpen && !closing) return
        previousFocus = rootView.findFocus()
        closing = false
        overlay.visibility = View.VISIBLE
        content.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        panel.animate().withEndAction(null).cancel(); scrim.animate().cancel()
        panel.translationX = if (animate) (if (onRight) 1 else -1) * panel.layoutParams.width.toFloat() else 0f
        scrim.alpha = if (animate) 0f else 1f
        panel.requestFocus()
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback?.let { callback ->
                findOnBackInvokedDispatcher()?.let { dispatcher ->
                    dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
                    backDispatcher = dispatcher
                }
            }
        }
        if (animate) {
            panel.animate().translationX(0f).setDuration(200).start()
            scrim.animate().alpha(1f).setDuration(200).start()
        }
    }

    fun close(animate: Boolean = true) {
        if (!isOpen) return
        panel.animate().withEndAction(null).cancel(); scrim.animate().cancel()
        closing = true
        swiping = false
        content.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
        previousFocus?.requestFocus(); previousFocus = null
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let { backDispatcher?.unregisterOnBackInvokedCallback(it) }
        backDispatcher = null
        if (animate) {
            panel.animate().translationX((if (onRight) 1 else -1) * panel.width.toFloat()).setDuration(180)
                .withEndAction { overlay.visibility = View.GONE; closing = false }.start()
            scrim.animate().alpha(0f).setDuration(180).start()
        } else { overlay.visibility = View.GONE; closing = false }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isOpen && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) close()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!isOpen) return super.onInterceptTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX = event.x; startY = event.y; swiping = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - startX
                val towardEdge = if (onRight) dx else -dx
                if (towardEdge > touchSlop * 2 && towardEdge > abs(event.y - startY) * 2) { swiping = true; return true }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> swiping = false
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!swiping) return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) close()
        else if (event.actionMasked == MotionEvent.ACTION_CANCEL) swiping = false
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        close(false)
        super.onDetachedFromWindow()
    }
}
