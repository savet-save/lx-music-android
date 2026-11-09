package cn.toside.music.nativeapp.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.Bitmap
import android.util.LruCache
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.Artwork
import cn.toside.music.nativeapp.online.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A real browsing destination: its ViewModel keeps filters, paging and position on the back stack. */
class PlaylistBrowserFragment : Fragment() {
    internal val model: PlaylistBrowserViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                PlaylistBrowserViewModel(extras.createSavedStateHandle(), (requireActivity() as MainActivity).playlistBrowserRepository) as T
        }
    }
    private lateinit var palette: UiPalette
    private lateinit var styles: LxStyles
    private var grid: GridView? = null
    private var filters: LinearLayout? = null
    private var status: TextView? = null
    private var retry: TextView? = null
    private var more: TextView? = null
    private var progress: ProgressBar? = null
    private var adapter: CoversAdapter? = null
    private var categoriesJob: Job? = null
    private val covers = Semaphore(4)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        palette = UiPalette(requireContext(), NativeThemes(requireContext()).active())
        styles = LxStyles(requireContext(), palette, Typeface.createFromAsset(requireContext().assets, "fonts/icomoon.ttf"))
        return LinearLayout(requireContext()).apply {
            tag = "playlist-browser"; orientation = LinearLayout.VERTICAL; setBackgroundColor(palette.surface)
            val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(8), dp(4)) }
            header.addView(styles.icon(59680, getString(R.string.ui_e35a36e26d52), 19f) { (requireActivity() as MainActivity).openAppNavigation() }, LinearLayout.LayoutParams(dp(48), dp(48)))
            header.addView(styles.label(getString(R.string.layout_songlists), 19f).apply { setPadding(dp(8), 0, 0, 0); setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(header)
            filters = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; tag = "playlist-filters" }
            addView(HorizontalScrollView(context).apply { isFillViewport = true; isHorizontalScrollBarEnabled = false; addView(filters, ViewGroup.LayoutParams(-1, -2)) }, LinearLayout.LayoutParams(-1, -2))
            progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(palette.primary)
            }
            addView(progress, LinearLayout.LayoutParams(-1, dp(2)))
            val message = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
            status = styles.label("", 12f, palette.muted).apply { maxLines = 3; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; tag = "playlist-status" }
            message.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
            retry = control(getString(R.string.playlist_retry)) { model.loadMore() }.apply { tag = "playlist-retry" }
            message.addView(retry); addView(message)
            val results = FrameLayout(context)
            grid = object : GridView(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val columns = ((MeasureSpec.getSize(widthMeasureSpec) / resources.displayMetrics.density) / 150).toInt().coerceIn(3, 7)
                    // GridView.setNumColumns clears its child views. Do this before measurement,
                    // rather than inside an on-layout callback that would clear freshly laid-out cards.
                    if (numColumns != columns) numColumns = columns
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                }
            }.apply {
                tag = "playlist-grid"; numColumns = 3; stretchMode = GridView.STRETCH_COLUMN_WIDTH
                horizontalSpacing = dp(14); verticalSpacing = dp(18); setPadding(dp(12), dp(12), dp(12), dp(64))
                clipToPadding = false; isVerticalScrollBarEnabled = false
            }
            results.addView(grid, FrameLayout.LayoutParams(-1, -1))
            val empty = styles.label(getString(R.string.playlist_empty), 16f, palette.muted).apply { gravity = Gravity.CENTER; maxLines = 3; setPadding(dp(24), dp(24), dp(24), dp(24)) }
            results.addView(empty, FrameLayout.LayoutParams(-1, -1)); grid?.emptyView = empty
            more = control(getString(R.string.ui_af90a08fec8e)) { model.loadMore() }.apply { tag = "playlist-more"; setBackgroundColor(palette.surface); visibility = View.GONE }
            results.addView(more, FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM))
            addView(results, LinearLayout.LayoutParams(-1, 0, 1f))
            palette.textStyle(this)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val target = requireNotNull(grid)
        adapter = CoversAdapter().also { target.adapter = it }
        target.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            rememberPosition()
            model.state.value.items.getOrNull(position)?.let { (requireActivity() as MainActivity).openBrowserPlaylist(it) }
        }
        target.setOnScrollListener(object : AbsListView.OnScrollListener {
            private var scrolling = false
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) { scrolling = scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE; if (!scrolling) rememberPosition() }
            override fun onScroll(view: AbsListView?, first: Int, count: Int, total: Int) {
                val state = model.state.value
                val atEnd = total > 0 && first + count >= total
                more?.visibility = if (atEnd && state.hasMore && state.error == null) View.VISIBLE else View.GONE
                if (scrolling && total > 0 && first + count >= total - target.numColumns) model.loadMore()
            }
        })
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { render(it) } }
        }
        model.ensureLoaded()
    }

    private fun render(state: PlaylistBrowserState) {
        val target = grid ?: return
        progress?.visibility = if (state.loading) View.VISIBLE else View.INVISIBLE
        status?.text = state.error?.let { getString(R.string.playlist_error, it) }.orEmpty()
        status?.visibility = if (state.error != null) View.VISIBLE else View.GONE
        retry?.visibility = if (state.error != null) View.VISIBLE else View.GONE
        more?.apply { visibility = if (state.items.isNotEmpty() && target.lastVisiblePosition >= state.items.lastIndex && state.hasMore && state.error == null) View.VISIBLE else View.GONE; isEnabled = !state.loading }
        renderFilters(state.query)
        val previous = adapter?.items.orEmpty()
        if (previous != state.items) {
            val first = if (previous.isEmpty()) model.firstVisible else target.firstVisiblePosition
            val top = if (previous.isEmpty()) model.scrollTop else target.getChildAt(0)?.top ?: 0
            val restore = if (previous.isEmpty()) model.gridState else target.onSaveInstanceState()
            adapter?.items = state.items; adapter?.notifyDataSetChanged()
            if (state.items.isNotEmpty()) {
                if (restore != null) target.onRestoreInstanceState(restore)
                else target.setSelectionFromTop(first.coerceAtMost(state.items.lastIndex), top)
            }
        }
    }
    private fun renderFilters(query: PlaylistBrowseQuery) {
        val row = filters ?: return
        if (row.getTag(R.id.playlist_browser) == query) return
        row.setTag(R.id.playlist_browser, query); row.removeAllViews()
        query.orders().forEach { order ->
            val selected = order == query.order
            val tab = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL; isSelected = selected; tag = "playlist-order-$order"
                background = styles.touch(); isFocusable = true
                addView(styles.label(getString(when (order) { "new" -> R.string.playlist_new; "hot" -> R.string.playlist_hot; else -> R.string.playlist_recommended }), 14f,
                    if (selected) palette.primary else palette.foreground).apply { gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0) }, LinearLayout.LayoutParams(-2, dp(46)))
                addView(View(context).apply { setBackgroundColor(if (selected) palette.primary else android.graphics.Color.TRANSPARENT) }, LinearLayout.LayoutParams(-1, dp(2)).apply { marginStart = dp(10); marginEnd = dp(10) })
                setOnClickListener { rememberPosition(); model.select(query.copy(order = order)) }
            }
            row.addView(tab)
        }
        row.addView(View(requireContext()), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(control(query.categoryName.ifBlank { getString(R.string.playlist_default) }) { chooseCategory() }.apply { tag = "playlist-category" })
        row.addView(control(getString(R.string.playlist_open)) { (requireActivity() as MainActivity).openPlaylistLink() }.apply { tag = "playlist-open" })
        row.addView(control(UiPreferences.sourceName(requireContext(), query.source)) { chooseSource() }.apply { tag = "playlist-source" })
    }
    private val platformPopup = PlatformPopup()
    private val categoryPopup = PlatformPopup("playlist-category")
    private fun chooseSource() {
        val sources = PlatformPopup.sources
        val anchor = filters?.findViewWithTag<View>("playlist-source") ?: return
        platformPopup.show(anchor, sources, sources.map { UiPreferences.sourceName(requireContext(), it) }, model.state.value.query.source, palette, alignEnd = true) { selected ->
            rememberPosition(); model.select(PlaylistBrowseQuery(source = selected))
        }
    }
    private fun chooseCategory() {
        if (categoriesJob?.isActive == true) return
        val query = model.state.value.query
        categoriesJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val groups = (requireActivity() as MainActivity).playlistBrowserRepository.categories(query.source)
                if (model.state.value.query.source != query.source) return@launch
                val anchor = filters?.findViewWithTag<View>("playlist-category") ?: return@launch
                val labels = listOf(getString(R.string.playlist_default)) + groups.map { it.name }
                categoryPopup.show(anchor, labels.indices.map(Int::toString), labels, "", palette) { groupId ->
                        val group = groupId.toInt()
                        if (group == 0) { rememberPosition(); model.select(query.copy(category = "全部", categoryName = "")) }
                        else {
                            val selected = groups[group - 1]
                            categoryPopup.show(anchor, selected.tags.map { it.id }, selected.tags.map { it.name }, query.category, palette) { id ->
                                val tag = selected.tags.first { it.id == id }; rememberPosition(); model.select(query.copy(category = tag.id, categoryName = tag.name))
                            }
                        }
                    }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(requireContext(), getString(R.string.playlist_error, e.localizedMessage.orEmpty()), Toast.LENGTH_LONG).show() }
        }
    }
    private fun rememberPosition() { grid?.let { if (it.childCount > 0) { model.firstVisible = it.firstVisiblePosition; model.scrollTop = it.getChildAt(0).top; model.gridState = it.onSaveInstanceState() } } }
    override fun onSaveInstanceState(outState: Bundle) { rememberPosition(); super.onSaveInstanceState(outState) }
    private fun control(text: String, click: () -> Unit) = styles.label(text, 14f).apply {
        gravity = Gravity.CENTER; minHeight = dp(48); setPadding(dp(10), 0, dp(10), 0)
        background = styles.touch(); isFocusable = true; setOnClickListener { click() }
    }
    private fun dp(value: Int) = styles.dp(value)
    override fun onStop() { platformPopup.dismiss(); categoryPopup.dismiss(); categoriesJob?.cancel(); super.onStop() }
    override fun onDestroyView() {
        platformPopup.dismiss(); categoryPopup.dismiss()
        rememberPosition(); categoriesJob = null; adapter = null; grid = null; filters = null; status = null; retry = null; more = null; progress = null
        super.onDestroyView()
    }

    private inner class CoversAdapter : BaseAdapter() {
        var items = emptyList<OnlinePlaylist>()
        private val artwork = Artwork(requireContext().applicationContext)
        private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val card = (convertView as? CoverCard) ?: CoverCard(requireContext())
            val item = getItem(position)
            if (card.title.text.toString() != item.name) card.title.text = item.name
            card.contentDescription = listOf(item.name, item.author).filter(String::isNotBlank).joinToString(" · ")
            card.tag = "playlist-card-${item.source}-${item.id}"
            if (card.cover.tag != item.coverUrl) { card.job?.cancel(); card.cover.show(null); card.cover.tag = item.coverUrl }
            // Measurement also binds recycled cells. A loaded cover must remain untouched,
            // even after its cache entry is evicted by the larger tablet viewport.
            if (card.cover.drawable != null) return card
            val cached = memory.get(item.coverUrl)
            if (cached != null) card.cover.show(cached)
            else if (item.coverUrl.isNotEmpty() && card.cover.drawable == null && card.job?.isActive != true) card.job = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val bitmap = covers.withPermit { artwork.loadUrl(item.coverUrl) }
                    if (bitmap != null) {
                        memory.put(item.coverUrl, bitmap)
                        if (card.cover.tag == item.coverUrl) card.cover.show(bitmap)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Keep the placeholder; a failed image must not hide the playlist. */ }
            }
            return card
        }
    }
    private inner class CoverCard(context: Context) : LinearLayout(context) {
        var job: Job? = null
        val cover = FixedCoverView(context)
        val title = styles.label("", 13.5f).apply { maxLines = 2; minLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.TOP; setPadding(0, dp(5), 0, 0) }
        init {
            orientation = VERTICAL
            val square = FrameLayout(context).apply {
                background = styles.shape(palette.placeholder, 5); clipToOutline = true
                addView(styles.label("LX", 22f, palette.primary).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
                addView(cover, FrameLayout.LayoutParams(-1, -2))
            }
            addView(square, LayoutParams(-1, -2)); addView(title, LayoutParams(-1, -2))
            // GridView owns item click/focus handling and supplies a bounded recycled set of cards.
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
}
