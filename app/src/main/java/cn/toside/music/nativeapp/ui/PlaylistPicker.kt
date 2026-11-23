package cn.toside.music.nativeapp.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.Playlist

/** Saved playlists and the local karaoke library. */
internal class PlaylistPicker(
    context: Context,
    private val styles: LxStyles,
    private val palette: UiPalette,
    private val displayName: (Playlist) -> String,
    private val onSelect: (Playlist) -> Unit,
    private val onActions: (View, Playlist) -> Unit,
    private val onKaraoke: () -> Unit,
) : ListView(context) {
    private var playlists = emptyList<Playlist>()
    private var karaokePosition = 0
    private var selectedPosition = -1
    private val rows = object : BaseAdapter() {
        override fun getCount() = playlists.size + 1
        override fun getItem(position: Int): Playlist? = when {
            position < karaokePosition -> playlists[position]
            position > karaokePosition -> playlists[position - 1]
            else -> null
        }
        override fun getItemId(position: Int) = getItem(position)?.id ?: Long.MIN_VALUE
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            ((convertView as? Row) ?: Row(context)).apply { bind(getItem(position), position == selectedPosition) }
    }

    init {
        divider = null
        itemsCanFocus = true
        setBackgroundColor(palette.surface)
        adapter = rows
    }

    fun render(items: List<Playlist>, currentId: Long?, isMyLists: Boolean, reveal: Boolean): Boolean {
        val selected = when {
            !isMyLists -> -1
            else -> items.indexOfFirst { it.id == currentId }
        }
        val karaokeIndex = items.takeWhile { it.syncId == "default" || it.syncId == "love" }.size
        val row = if (selected >= karaokeIndex) selected + 1 else selected
        val changed = playlists != items || selectedPosition != row
        if (changed) {
            playlists = items
            karaokePosition = karaokeIndex
            selectedPosition = row
            rows.notifyDataSetChanged()
        }
        if (reveal) setSelection((selectedPosition - 2).coerceAtLeast(0))
        return changed || reveal
    }

    private inner class Row(context: Context) : LinearLayout(context) {
        private var playlist: Playlist? = null
        private val name = styles.label("", 15f).apply {
            background = styles.touch()
            setPaddingRelative(styles.dp(12), 0, styles.dp(4), 0)
            isFocusable = true
            setOnClickListener { playlist?.let(onSelect) ?: onKaraoke() }
        }
        private val more = styles.icon(59667, "", 18f) { }.apply {
            setOnClickListener { view -> playlist?.let { onActions(view, it) } }
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(name, LayoutParams(0, styles.dp(48), 1f))
            addView(more, LayoutParams(styles.dp(48), styles.dp(48)))
            palette.textStyle(this)
        }

        fun bind(item: Playlist?, selected: Boolean) {
            playlist = item
            val key = item?.id?.toString() ?: "karaoke"
            val title = item?.let(displayName) ?: context.getString(R.string.karaoke_library)
            name.tag = "playlist-choice-$key"
            name.text = if (selected) "› $title" else title
            name.contentDescription = title
            name.isSelected = selected
            name.setTextColor(if (selected) palette.primary else palette.foreground)
            more.tag = "playlist-more-$key"
            more.visibility = if (item == null) View.GONE else View.VISIBLE
            more.contentDescription = context.getString(R.string.playlist_actions_for, title)
            more.setTextColor(palette.muted)
        }
    }
}
