package cn.toside.music.nativeapp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import cn.toside.music.nativeapp.R

internal class ZoomableImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    internal val viewport = ImageViewport()
    private var bitmap: Bitmap? = null
    private var pendingPosition: ImageViewport.Position? = null
    private val transform = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f
    private val scaling = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            viewport.scaleBy(detector.scaleFactor, detector.focusX, detector.focusY); invalidate(); return true
        }
    }).apply { isQuickScaleEnabled = false }
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapUp(event: MotionEvent): Boolean = performClick()
        override fun onDoubleTap(event: MotionEvent): Boolean {
            if (viewport.zoom > 1.01f) viewport.restore(ImageViewport.Position())
            else viewport.scaleBy(2.5f, event.x, event.y)
            invalidate(); return true
        }
    })
    init { tag = "comment-image-zoom"; isClickable = true; isFocusable = true; contentDescription = context.getString(R.string.comments_image_gestures) }

    fun image(value: Bitmap) {
        if (bitmap === value) return
        bitmap = value; viewport.image(value.width, value.height)
        restorePending(); invalidate()
    }
    fun restore(position: ImageViewport.Position) { pendingPosition = position; restorePending() }
    private fun restorePending() {
        if (bitmap == null || width <= 0 || height <= 0) return
        pendingPosition?.let { viewport.restore(it); pendingPosition = null }; invalidate()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); viewport.resize(w, h); restorePending()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let { image ->
            transform.setScale(viewport.scale, viewport.scale); transform.postTranslate(viewport.x, viewport.y)
            canvas.drawBitmap(image, transform, paint)
        }
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    @SuppressLint("ClickableViewAccessibility") // GestureDetector.onSingleTapUp delegates to performClick.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        scaling.onTouchEvent(event); gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointer = event.getPointerId(0); lastX = event.x; lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> pointer = MotionEvent.INVALID_POINTER_ID
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !scaling.isInProgress) {
                val index = event.findPointerIndex(pointer)
                if (index >= 0) {
                    val x = event.getX(index); val y = event.getY(index)
                    viewport.panBy(x - lastX, y - lastY); lastX = x; lastY = y; invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val index = if (event.actionIndex == 0) 1 else 0
                pointer = event.getPointerId(index); lastX = event.getX(index); lastY = event.getY(index)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pointer = MotionEvent.INVALID_POINTER_ID; parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
