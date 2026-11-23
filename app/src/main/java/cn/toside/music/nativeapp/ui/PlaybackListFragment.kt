package cn.toside.music.nativeapp.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.RecentSongs
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.player.PlaybackService
import cn.toside.music.nativeapp.utils.ToastUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.NumberFormat

class PlaybackListFragment : Fragment() {
    internal enum class Page(val title: Int, val count: Int, val empty: Int, val clear: Int) {
        QUEUE(R.string.ui_f01b6815d826, R.string.ui_827be73ec6db, R.string.ui_09679f956e0e, R.string.ui_d18e02739127),
        HISTORY(R.string.ui_426744e26e7e, R.string.ui_4f03b04808e7, R.string.playback_history_empty, R.string.ui_1ef3de06b32e)
    }

    private val page get() = Page.valueOf(requireNotNull(requireArguments().getString(PAGE)))
    private val host get() = requireActivity() as MainActivity
    private val history by lazy { RecentSongs(requireContext().applicationContext) }
    private val queueActions = PlatformPopup("queue-actions", outlined = true)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ListPage(savedInstanceState)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val content = view as ListPage
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                when (page) {
                    Page.QUEUE -> host.playbackService.collectLatest { player ->
                        player?.queueState?.distinctUntilChanged { previous, next ->
                            previous?.index == next?.index && previous?.songs == next?.songs
                        }?.collect { snapshot ->
                            content.render(snapshot?.songs.orEmpty(), snapshot?.index ?: -1)
                        }
                    }
                    Page.HISTORY -> history.changes.collectLatest {
                        try {
                            content.render(history.list())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            content.render(emptyList(), emptyMessage = getString(R.string.ui_82abac1a7ffd, e.localizedMessage.orEmpty()))
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        (view as? ListPage)?.saveScroll(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        view?.apply { alpha = 1f; scaleX = 1f; scaleY = 1f }
    }

    override fun onStop() {
        queueActions.dismiss()
        super.onStop()
    }

    private fun clear(songs: List<Song>) {
        when (page) {
            Page.QUEUE -> withQueue(songs) { it.clearQueue() }
            Page.HISTORY -> viewLifecycleOwner.lifecycleScope.launch {
                if (!history.clear()) ToastUtils.show(requireContext(), R.string.playback_history_clear_error)
            }
        }
    }

    private fun play(songs: List<Song>, position: Int) {
        when (page) {
            Page.QUEUE -> withQueue(songs) { player ->
                player.playQueued(position)
                host.closePlaybackList()
            }
            Page.HISTORY -> {
                val activity = host
                activity.closePlaybackList()
                activity.playPageSongs(songs, position)
            }
        }
    }

    private fun withQueue(songs: List<Song>, action: (PlaybackService) -> Unit) {
        host.playbackService.value?.takeIf { it.queueState.value?.songs == songs }?.let(action)
    }

    private fun showQueueActions(anchor: View, songs: List<Song>, position: Int, palette: UiPalette) {
        val actions = listOf("play", "up", "down", "remove")
        val labels = listOf(R.string.ui_c3396195e91c, R.string.ui_f853a70b1204, R.string.ui_e75e8b4e5c97, R.string.ui_6135d4159e89)
        val disabled = buildSet {
            if (position == 0) add("up")
            if (position == songs.lastIndex) add("down")
        }
        queueActions.show(anchor, actions, labels.map(::getString), "", palette, alignEnd = true, disabled = disabled) { action ->
            if (action == "play") play(songs, position)
            else withQueue(songs) { player ->
                when (action) {
                    "up" -> player.moveQueued(position, position - 1)
                    "down" -> player.moveQueued(position, position + 1)
                    "remove" -> player.removeQueued(position)
                }
            }
        }
    }

    private inner class ListPage(savedState: Bundle?) : LinearLayout(requireContext()) {
        private val palette = UiPalette(context, NativeThemes(context).active())
        private val styles = LxStyles(context, palette, Typeface.createFromAsset(context.assets, "fonts/icomoon.ttf"))
        private val numberFormat = NumberFormat.getIntegerInstance(resources.configuration.locales[0]).apply { isGroupingUsed = false }
        private val isQueue = page == Page.QUEUE
        private val songAdapter = SongAdapter()
        private var pendingScroll = savedState?.takeIf { it.containsKey(FIRST) }?.let { it.getInt(FIRST) to it.getInt(TOP) }
        private val title = styles.label(getString(page.title), 19f).apply {
            setTypeface(null, Typeface.BOLD)
            setPaddingRelative(styles.dp(8), 0, styles.dp(8), 0)
            maxLines = 2
            isAccessibilityHeading = true
        }
        private val clearButton = styles.label(getString(page.clear), 14f, palette.primary).apply {
            tag = "playback-list-clear"
            gravity = Gravity.CENTER
            minWidth = styles.dp(48)
            minHeight = styles.dp(48)
            setPaddingRelative(styles.dp(12), 0, styles.dp(12), 0)
            background = styles.touch()
            isFocusable = true
            visibility = View.GONE
            setOnClickListener { clear(songAdapter.songs) }
        }
        private val message = styles.label(getString(R.string.ui_d04fcbda737f), 14f, palette.muted).apply {
            tag = "playback-list-empty"
            gravity = Gravity.CENTER
            maxLines = 3
            setPadding(styles.dp(16), 0, styles.dp(16), 0)
        }
        private val songsList = ListView(context).apply {
            tag = "playback-list-songs"
            divider = null
            selector = styles.touch()
            setDrawSelectorOnTop(true)
            emptyView = message
            adapter = songAdapter
            setOnItemClickListener { _, row, _, _ -> (row as SongRow).playSong() }
            if (isQueue) setOnItemLongClickListener { _, row, _, _ ->
                (row as SongRow).openActions()
                true
            }
        }

        init {
            tag = if (isQueue) "playback-queue-page" else "playback-history-page"
            orientation = VERTICAL
            isClickable = true
            ViewCompat.setAccessibilityPaneTitle(this, getString(page.title))
            setBackgroundColor(palette.surface)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = styles.dp(56)
                setPaddingRelative(styles.dp(4), 0, styles.dp(8), 0)
                addView(styles.backButton(host::closePlaybackList), LayoutParams(styles.dp(48), styles.dp(48)))
                addView(title, LayoutParams(0, -2, 1f))
                addView(clearButton, LayoutParams(-2, -2))
            }, LayoutParams(-1, -2))
            addView(View(context).apply { setBackgroundColor(palette.divider) }, LayoutParams(-1, styles.dp(1)))
            addView(FrameLayout(context).apply {
                addView(songsList, FrameLayout.LayoutParams(-1, -1))
                addView(message, FrameLayout.LayoutParams(-1, -1))
            }, LayoutParams(-1, 0, 1f))
            palette.textStyle(this)
        }

        fun render(songs: List<Song>, current: Int = -1, emptyMessage: CharSequence = getString(page.empty)) {
            title.text = getString(page.count, numberFormat.format(songs.size.toLong()))
            clearButton.visibility = if (songs.isEmpty()) View.GONE else View.VISIBLE
            message.text = emptyMessage
            val firstLoad = songAdapter.songs.isEmpty()
            if (songAdapter.songs != songs || songAdapter.current != current) {
                if (songAdapter.songs != songs) queueActions.dismiss()
                songAdapter.songs = songs
                songAdapter.current = current
                songAdapter.notifyDataSetChanged()
            }
            if (songs.isEmpty()) return
            val scroll = pendingScroll
            if (scroll != null) {
                songsList.setSelectionFromTop(scroll.first.coerceIn(songs.indices), scroll.second)
                pendingScroll = null
            } else if (firstLoad && current in songs.indices) {
                songsList.setSelection(current)
            }
        }

        fun saveScroll(state: Bundle) {
            val scroll = pendingScroll ?: songsList.getChildAt(0)?.let { songsList.firstVisiblePosition to it.top } ?: return
            state.putInt(FIRST, scroll.first)
            state.putInt(TOP, scroll.second)
        }

        private inner class SongAdapter : BaseAdapter() {
            var songs = emptyList<Song>()
            var current = -1
            override fun getCount() = songs.size
            override fun getItem(position: Int) = songs[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                ((convertView as? SongRow) ?: SongRow()).also { it.bind(songs, position, position == current) }
        }

        private inner class SongRow : LinearLayout(context) {
            private var songs = emptyList<Song>()
            private var position = 0
            private val number = if (isQueue) styles.label("", 13f, palette.muted).apply { gravity = Gravity.CENTER } else null
            private val name = styles.label("", 15f)
            private val artist = styles.label("", 13f, palette.muted)
            private val more: TextView? = if (isQueue) styles.icon(59667, getString(R.string.ui_507e04d42f14), 16f) { openActions() }.apply {
                tag = "playback-queue-row-menu"
                isFocusable = false
            } else null

            init {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = styles.dp(60)
                setPaddingRelative(styles.dp(if (isQueue) 4 else 16), 0, styles.dp(8), 0)
                number?.let { addView(it, LayoutParams(styles.dp(44), -1)) }
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(0, styles.dp(8), 0, styles.dp(8))
                    addView(name, LayoutParams(-1, -2))
                    addView(artist, LayoutParams(-1, -2))
                }, LayoutParams(0, -2, 1f))
                more?.let { addView(it, LayoutParams(styles.dp(48), styles.dp(48))) }
                palette.textStyle(this)
            }

            fun bind(items: List<Song>, index: Int, current: Boolean) {
                songs = items
                position = index
                val song = items[index]
                number?.text = numberFormat.format(index + 1L)
                number?.setTextColor(if (current) palette.primary else palette.muted)
                name.text = song.title
                artist.text = song.artist
                artist.visibility = if (song.artist.isBlank()) View.GONE else View.VISIBLE
                if (isActivated != current) {
                    isActivated = current
                    setBackgroundColor(if (current) styles.selected else Color.TRANSPARENT)
                    ViewCompat.setStateDescription(this, if (current) getString(R.string.playback_queue_current) else null)
                }
            }

            fun playSong() = play(songs, position)
            fun openActions() {
                more?.let { showQueueActions(it, songs, position, palette) }
            }
        }
    }

    internal companion object {
        const val TAG = "playback-list"
        private const val PAGE = "page"
        private const val FIRST = "first"
        private const val TOP = "top"

        fun create(page: Page) = PlaybackListFragment().apply {
            arguments = Bundle().apply { putString(PAGE, page.name) }
        }
    }
}
