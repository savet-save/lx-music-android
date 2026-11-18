package cn.toside.music.nativeapp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    fun bind(values: List<String>) {
        val images = values.mapNotNull(::commentImageUrl).distinct()
        if (images == urls) return
        close(); removeAllViews(); thumbnails.clear(); urls = images
        images.forEachIndexed { index, url ->
            val thumbnail = Thumbnail(url, index)
            thumbnails.add(thumbnail); addView(thumbnail.root)
            if (isAttachedToWindow) thumbnail.load()
        }
    }
    private inner class Thumbnail(val url: String, index: Int) {
        val picture = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; tag = "comment-thumbnail-image"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val loading = ProgressBar(context).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(palette.primary) }
        val error = TextView(context).apply {
            text = context.getString(R.string.comments_image_error); setTextColor(palette.muted); textSize = 12f
            gravity = Gravity.CENTER; visibility = View.GONE; setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val root = FrameLayout(context).apply {
            tag = "comment-thumbnail-$index"; setBackgroundColor(palette.placeholder)
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
                    val image = artwork.loadUrl(url, maxDimension = 256, maxDownloadBytes = AppPreferences(context).commentImageMaxBytes)
                    if (image == null) error.visibility = View.VISIBLE else picture.setImageBitmap(image)
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
            item.root.layout(left, top, left + side, top + side)
        }
    }
    override fun close() { thumbnails.forEach { it.job?.cancel(); it.job = null } }
}
