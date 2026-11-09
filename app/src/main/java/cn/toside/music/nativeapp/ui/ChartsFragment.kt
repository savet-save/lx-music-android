package cn.toside.music.nativeapp.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Parcelable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.AppPreferences
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.online.*
import kotlinx.coroutines.launch
import org.json.JSONObject

class ChartsFragment : Fragment() {
    internal val model: ChartsViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                ChartsViewModel(extras.createSavedStateHandle(), (requireActivity() as MainActivity).chartsRepository) as T
        }
    }
    private lateinit var palette: UiPalette
    private lateinit var styles: LxStyles
    private var songs: ListView? = null
    private var source: TextView? = null
    private var board: TextView? = null
    private var status: TextView? = null
    private var empty: TextView? = null
    private var retry: TextView? = null
    private var more: TextView? = null
    private var progress: ProgressBar? = null
    private var adapter: SongsAdapter? = null
    private var playingKey: String? = null
    private val platformPopup = PlatformPopup()
    private var boardDrawer: HomeDrawerLayout? = null
    private var boardPicker: ListView? = null
    private var boardAdapter: BoardsAdapter? = null
    private var boardBack: OnBackPressedCallback? = null
    internal val isBoardDrawerOpen get() = boardDrawer?.isOpen == true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        palette = UiPalette(requireContext(), NativeThemes(requireContext()).active())
        styles = LxStyles(requireContext(), palette, Typeface.createFromAsset(requireContext().assets, "fonts/icomoon.ttf"))
        return LinearLayout(requireContext()).apply {
            tag = "charts-page"; orientation = LinearLayout.VERTICAL; setBackgroundColor(palette.surface)
            val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(8), dp(4)) }
            header.addView(styles.icon(59680, getString(R.string.ui_e35a36e26d52), 19f) {
                platformPopup.dismiss(); boardDrawer?.close(false); (requireActivity() as MainActivity).openAppNavigation()
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            header.addView(styles.label(getString(R.string.ui_1ee95aa94712), 19f).apply { setPadding(dp(8), 0, 0, 0); setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(header)
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val selectors = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(8), 0) }
                source = control("") { chooseSource() }.apply { tag = "charts-source"; contentDescription = getString(R.string.ui_9b980a0136e4) }
                board = control("", palette.primary) { chooseBoard() }.apply { tag = "charts-board"; contentDescription = getString(R.string.chart_select) }
                selectors.addView(source, LinearLayout.LayoutParams(-2, dp(48))); selectors.addView(board, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(selectors)
                addView(View(context).apply { setBackgroundColor(palette.divider) }, LinearLayout.LayoutParams(-1, dp(1)))
                progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(palette.primary) }
                addView(progress, LinearLayout.LayoutParams(-1, dp(2)))
                val message = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
                status = styles.label("", 12f, palette.muted).apply { maxLines = 3; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
                message.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
                retry = control(getString(R.string.playlist_retry)) { model.loadMore() }.apply { tag = "charts-retry" }
                message.addView(retry); addView(message)
                val result = FrameLayout(context)
                songs = ListView(context).apply { tag = "charts-songs"; divider = null; isVerticalScrollBarEnabled = false; setPadding(0, 0, 0, dp(56)); clipToPadding = false }
                result.addView(songs, FrameLayout.LayoutParams(-1, -1))
                empty = styles.label("", 16f, palette.muted).apply { gravity = Gravity.CENTER; maxLines = 3 }
                result.addView(empty, FrameLayout.LayoutParams(-1, -1)); songs?.emptyView = empty
                more = control(getString(R.string.ui_af90a08fec8e)) { model.loadMore() }.apply { tag = "charts-more"; setBackgroundColor(palette.surface); visibility = View.GONE }
                result.addView(more, FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM))
                addView(result, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            val picker = ListView(context).apply {
                divider = null; setBackgroundColor(palette.surface); isVerticalScrollBarEnabled = false
                adapter = BoardsAdapter().also { boardAdapter = it }
                setOnItemClickListener { _, _, position, _ ->
                    val selected = boardAdapter?.items?.getOrNull(position) ?: return@setOnItemClickListener
                    boardDrawer?.close(); model.select(selected)
                }
            }.also { boardPicker = it }
            boardDrawer = HomeDrawerLayout(context, content, picker, onRight = false, widthFraction = .5f,
                tagPrefix = "charts-drawer", closeDescription = R.string.chart_close_picker,
                onOpenChanged = { boardBack?.isEnabled = it })
            addView(boardDrawer, LinearLayout.LayoutParams(-1, 0, 1f))
            palette.textStyle(this)
        }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        boardBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { boardDrawer?.close() }
        }.also { requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it) }
        val list = requireNotNull(songs)
        adapter = SongsAdapter().also { list.adapter = it }
        list.setOnItemClickListener { _, _, position, _ -> (requireActivity() as MainActivity).playPageSongs(model.state.value.songs, position) }
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            private var scrolling = false
            override fun onScrollStateChanged(view: AbsListView?, state: Int) { scrolling = state != AbsListView.OnScrollListener.SCROLL_STATE_IDLE; if (!scrolling) rememberPosition() }
            override fun onScroll(view: AbsListView?, first: Int, count: Int, total: Int) {
                val state = model.state.value
                more?.visibility = if (total > 0 && first + count >= total && state.hasMore && state.error == null) View.VISIBLE else View.GONE
                if (scrolling && total > 0 && first + count >= total - 3) model.loadMore()
            }
        })
        renderPlayback((requireActivity() as MainActivity).settingsPlayer()?.state?.value?.song)
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect(::render) } }
        model.ensureLoaded()
        if (savedInstanceState?.getBoolean("board-drawer-open") == true) view.post {
            if (boardDrawer?.isAttachedToWindow == true) {
                chooseBoard(false)
                @Suppress("DEPRECATION")
                val position = savedInstanceState.getParcelable<Parcelable>("board-picker-state")
                if (position != null) boardPicker?.onRestoreInstanceState(position)
            }
        }
    }
    private fun render(state: ChartsState) {
        val list = songs ?: return
        source?.let { val text = UiPreferences.sourceName(requireContext(), state.board.source); if (it.text.toString() != text) it.text = text }
        board?.let { if (it.text.toString() != state.board.name) it.text = state.board.name }
        renderBoards()
        progress?.visibility = if (state.loading) View.VISIBLE else View.INVISIBLE
        status?.text = state.error?.let { getString(R.string.chart_error, it) }.orEmpty()
        status?.visibility = if (state.error == null) View.GONE else View.VISIBLE
        retry?.visibility = if (state.error == null) View.GONE else View.VISIBLE
        more?.isEnabled = !state.loading
        if (state.error != null || !state.hasMore) more?.visibility = View.GONE
        empty?.text = getString(if (state.loading) R.string.ui_4760ac1e18cc else R.string.chart_empty)
        val previous = adapter?.items.orEmpty()
        if (previous != state.songs) {
            val restore = if (previous.isEmpty()) model.scrollState else list.onSaveInstanceState()
            adapter?.items = state.songs; adapter?.notifyDataSetChanged()
            if (state.songs.isNotEmpty() && restore != null) list.onRestoreInstanceState(restore)
        }
    }
    internal fun renderPlayback(song: Song?) {
        val key = song?.let { "${it.source}:${it.id}" }
        if (key != playingKey) { playingKey = key; adapter?.notifyDataSetChanged() }
    }
    private fun chooseSource() {
        boardDrawer?.close(false)
        val anchor = source ?: return
        val ids = ChartsViewModel.sources
        platformPopup.show(anchor, ids, ids.map { UiPreferences.sourceName(requireContext(), it) }, model.state.value.board.source, palette) { model.selectSource(it) }
    }
    private fun renderBoards() {
        boardAdapter?.let { adapter ->
            val choices = model.choices()
            val selected = model.state.value.board
            if (adapter.items != choices || adapter.selected != selected) {
                adapter.items = choices; adapter.selected = selected; adapter.notifyDataSetChanged()
            }
        }
    }
    private fun chooseBoard(animate: Boolean = true) {
        platformPopup.dismiss(); renderBoards()
        if (isBoardDrawerOpen) boardDrawer?.close(animate)
        else {
            val selected = boardAdapter?.items?.indexOf(model.state.value.board) ?: 0
            boardPicker?.setSelection((selected - 2).coerceAtLeast(0))
            boardDrawer?.open(animate)
        }
    }
    private fun rememberPosition() { songs?.let { if (it.childCount > 0) model.scrollState = it.onSaveInstanceState() } }
    override fun onSaveInstanceState(outState: Bundle) {
        rememberPosition()
        outState.putBoolean("board-drawer-open", isBoardDrawerOpen)
        outState.putParcelable("board-picker-state", boardPicker?.onSaveInstanceState())
        super.onSaveInstanceState(outState)
    }
    override fun onStop() { platformPopup.dismiss(); super.onStop() }
    override fun onDestroyView() {
        platformPopup.dismiss(); boardDrawer?.close(false); boardDrawer = null; boardPicker = null; boardAdapter = null; boardBack = null
        rememberPosition(); songs = null; adapter = null; source = null; board = null; status = null; empty = null; more = null; retry = null; progress = null
        super.onDestroyView()
    }
    private fun control(text: String, color: Int = palette.foreground, action: () -> Unit) = styles.label(text, 15f, color).apply {
        gravity = Gravity.CENTER_VERTICAL; minHeight = dp(48); setPadding(dp(10), 0, dp(10), 0); background = styles.touch(); isFocusable = true; setOnClickListener { action() }
    }
    private fun dp(value: Int) = styles.dp(value)
    private inner class BoardsAdapter : BaseAdapter() {
        var items = emptyList<Board>()
        var selected: Board? = null
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position].name
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            ((convertView as? TextView) ?: styles.label("", 14f).apply {
                gravity = Gravity.CENTER_VERTICAL; minHeight = dp(48); maxLines = 2
                setPadding(dp(8), dp(10), dp(8), dp(10)); background = styles.touch()
            }).apply {
                val item = items[position]; isSelected = item == selected
                isActivated = item == selected
                text = if (isSelected) "› ${item.name}" else item.name
                setTextColor(if (isSelected) palette.primary else palette.foreground)
                tag = "chart-choice-${item.source}-${item.id}"
            }
    }
    private inner class SongsAdapter : BaseAdapter() {
        var items = emptyList<Song>()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = ((convertView as? SongRow) ?: SongRow(requireContext())).apply { bind(getItem(position), position) }
    }
    private inner class SongRow(context: Context) : LinearLayout(context) {
        private val rank = styles.label("", 12f, palette.faint).apply { gravity = Gravity.CENTER }
        private val name = styles.label("", 15f)
        private val quality = styles.label("", 10f, palette.primary)
        private val artist = styles.label("", 11f, palette.muted)
        private val duration = styles.label("", 12f, palette.faint).apply { gravity = Gravity.CENTER }
        private var current: Song? = null
        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56); setPadding(dp(4), dp(6), dp(4), dp(6))
            addView(rank, LayoutParams(dp(28), -1))
            val info = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(2), 0, dp(4), 0); addView(name, LayoutParams(-1, -2)) }
            val subtitle = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, 0) }
            subtitle.addView(quality, LayoutParams(-2, -2).apply { marginEnd = dp(5) }); subtitle.addView(artist, LayoutParams(0, -2, 1f))
            info.addView(subtitle); addView(info, LayoutParams(0, -2, 1f))
            addView(duration, LayoutParams(dp(44), -2))
            addView(styles.icon(59667, getString(R.string.ui_507e04d42f14), 16f) {}.apply {
                isFocusable = false
                setOnClickListener { anchor ->
                    val songs = model.state.value.songs
                    (requireActivity() as MainActivity).openChartSongActions(anchor, songs, songs.indexOf(current))
                }
            }, LayoutParams(dp(40), dp(44)))
            palette.textStyle(this)
        }
        fun bind(song: Song, position: Int) {
            current = song; tag = "chart-song-${song.source}-${song.id}"
            fun TextView.show(value: String) { if (text.toString() != value) text = value }
            rank.show((position + 1).toString()); name.show(song.title); artist.show(song.artist)
            val types = runCatching { JSONObject(song.sourceInfo.orEmpty()).optJSONArray("types") }.getOrNull()
            val available = (0 until (types?.length() ?: 0)).map { types?.optJSONObject(it)?.optString("type") }.toSet()
            val badge = when { "flac24bit" in available -> "24bit"; "flac" in available -> "SQ"; "320k" in available -> "HQ"; else -> "" }
            quality.show(badge); quality.visibility = if (badge.isBlank()) View.GONE else View.VISIBLE
            val seconds = song.duration.coerceAtLeast(0) / 1000
            duration.show("${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}")
            duration.visibility = if (AppPreferences(context).showDuration) View.VISIBLE else View.GONE
            val active = playingKey == "${song.source}:${song.id}"
            isActivated = active; name.setTextColor(if (active) palette.primary else palette.foreground); background = styles.touch(if (active) styles.selected else android.graphics.Color.TRANSPARENT, 0)
        }
    }
}
