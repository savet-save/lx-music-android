package cn.toside.music.nativeapp.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import cn.toside.music.nativeapp.data.Artwork
import cn.toside.music.nativeapp.online.OnlinePlaylist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Shared cover cells for browsing and searching, with bounded image work and stable recycled views. */
internal class PlaylistCoversAdapter(
    private val context: Context,
    private val styles: LxStyles,
    private val palette: UiPalette,
    parentScope: CoroutineScope,
    private val showSource: Boolean = false,
) : BaseAdapter(), AutoCloseable {
    var items = emptyList<OnlinePlaylist>()
    private val work = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val covers = Semaphore(4)
    private val artwork = Artwork(context.applicationContext)
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val card = (convertView as? CoverCard) ?: CoverCard(context)
        val item = getItem(position)
        if (card.title.text.toString() != item.name) card.title.text = item.name
        if (card.source.text.toString() != item.source) card.source.text = item.source
        card.contentDescription = listOf(item.name, item.author, if (showSource) UiPreferences.sourceName(context, item.source) else "").filter(String::isNotBlank).joinToString(" · ")
        card.tag = "playlist-card-${item.source}-${item.id}"
        if (card.cover.tag != item.coverUrl) { card.job?.cancel(); card.cover.show(null); card.cover.tag = item.coverUrl }
        // Measurement also binds cells. Keep a loaded image even after its LRU entry is evicted.
        if (card.cover.drawable != null) return card
        val cached = memory.get(item.coverUrl)
        if (cached != null) card.cover.show(cached)
        else if (item.coverUrl.isNotEmpty() && card.job?.isActive != true) card.job = work.launch {
            try {
                val bitmap = covers.withPermit { artwork.loadUrl(item.coverUrl) }
                if (bitmap != null) {
                    memory.put(item.coverUrl, bitmap)
                    if (card.cover.tag == item.coverUrl) card.cover.show(bitmap)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Keep the placeholder and the playlist clickable. */ }
        }
        return card
    }
    private inner class CoverCard(context: Context) : LinearLayout(context) {
        var job: Job? = null
        val cover = FixedCoverView(context)
        val title = styles.label("", 13.5f).apply {
            tag = "playlist-card-title"; maxLines = 2; minLines = 2; ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.TOP; setPadding(0, styles.dp(5), 0, 0)
        }
        val source = styles.label("", 11f, Color.WHITE).apply {
            tag = "playlist-card-source"; gravity = Gravity.CENTER
            setPadding(styles.dp(3), styles.dp(1), styles.dp(3), styles.dp(1))
            setBackgroundColor(Color.argb(140, 0, 0, 0)); visibility = if (showSource) View.VISIBLE else View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        init {
            orientation = VERTICAL
            val square = FrameLayout(context).apply {
                background = styles.shape(palette.placeholder, 5); clipToOutline = true
                addView(styles.label("LX", 22f, palette.primary).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
                addView(cover, FrameLayout.LayoutParams(-1, -2))
                addView(source, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
            }
            addView(square, LayoutParams(-1, -2)); addView(title, LayoutParams(-1, -2))
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }
    private class FixedCoverView(context: Context) : ImageView(context) {
        private var updatingPixels = false
        private var shown: Bitmap? = null
        init { scaleType = ScaleType.CENTER_CROP; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        fun show(bitmap: Bitmap?) {
            if (shown === bitmap) return
            shown = bitmap; updatingPixels = true
            try { if (bitmap == null) setImageDrawable(null) else setImageBitmap(bitmap) }
            finally { updatingPixels = false }
        }
        override fun requestLayout() { if (!updatingPixels) super.requestLayout() }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY))
        }
    }
    override fun close() { work.cancel(); memory.evictAll() }
}
