package cn.toside.music.nativeapp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.Artwork
import cn.toside.music.nativeapp.data.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal fun commentImageUrl(value: String): String? {
    val url = value.trim().let { if (it.startsWith("//")) "https:$it" else it }
    val parsed = Uri.parse(url)
    return url.takeIf { parsed.scheme in setOf("http", "https") && !parsed.host.isNullOrBlank() }
}

@SuppressLint("ViewConstructor")
internal class CommentImageThumbnails(context: Context, private val scope: CoroutineScope, private val palette: UiPalette) : ViewGroup(context), AutoCloseable {
    private val artwork = Artwork(context)
    private val thumbnails = mutableListOf<Thumbnail>()
    private var urls = emptyList<String>()
    private val thumbnailOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, dp(5).toFloat())
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    fun bind(values: List<String>) {
        val images = values.mapNotNull(::commentImageUrl).distinct()
        if (images == urls) return
        close(); removeAllViews(); thumbnails.clear(); urls = images
        images.forEachIndexed { index, url ->
            val thumbnail = Thumbnail(url, index, images.size == 1)
            thumbnails.add(thumbnail); addView(thumbnail.root)
            if (isAttachedToWindow) thumbnail.load()
        }
    }
    private inner class Thumbnail(val url: String, index: Int, private val single: Boolean) {
        val picture = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; tag = "comment-thumbnail-image"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val loading = ProgressBar(context).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(palette.primary) }
        val error = TextView(context).apply {
            text = context.getString(R.string.comments_image_error); setTextColor(palette.muted); textSize = 12f
            gravity = Gravity.CENTER; visibility = View.GONE; setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val root = FrameLayout(context).apply {
            tag = "comment-thumbnail-$index"
            outlineProvider = thumbnailOutline
            clipToOutline = true
            contentDescription = context.getString(R.string.ui_475d38917292, (index + 1).toString())
            addView(picture, FrameLayout.LayoutParams(-1, -1))
            addView(loading, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            addView(error, FrameLayout.LayoutParams(-1, -1))
            isFocusable = true; setOnClickListener { context.startActivity(CommentImageActivity.intent(context, url)) }
        }
        var job: Job? = null
        fun load() {
            if (picture.drawable != null || job?.isActive == true) return
            job = scope.launch {
                loading.visibility = View.VISIBLE; error.visibility = View.GONE
                try {
                    val image = artwork.loadUrl(url, maxDimension = if (single) 768 else 256,
                        maxDownloadBytes = AppPreferences(context).commentImageMaxBytes)
                    if (image == null) error.visibility = View.VISIBLE
                    else {
                        picture.setImageBitmap(image)
                        if (single) root.requestLayout()
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { error.visibility = View.VISIBLE }
                finally { if (isActive) loading.visibility = View.GONE }
            }
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); thumbnails.forEach(Thumbnail::load) }
    override fun onDetachedFromWindow() { close(); super.onDetachedFromWindow() }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val single = thumbnails.singleOrNull()
        if (single != null) {
            val bitmap = (single.picture.drawable as? BitmapDrawable)?.bitmap
            val maxSide = minOf(width, dp(if (bitmap == null) 112 else 240))
            val minSide = minOf(maxSide, dp(48))
            val aspect = bitmap?.let { it.width.toFloat() / it.height } ?: 1f
            val imageWidth = (maxSide * minOf(1f, aspect)).roundToInt().coerceAtLeast(minSide)
            val imageHeight = (maxSide / maxOf(1f, aspect)).roundToInt().coerceAtLeast(minSide)
            single.root.measure(MeasureSpec.makeMeasureSpec(imageWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(imageHeight, MeasureSpec.EXACTLY))
            setMeasuredDimension(width, resolveSize(imageHeight, heightMeasureSpec))
            return
        }
        val gap = dp(6)
        val columns = ((width + gap) / (dp(112) + gap)).coerceIn(1, 3)
        val side = minOf(dp(112), ((width - (columns - 1) * gap) / columns).coerceAtLeast(0))
        thumbnails.forEach { it.root.measure(MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)) }
        val rows = (childCount + columns - 1) / columns
        setMeasuredDimension(width, resolveSize(if (rows == 0) 0 else rows * side + (rows - 1) * gap, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (childCount == 0) return
        val side = getChildAt(0).measuredWidth; val gap = dp(6)
        val columns = ((width + gap) / (side + gap)).coerceIn(1, 3)
        thumbnails.forEachIndexed { index, item ->
            val left = (index % columns) * (side + gap); val top = (index / columns) * (side + gap)
            item.root.layout(left, top, left + item.root.measuredWidth, top + item.root.measuredHeight)
        }
    }
    override fun close() { thumbnails.forEach { it.job?.cancel(); it.job = null } }
}
