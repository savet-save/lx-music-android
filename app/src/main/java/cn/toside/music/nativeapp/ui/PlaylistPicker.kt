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

/** Saved playlists only; the device library has its own navigation entry. */
internal class PlaylistPicker(
    context: Context,
    private val styles: LxStyles,
    private val palette: UiPalette,
    private val displayName: (Playlist) -> String,
    private val onSelect: (Playlist) -> Unit,
    private val onActions: (View, Playlist) -> Unit,
) : ListView(context) {
    private var playlists = emptyList<Playlist>()
    private var selectedPosition = -1
    private val rows = object : BaseAdapter() {
        override fun getCount() = playlists.size
        override fun getItem(position: Int) = playlists[position]
        override fun getItemId(position: Int) = getItem(position).id
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
        val changed = playlists != items || selectedPosition != selected
        if (changed) {
            playlists = items
            selectedPosition = selected
            rows.notifyDataSetChanged()
        }
        if (reveal) setSelection((selectedPosition - 2).coerceAtLeast(0))
        return changed || reveal
    }

    private inner class Row(context: Context) : LinearLayout(context) {
        private lateinit var playlist: Playlist
        private val name = styles.label("", 15f).apply {
            background = styles.touch()
            setPaddingRelative(styles.dp(12), 0, styles.dp(4), 0)
            isFocusable = true
            setOnClickListener { onSelect(playlist) }
        }
        private val more = styles.icon(59667, "", 18f) { }.apply {
            setOnClickListener { onActions(it, playlist) }
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(name, LayoutParams(0, styles.dp(48), 1f))
            addView(more, LayoutParams(styles.dp(48), styles.dp(48)))
            palette.textStyle(this)
        }

        fun bind(item: Playlist, selected: Boolean) {
            playlist = item
            val key = item.id.toString()
            val title = displayName(item)
            name.tag = "playlist-choice-$key"
            name.text = if (selected) "› $title" else title
            name.contentDescription = title
            name.isSelected = selected
            name.setTextColor(if (selected) palette.primary else palette.foreground)
            more.tag = "playlist-more-$key"
            more.contentDescription = context.getString(R.string.playlist_actions_for, title)
            more.setTextColor(palette.muted)
        }
    }
}
