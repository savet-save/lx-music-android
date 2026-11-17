package cn.toside.music.nativeapp.ui

import kotlin.math.min

/** Image coordinates are independent of screen density; zoom is relative to the full-image fit. */
internal class ImageViewport {
    data class Position(val zoom: Float = 1f, val centerX: Float = 0.5f, val centerY: Float = 0.5f)
    private var imageWidth = 0f
    private var imageHeight = 0f
    private var width = 0f
    private var height = 0f
    var zoom = 1f; private set
    var x = 0f; private set
    var y = 0f; private set
    val scale: Float get() = if (imageWidth > 0 && imageHeight > 0) min(width / imageWidth, height / imageHeight) * zoom else 0f
    val scaledWidth get() = imageWidth * scale
    val scaledHeight get() = imageHeight * scale

    fun image(width: Int, height: Int) {
        imageWidth = width.coerceAtLeast(0).toFloat(); imageHeight = height.coerceAtLeast(0).toFloat()
        restore(Position())
    }
    fun resize(width: Int, height: Int) {
        val position = position()
        this.width = width.coerceAtLeast(0).toFloat(); this.height = height.coerceAtLeast(0).toFloat()
        restore(position)
    }
    fun position() = Position(zoom,
        if (scaledWidth > 0) ((width / 2 - x) / scaledWidth).coerceIn(0f, 1f) else 0.5f,
        if (scaledHeight > 0) ((height / 2 - y) / scaledHeight).coerceIn(0f, 1f) else 0.5f)
    fun restore(position: Position) {
        zoom = position.zoom.takeIf(Float::isFinite)?.coerceIn(1f, 8f) ?: 1f
        x = width / 2 - (position.centerX.takeIf(Float::isFinite) ?: 0.5f).coerceIn(0f, 1f) * scaledWidth
        y = height / 2 - (position.centerY.takeIf(Float::isFinite) ?: 0.5f).coerceIn(0f, 1f) * scaledHeight
        constrain()
    }
    fun scaleBy(factor: Float, focusX: Float, focusY: Float) {
        if (!factor.isFinite() || factor <= 0 || !focusX.isFinite() || !focusY.isFinite() || scale <= 0) return
        val previous = zoom
        zoom = (zoom * factor).coerceIn(1f, 8f)
        val ratio = zoom / previous
        x = focusX - (focusX - x) * ratio; y = focusY - (focusY - y) * ratio
        constrain()
    }
    fun panBy(dx: Float, dy: Float) {
        if (!dx.isFinite() || !dy.isFinite()) return
        x += dx; y += dy; constrain()
    }
    private fun constrain() {
        x = if (scaledWidth <= width) (width - scaledWidth) / 2 else x.coerceIn(width - scaledWidth, 0f)
        y = if (scaledHeight <= height) (height - scaledHeight) / 2 else y.coerceIn(height - scaledHeight, 0f)
    }
}
