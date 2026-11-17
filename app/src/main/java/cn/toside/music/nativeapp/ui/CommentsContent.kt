package cn.toside.music.nativeapp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Parcelable
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.Artwork
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.online.MusicComment
import cn.toside.music.nativeapp.utils.ToastUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Native read-only comment rows. Like counts deliberately have no action or touch feedback. */
@SuppressLint("ViewConstructor") // Created with page actions, never inflated from XML.
internal class CommentsContent(context: Context, private val scope: CoroutineScope, private val palette: UiPalette,
    private val replyName: String?, close: () -> Unit, select: (Boolean) -> Unit, refresh: () -> Unit,
    private val more: () -> Unit, private val replies: (Song, MusicComment) -> Unit) : FrameLayout(context), AutoCloseable {
    private val styles = LxStyles(context, palette, Typeface.createFromAsset(context.assets, "fonts/icomoon.ttf"))
    private fun dp(value: Int) = styles.dp(value)
    private fun label(value: String = "", size: Float = 15f, color: Int = palette.foreground) = styles.label(value, size, color).apply {
        includeFontPadding = false; maxLines = Int.MAX_VALUE; ellipsize = null
    }
    private val title = label(size = 19f).apply { tag = "comments-title"; gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val hot = label(size = 17f).apply { tag = "comments-hot" }
    private val latest = label(size = 17f).apply { tag = "comments-latest" }
    private val source = label(size = 12f, color = palette.muted).apply { tag = "comments-source"; setPadding(dp(16), dp(8), dp(16), dp(8)) }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(palette.primary)
    }
    private val message = label(size = 14f, color = palette.muted).apply {
        tag = "comments-status"; gravity = Gravity.CENTER; setPadding(dp(24), dp(16), dp(24), dp(16)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val retry = label(context.getString(R.string.ui_b8784c8dd563), 14f, palette.primary).apply {
        tag = "comments-retry"; gravity = Gravity.CENTER; minHeight = dp(48); background = styles.touch()
        isFocusable = true; setOnClickListener { more() }
    }
    private val footer = label(size = 14f, color = palette.primary).apply {
        tag = "comments-more"; gravity = Gravity.CENTER; setPadding(dp(16), dp(16), dp(16), dp(16))
        minHeight = dp(48); setOnClickListener { more() }
    }
    internal val list = ListView(context).apply {
        tag = "comments-list"; divider = null; isVerticalScrollBarEnabled = false; clipToPadding = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }
    private val rows = CommentRows()
    private var state: CommentsState? = null
    private var pendingRestore: Parcelable? = null

    init {
        tag = "comments-page"; setBackgroundColor(palette.surface)
        val column = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(
                MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(widthMeasureSpec), dp(960)), MeasureSpec.EXACTLY), heightMeasureSpec)
        }.apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(context).apply { tag = "comments-header"; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        header.addView(styles.backButton(close), LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Space(context), LinearLayout.LayoutParams(dp(48), dp(48)))
        column.addView(header)
        val tabs = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        for ((view, selected) in listOf(hot to true, latest to false)) {
            view.maxLines = 1; view.ellipsize = TextUtils.TruncateAt.END
            view.gravity = Gravity.CENTER; view.minHeight = dp(48); view.setPadding(dp(10), dp(10), dp(10), dp(10))
            view.background = styles.touch(); view.isFocusable = true; view.setOnClickListener { select(selected) }
            tabs.addView(view, LinearLayout.LayoutParams(-2, -2))
            view.visibility = if (replyName == null) View.VISIBLE else View.GONE
        }
        tabs.addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f))
        tabs.addView(label("↻", 27f, palette.muted).apply {
            tag = "comments-refresh"; contentDescription = context.getString(R.string.comments_refresh)
            gravity = Gravity.CENTER; background = styles.touch(); isFocusable = true; setOnClickListener { refresh() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        column.addView(tabs)
        column.addView(View(context).apply { setBackgroundColor(palette.divider) }, LinearLayout.LayoutParams(-1, dp(1)))
        column.addView(progress, LinearLayout.LayoutParams(-1, dp(2)))
        column.addView(source, LinearLayout.LayoutParams(-1, -2))
        val body = FrameLayout(context)
        list.addFooterView(footer); list.adapter = rows
        body.addView(list, LayoutParams(-1, -1))
        val empty = LinearLayout(context).apply { tag = "comments-empty"; orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; addView(message); addView(retry) }
        body.addView(empty, LayoutParams(-1, -1))
        column.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(column, LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        palette.textStyle(this)
    }

    internal fun restoreListState(value: Parcelable?) { pendingRestore = value }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = minOf(MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight, dp(960))
        val maxTabWidth = ((width - dp(64)) / 2).coerceAtLeast(dp(40))
        hot.maxWidth = maxTabWidth; latest.maxWidth = maxTabWidth
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    internal fun saveListState(): Parcelable? = list.onSaveInstanceState()
    internal fun resetScroll() { pendingRestore = null; list.setSelection(0) }
    internal fun render(value: CommentsState) {
        state = value
        title.text = if (replyName == null) context.getString(R.string.comments_title, value.requested.title)
            else context.getString(R.string.ui_04348c8824f5, replyName)
        for ((view, selected, resource) in listOf(Triple(hot, true, R.string.comments_hot), Triple(latest, false, R.string.comments_latest))) {
            val name = context.getString(resource)
            val count = value.totals[selected]?.takeIf { it != Long.MAX_VALUE }
            view.text = if (count == null) name else context.getString(R.string.comments_tab_count, name, count)
            view.isSelected = value.hot == selected; view.setTextColor(if (view.isSelected) palette.primary else palette.foreground)
        }
        source.text = if (value.source.source != value.requested.source) context.getString(R.string.comments_fallback, UiPreferences.sourceName(context, value.source.source)) else ""
        source.visibility = if (source.text.isEmpty()) View.GONE else View.VISIBLE
        progress.visibility = if (value.loading) View.VISIBLE else View.INVISIBLE
        val empty = value.items.isEmpty()
        list.visibility = if (empty) View.GONE else View.VISIBLE
        findViewWithTag<View>("comments-empty").visibility = if (empty) View.VISIBLE else View.GONE
        message.text = when {
            value.error != null -> context.getString(R.string.ui_e6e79ef974ed, value.error)
            value.loading -> context.getString(R.string.ui_21bd738e0d71)
            else -> context.getString(R.string.comments_empty)
        }
        retry.visibility = if (!value.loading && value.error != null) View.VISIBLE else View.GONE
        if (rows.items !== value.items) {
            val position = list.firstVisiblePosition; val top = list.getChildAt(0)?.top ?: 0
            rows.items = value.items; rows.notifyDataSetChanged()
            if (value.page > 1) list.setSelectionFromTop(position, top)
            if (!empty && pendingRestore != null) { list.onRestoreInstanceState(pendingRestore); pendingRestore = null }
        }
        footer.text = when {
            value.loading -> context.getString(R.string.ui_21bd738e0d71)
            value.error != null -> context.getString(R.string.ui_e6e79ef974ed, value.error) + "\n" + context.getString(R.string.ui_b8784c8dd563)
            value.hasMore -> context.getString(R.string.ui_af90a08fec8e)
            else -> context.getString(R.string.ui_06d9bbeb3af9)
        }
        footer.isEnabled = !value.loading && (value.hasMore || value.error != null)
        footer.isClickable = footer.isEnabled
        footer.isFocusable = footer.isEnabled
        footer.setTextColor(if (footer.isEnabled) palette.primary else palette.muted)
    }
    override fun close() { rows.close() }

    private inner class CommentRows : BaseAdapter(), AutoCloseable {
        var items = emptyList<MusicComment>()
        private val artwork = Artwork(context)
        private val requests = java.util.WeakHashMap<ImageView, Job>()
        private val cache = object : LruCache<String, Bitmap>(2 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = false
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder = (convertView?.tag as? CommentRow) ?: CommentRow()
            holder.bind(items[position])
            return holder.root
        }
        private inner class CommentRow(private val depth: Int = 0) {
            val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = this@CommentRow }
            private val initial = label(size = 18f, color = palette.primary).apply { gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            private val picture = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            private val name = label(size = 15f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            private val date = label(size = 12f, color = palette.muted)
            private val likes = label(size = 12f, color = palette.muted).apply {
                isClickable = false; isLongClickable = false; isFocusable = false
                maxLines = 1; maxWidth = dp(112); ellipsize = TextUtils.TruncateAt.END
                compoundDrawablePadding = dp(2)
                setCompoundDrawablesRelative(context.getDrawable(R.drawable.ic_comment_like)?.mutate()?.apply {
                    setTint(palette.muted); setBounds(0, 0, dp(14), dp(14))
                }, null, null, null)
            }
            private val text = label(size = 16f).apply { setLineSpacing(dp(4).toFloat(), 1f); setTextIsSelectable(true) }
            private val extra = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            private val thread = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
            private val replyRows = mutableListOf<CommentRow>()
            init {
                val line = LinearLayout(context).apply { gravity = Gravity.TOP; setPadding(dp(16), dp(15), dp(16), dp(15)) }
                val avatar = FrameLayout(context).apply {
                    background = GradientDrawable().apply { setColor(palette.placeholder); cornerRadius = dp(5).toFloat() }
                    clipToOutline = true
                    addView(initial, LayoutParams(-1, -1)); addView(picture, LayoutParams(-1, -1))
                }
                line.addView(avatar, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
                val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val top = LinearLayout(context).apply { gravity = Gravity.TOP }
                val author = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; addView(name)
                    addView(date, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
                }
                top.addView(author, LinearLayout.LayoutParams(0, -2, 1f))
                top.addView(likes, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8); topMargin = dp(10) })
                content.addView(top)
                content.addView(text, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                content.addView(extra)
                line.addView(content, LinearLayout.LayoutParams(0, -2, 1f))
                root.addView(line)
                root.addView(thread, LinearLayout.LayoutParams(-1, -2))
                root.addView(View(context).apply { setBackgroundColor(palette.divider) }, LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(64); marginEnd = dp(16) })
            }
            fun bind(item: MusicComment) {
                root.contentDescription = null
                name.text = item.name.ifBlank { context.getString(R.string.comments_anonymous) }; name.tag = "comment-author-${item.id}"
                date.text = listOf(formatDate(item.date), item.location).filter(String::isNotBlank).joinToString(" · ")
                date.visibility = if (date.text.isBlank()) View.GONE else View.VISIBLE
                text.text = item.text; text.tag = "comment-body-${item.id}"
                likes.text = String.format(context.resources.configuration.locales[0], "%d", item.likes.coerceAtLeast(0)); likes.tag = "comment-likes-${item.id}"
                likes.contentDescription = context.getString(R.string.comments_likes, item.likes.coerceAtLeast(0))
                initial.text = name.text.firstOrNull()?.toString().orEmpty()
                val url = item.avatar
                if (picture.tag != url) {
                    requests.remove(picture)?.cancel(); picture.tag = url; picture.setImageDrawable(null)
                    if (url.isNotBlank()) {
                        val cached = cache.get(url)
                        if (cached != null) picture.setImageBitmap(cached)
                        else requests[picture] = scope.launch {
                            try {
                                val bitmap = artwork.loadUrl(url)
                                if (bitmap != null) { cache.put(url, bitmap); if (picture.tag == url) picture.setImageBitmap(bitmap) }
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { }
                        }
                    }
                }
                extra.removeAllViews()
                for (urlValue in item.images) {
                    val urlText = if (urlValue.startsWith("//")) "https:$urlValue" else urlValue
                    if (!urlText.startsWith("https://") && !urlText.startsWith("http://")) continue
                    extra.addView(label(context.getString(R.string.ui_475d38917292, (item.images.indexOf(urlValue) + 1).toString()), 13f, palette.primary).apply {
                        minHeight = dp(40); gravity = Gravity.CENTER_VERTICAL; background = styles.touch(); isFocusable = true
                        setOnClickListener { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(urlText))) }.onFailure { ToastUtils.show(context, context.getString(R.string.ui_7206c39ccf2e)) } }
                    })
                }
                for (child in listOfNotNull(item.quoted)) extra.addView(label("${child.name}: ${child.text}", 14f, palette.muted).apply {
                    setTextIsSelectable(true); setPadding(dp(10), dp(8), dp(8), dp(8)); setLineSpacing(dp(3).toFloat(), 1f)
                    setBackgroundColor(palette.placeholder)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                thread.removeAllViews()
                if (depth == 0) item.replies.forEachIndexed { index, child ->
                    val row = replyRows.getOrNull(index) ?: CommentRow(depth + 1).also { replyRows.add(it) }
                    row.bind(child); thread.addView(row.root, LinearLayout.LayoutParams(-1, -2))
                }
                val current = state?.source
                if (depth == 0 && item.replyCount > 0 && current?.source in setOf("kg", "mg", "bili") && replyName == null) {
                    thread.addView(label(context.getString(R.string.comments_view_replies, item.replyCount), 13f, palette.primary).apply {
                        tag = "comment-replies-${item.id}"; minHeight = dp(40); gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(48), dp(8), dp(16), dp(12))
                        background = styles.touch(); isFocusable = true; setOnClickListener { replies(requireNotNull(current), item) }
                    })
                }
            }
        }
        override fun close() { requests.values.toList().forEach(Job::cancel); requests.clear(); cache.evictAll() }
    }
    companion object {
        internal fun formatDate(value: String): String = value.toLongOrNull()?.takeIf { it > 0 }?.let {
            val millis = if (it < 10_000_000_000L) it * 1000 else it
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
        } ?: value
    }
}
