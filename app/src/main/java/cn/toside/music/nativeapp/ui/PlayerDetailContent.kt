package cn.toside.music.nativeapp.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.Song
import androidx.core.graphics.ColorUtils
import org.json.JSONObject

internal data class PlayerDetailActions(
    val close: () -> Unit,
    val lyrics: () -> Unit,
    val previous: () -> Unit,
    val play: () -> Unit,
    val next: () -> Unit,
    val mode: () -> Unit,
    val queue: () -> Unit,
    val sleep: () -> Unit,
    val settings: () -> Unit,
    val collect: () -> Unit,
    val comments: () -> Unit,
    val more: (View) -> Unit,
)

/** The same playback views arranged for an album page on phones and a lyric desk on tablets. */
internal class PlayerDetailContent(
    context: Context,
    val adaptive: AdaptiveLayout,
    private val palette: UiPalette,
    icons: Typeface,
    val lyrics: PlayerLyricScroll,
    actions: PlayerDetailActions,
) : LinearLayout(context) {
    private val styles = LxStyles(context, palette, icons)
    private fun dp(value: Int) = styles.dp(value)
    private fun label(size: Float, color: Int = palette.foreground) = styles.label("", size, color).apply {
        includeFontPadding = false
    }
    private fun icon(code: Int, name: Int, size: Float = 23f, click: () -> Unit) =
        styles.icon(code, context.getString(name), size, click).apply {
            includeFontPadding = false; tooltipText = contentDescription
        }

    val title = label(19f).apply { tag = "player-title" }
    val artist = label(14f, palette.muted).apply { tag = "player-artist" }
    val metadata = label(12f).apply {
        tag = "player-metadata"; maxLines = 6; gravity = Gravity.TOP; setLineSpacing(dp(3).toFloat(), 1f)
        visibility = if (adaptive.splitPlayer) View.VISIBLE else View.GONE
    }
    val cover = AlbumCoverLayout(context, adaptive.splitPlayer).apply {
        tag = "player-artwork"; clipChildren = false; clipToPadding = false
        contentDescription = context.getString(R.string.ui_ccbb5ee62b1a)
        setOnClickListener { actions.lyrics() }
        setOnLongClickListener { actions.more(it); true }
    }
    val coverImage = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
    val pageToggle = icon(59697, R.string.ui_9dbfa9b42bb3, 24f, actions.lyrics).apply { tag = "player-lyrics-toggle" }
    val toggle = icon(59653, R.string.ui_c3396195e91c, if (adaptive.splitPlayer) 28f else 42f, actions.play).apply {
        tag = "player-play"; setTextColor(palette.primary)
    }
    val mode = icon(59649, R.string.player_play_mode, 24f, actions.mode).apply { tag = "player-mode" }
    val collect = icon(59670, R.string.ui_cc143006b20d, 25f, actions.collect).apply { tag = "player-collect" }
    val comments = icon(59672, R.string.ui_07f73642e525, 24f, actions.comments).apply { tag = "player-comments" }
    val more = icon(59680, R.string.ui_38844b135cf7, 24f) {}.apply {
        tag = "player-more"; setOnClickListener { actions.more(this) }
    }
    val elapsed = label(16f, palette.muted).apply { tag = "player-elapsed" }
    val duration = label(16f, palette.muted).apply { tag = "player-duration"; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
    val time = label(12f, palette.foreground).apply { tag = "player-time"; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
    val status = label(12f, palette.muted).apply { tag = "player-status"; maxLines = 2; visibility = View.GONE }
    val seek = SeekBar(context).apply {
        tag = "player-seek"; contentDescription = context.getString(R.string.ui_fc16e2a0fca6)
        val trackColor = ColorUtils.blendARGB(palette.surface, palette.primary, if (palette.theme.dark) .30f else .20f)
        fun line(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(2).toFloat() }
        progressDrawable = LayerDrawable(arrayOf(line(trackColor), ClipDrawable(line(palette.primary), Gravity.LEFT, ClipDrawable.HORIZONTAL))).apply {
            setId(0, android.R.id.background); setId(1, android.R.id.progress)
            for (index in 0..1) { setLayerHeight(index, dp(2).coerceAtLeast(1)); setLayerGravity(index, Gravity.CENTER_VERTICAL) }
        }
        progressTintList = ColorStateList.valueOf(palette.primary)
        progressBackgroundTintList = ColorStateList.valueOf(trackColor)
        thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(palette.primary)
            setSize(dp(if (adaptive.splitPlayer) 10 else 12), dp(if (adaptive.splitPlayer) 10 else 12))
        }
        thumbTintList = ColorStateList.valueOf(palette.primary)
        splitTrack = false; thumbOffset = dp(6)
        setPadding(dp(6), 0, dp(6), 0); minimumHeight = 0
    }
    private val body: PlayerBody
    private var renderedSong: Song? = null

    init {
        orientation = VERTICAL
        tag = if (adaptive.splitPlayer) "player-expanded" else "player-compact"
        clipChildren = false; clipToPadding = false
        val header = LinearLayout(context).apply {
            tag = "player-header"; gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false
            setPadding(dp(4), dp(4), dp(12), dp(4))
        }
        header.addView(styles.backButton(actions.close), LayoutParams(dp(48), dp(48)))
        val heading = LinearLayout(context).apply {
            orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL
            addView(title, LayoutParams(-1, -2)); addView(artist, LayoutParams(-1, -2).apply { topMargin = dp(5) })
            visibility = if (adaptive.splitPlayer) View.INVISIBLE else View.VISIBLE
        }
        header.addView(heading, LayoutParams(0, -1, 1f))
        if (!adaptive.splitPlayer) {
            header.addView(icon(59648, R.string.ui_f01b6815d826, 24f, actions.queue).apply {
                tag = "player-queue"; tooltipText = context.getString(R.string.player_queue_timer)
                setOnLongClickListener { actions.sleep(); true }
            }, LayoutParams(dp(48), dp(56)))
            header.addView(icon(59687, R.string.ui_8c38525734c0, 25f, actions.settings), LayoutParams(dp(48), dp(56)))
        }
        addView(header, LayoutParams(-1, dp(if (adaptive.splitPlayer || adaptive.compactPlayer) 56 else 64)))

        val album = FrameLayout(context).apply {
            background = styles.shape(palette.placeholder, 2); clipToOutline = true; elevation = dp(3).toFloat()
            addView(styles.label("LX", 58f, palette.primary).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
            addView(coverImage, FrameLayout.LayoutParams(-1, -1))
        }
        cover.addView(album, FrameLayout.LayoutParams(dp(240), dp(240), Gravity.CENTER))
        cover.addView(metadata, FrameLayout.LayoutParams(-1, -2))
        lyrics.isVerticalFadingEdgeEnabled = true; lyrics.setFadingEdgeLength(dp(56))
        body = PlayerBody(context, adaptive.splitPlayer, cover, lyrics, if (adaptive.splitPlayer) null else status)
        addView(body, LayoutParams(-1, 0, 1f))

        val previous = icon(59668, R.string.ui_8c747908bf69, if (adaptive.splitPlayer) 25f else 34f, actions.previous).apply { setTextColor(palette.primary); tag = "player-previous" }
        val next = icon(59654, R.string.ui_486e42475adb, if (adaptive.splitPlayer) 25f else 34f, actions.next).apply { setTextColor(palette.primary); tag = "player-next" }
        val controls = LinearLayout(context).apply {
            tag = "player-controls"; gravity = Gravity.CENTER
            listOf(previous, toggle, next).forEach { addView(it, LayoutParams(0, -1, 1f)) }
        }
        val tools = LinearLayout(context).apply { tag = "player-tools"; gravity = Gravity.CENTER }
        val toolViews = if (adaptive.splitPlayer) listOf(
            pageToggle,
            icon(59680, R.string.ui_f01b6815d826, 17f, actions.queue).apply { tag = "player-queue" },
            comments,
            icon(59687, R.string.ui_8c38525734c0, 18f, actions.settings),
            more,
            icon(59685, R.string.ui_dc0e25979181, 18f, actions.settings),
            icon(59648, R.string.ui_7afda7dc6745, 18f, actions.sleep),
            mode, collect,
        ) else listOf(pageToggle, collect, mode, comments, more)
        toolViews.forEach { view ->
            if (adaptive.splitPlayer) view.textSize = 17f
            view.setTextColor(palette.muted)
            tools.addView(view, LayoutParams(0, -1, 1f))
        }
        val footer = PlayerFooter(context, adaptive, seek, elapsed, duration, time,
            if (adaptive.splitPlayer) status else null, controls, tools)
        addView(footer, LayoutParams(-1, dp(if (adaptive.splitPlayer) 88 else if (adaptive.compactPlayer) 136 else 184)))
    }

    fun renderSong(song: Song?) {
        if (song == renderedSong && title.text.isNotEmpty()) return
        renderedSong = song
        title.text = song?.title ?: context.getString(R.string.ui_f518674b61f6)
        artist.text = song?.artist.orEmpty()
        val album = song?.sourceInfo?.let { runCatching { JSONObject(it).optString("albumName") }.getOrNull() }.orEmpty()
        metadata.text = listOf(
            context.getString(R.string.player_metadata_title, song?.title?.ifBlank { "—" } ?: "—"),
            context.getString(R.string.player_metadata_artist, song?.artist?.ifBlank { "—" } ?: "—"),
            context.getString(R.string.player_metadata_album, album.ifBlank { "—" }),
        ).joinToString("\n")
        collect.isEnabled = song != null
        comments.isEnabled = song != null && song.source != "local"
        comments.alpha = if (comments.isEnabled) 1f else .4f
    }

    fun showLyrics(show: Boolean) {
        lyrics.visibility = if (show) View.VISIBLE else View.GONE
        cover.visibility = if (!adaptive.splitPlayer && show) View.GONE else View.VISIBLE
        pageToggle.text = (if (show) 59698 else 59697).toChar().toString()
        pageToggle.isSelected = show
        pageToggle.contentDescription = context.getString(if (show) R.string.player_hide_lyrics else R.string.ui_9dbfa9b42bb3)
        body.requestLayout()
    }
}

/** Keep the square artwork and the lyrics independent of transport controls. */
private class PlayerBody(context: Context, private val split: Boolean, private val cover: View, private val lyrics: View,
    private val status: View?) : FrameLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    init { clipChildren = true; clipToPadding = true; addView(cover); addView(lyrics); status?.let { addView(it) } }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec); val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val margin = if (split) dp(24) else 0
        val available = (width - margin * 2).coerceAtLeast(0)
        val artworkWidth = if (split && lyrics.visibility == View.VISIBLE) (available * .42f).toInt() else available
        val messageHeight = if (status?.visibility == View.VISIBLE) dp(36) else 0
        fun measure(view: View, w: Int, h: Int) = view.measure(MeasureSpec.makeMeasureSpec(w.coerceAtLeast(0), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h.coerceAtLeast(0), MeasureSpec.EXACTLY))
        measure(cover, artworkWidth, height - messageHeight)
        measure(lyrics, if (split) available - artworkWidth else available, height - messageHeight)
        status?.let { measure(it, width - dp(32), messageHeight) }
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val margin = if (split) dp(24) else 0
        cover.layout(margin, 0, margin + cover.measuredWidth, cover.measuredHeight)
        val lyricLeft = if (split) margin + cover.measuredWidth else 0
        lyrics.layout(lyricLeft, 0, lyricLeft + lyrics.measuredWidth, lyrics.measuredHeight)
        status?.let { it.layout(dp(16), height - it.measuredHeight, width - dp(16), height) }
    }
}

/** Expanded transport stays on the bottom edge; phone transport is stacked below the artwork. */
private class PlayerFooter(context: Context, private val adaptive: AdaptiveLayout, private val seek: View,
    private val elapsed: View, private val duration: View, private val time: View, private val status: View?,
    private val controls: View, private val tools: View) : FrameLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    init {
        tag = "player-footer"
        listOf(seek, elapsed, duration, time, controls, tools).forEach { addView(it) }
        status?.let { addView(it) }
        elapsed.visibility = if (adaptive.splitPlayer) View.GONE else View.VISIBLE
        duration.visibility = elapsed.visibility; time.visibility = if (adaptive.splitPlayer) View.VISIBLE else View.GONE
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        val margin = dp(if (adaptive.splitPlayer) 24 else 8)
        val inner = (measuredWidth - margin * 2).coerceAtLeast(0)
        fun measure(view: View, w: Int, h: Int) = view.measure(MeasureSpec.makeMeasureSpec(w.coerceAtLeast(0), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h.coerceAtLeast(0), MeasureSpec.EXACTLY))
        if (adaptive.splitPlayer) {
            val transport = dp(144)
            val progress = (inner - transport - dp(24)).coerceAtLeast(0)
            measure(controls, transport, dp(64)); measure(tools, minOf(progress, dp(360)), dp(40))
            measure(seek, progress, dp(28)); measure(time, minOf(progress, dp(140)), dp(24))
            status?.let { measure(it, progress - dp(148), dp(24)) }
        } else {
            measure(seek, inner, dp(if (adaptive.compactPlayer) 28 else 40))
            measure(elapsed, inner / 2, dp(if (adaptive.compactPlayer) 24 else 28)); measure(duration, inner / 2, dp(if (adaptive.compactPlayer) 24 else 28))
            measure(controls, minOf(inner - dp(40), dp(320)), dp(if (adaptive.compactPlayer) 48 else 72))
            measure(tools, inner, dp(if (adaptive.compactPlayer) 40 else 48))
        }
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        fun place(view: View, x: Int, y: Int) = view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
        val margin = dp(if (adaptive.splitPlayer) 24 else 8)
        if (adaptive.splitPlayer) {
            place(controls, width - margin - controls.measuredWidth, height - controls.measuredHeight)
            place(seek, margin, height - dp(44))
            place(tools, margin + seek.measuredWidth - tools.measuredWidth, 0)
            place(time, margin + seek.measuredWidth - time.measuredWidth, height - dp(24))
            status?.let { place(it, margin, height - dp(24)) }
        } else {
            place(seek, margin, 0)
            place(elapsed, margin + dp(5), dp(if (adaptive.compactPlayer) 24 else 36))
            place(duration, width - margin - dp(5) - duration.measuredWidth, dp(if (adaptive.compactPlayer) 24 else 36))
            place(controls, (width - controls.measuredWidth) / 2, dp(if (adaptive.compactPlayer) 48 else 64))
            place(tools, margin, height - tools.measuredHeight)
        }
    }
}
