package cn.toside.music.nativeapp.ui

import android.app.AlertDialog
import android.app.DownloadManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.DownloadStatus
import cn.toside.music.nativeapp.data.DownloadTask
import cn.toside.music.nativeapp.data.Downloads
import cn.toside.music.nativeapp.data.DownloadLyricStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DownloadsFragment : Fragment() {
    private enum class Filter(val label: Int) {
        ALL(R.string.download_all), ACTIVE(R.string.download_active), PAUSED(R.string.download_paused),
        FAILED(R.string.download_errors), COMPLETE(R.string.download_complete);

        fun accepts(task: DownloadTask) = when (this) {
            ALL -> true
            ACTIVE -> task.status in setOf(DownloadStatus.RESOLVING, DownloadStatus.QUEUED, DownloadStatus.RUNNING)
            PAUSED -> task.status == DownloadStatus.PAUSED
            FAILED -> task.failed
            COMPLETE -> task.status == DownloadStatus.COMPLETE
        }
    }

    private val host get() = requireActivity() as MainActivity
    private val downloads by lazy { Downloads(requireContext().applicationContext) }
    private lateinit var palette: UiPalette
    private lateinit var styles: LxStyles
    private var wide = false
    private var filter = Filter.ALL
    private var query = ""
    private var tasks = emptyList<DownloadTask>()
    private var list: ListView? = null
    private var empty: TextView? = null
    private var adapter: TaskAdapter? = null
    private val tabs = mutableMapOf<Filter, Pair<TextView, View>>()
    private var pendingPosition: Pair<Int, Int>? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        palette = UiPalette(context, NativeThemes(context).active())
        styles = LxStyles(context, palette, Typeface.createFromAsset(context.assets, "fonts/icomoon.ttf"))
        wide = host.downloadTableLayout()
        if (savedInstanceState != null) {
            query = savedInstanceState.getString("query").orEmpty()
            filter = Filter.entries.firstOrNull { it.name == savedInstanceState.getString("filter") } ?: Filter.ALL
            pendingPosition = savedInstanceState.getInt("first") to savedInstanceState.getInt("top")
        }
        return LinearLayout(context).apply {
            tag = "downloads-page"; orientation = LinearLayout.VERTICAL; setBackgroundColor(palette.surface)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56); setPadding(dp(4), 0, dp(12), 0)
                addView(styles.icon(59680, getString(R.string.ui_e35a36e26d52), 19f) { host.openAppNavigation() }, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(styles.label(getString(R.string.download_page), 19f).apply { setTypeface(null, Typeface.BOLD); setPadding(dp(8), 0, 0, 0) })
            })
            addView(buildSearch(), LinearLayout.LayoutParams(if (wide) dp(360) else -1, dp(44)).apply {
                setMargins(dp(12), dp(4), dp(12), dp(8))
            })
            addView(buildTabs(), LinearLayout.LayoutParams(-1, -2))
            if (wide) addView(tableHeader(), LinearLayout.LayoutParams(-1, dp(36)))
            addView(View(context).apply { setBackgroundColor(palette.divider) }, LinearLayout.LayoutParams(-1, dp(1)))
            addView(FrameLayout(context).apply {
                list = ListView(context).apply {
                    tag = "downloads-list"; divider = null; itemsCanFocus = true
                    adapter = TaskAdapter().also { this@DownloadsFragment.adapter = it }
                }.also { addView(it, FrameLayout.LayoutParams(-1, -1)) }
                empty = styles.label(getString(R.string.download_loading), 14f, palette.muted).apply {
                    tag = "downloads-empty"; gravity = Gravity.CENTER; maxLines = 3; setPadding(dp(16), 0, dp(16), 0)
                }.also { addView(it, FrameLayout.LayoutParams(-1, -1)) }
                list?.emptyView = empty
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    private fun buildSearch() = LinearLayout(requireContext()).apply {
        gravity = Gravity.CENTER_VERTICAL; background = styles.shape(styles.selected)
        addView(styles.label(59660.toChar().toString(), 16f, palette.primary).apply {
            typeface = Typeface.createFromAsset(context.assets, "fonts/icomoon.ttf"); gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(40), -1))
        val input = EditText(context).apply {
            tag = "downloads-search"; hint = getString(R.string.download_search); textSize = 14f
            setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT; setText(query)
            setTextColor(palette.foreground); setHintTextColor(palette.muted); background = null; setPadding(0, 0, 0, 0)
        }
        addView(input, LinearLayout.LayoutParams(0, -1, 1f))
        val clear = styles.icon(59662, getString(R.string.search_clear), 16f) { input.text.clear() }.apply {
            visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
        }
        addView(clear, LinearLayout.LayoutParams(dp(44), -1))
        input.doAfterTextChanged {
            query = it.toString(); clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            pendingPosition = null; render(resetScroll = true)
        }
    }

    private fun buildTabs() = HorizontalScrollView(requireContext()).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            setPadding(dp(12), 0, dp(12), 0)
            Filter.entries.forEach { option ->
                val label = styles.label(getString(option.label), 14f).apply {
                    gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(10), 0)
                }
                val line = View(context).apply { setBackgroundColor(palette.primary) }
                tabs[option] = label to line
                addView(LinearLayout(context).apply {
                    tag = "downloads-filter-${option.name}"; orientation = LinearLayout.VERTICAL
                    isFocusable = true; background = styles.touch()
                    addView(label, LinearLayout.LayoutParams(-1, dp(42)))
                    addView(line, LinearLayout.LayoutParams(-1, dp(2)))
                    setOnClickListener { filter = option; pendingPosition = null; render(resetScroll = true) }
                })
            }
        })
        renderTabs()
    }

    private fun renderTabs() = tabs.forEach { (option, views) ->
        val selected = filter == option
        views.first.setTextColor(if (selected) palette.primary else palette.foreground)
        views.first.isSelected = selected
        (views.first.parent as View).isSelected = selected
        views.second.visibility = if (selected) View.VISIBLE else View.INVISIBLE
    }

    private fun tableHeader() = LinearLayout(requireContext()).apply {
        tag = "downloads-table-header"; gravity = Gravity.CENTER_VERTICAL
        tableCell("#", width = 36, header = true)
        tableCell(getString(R.string.layout_track), weight = 2.4f, header = true)
        tableCell(getString(R.string.download_progress), weight = .85f, header = true)
        tableCell(getString(R.string.download_status), weight = 1.55f, header = true)
        tableCell(getString(R.string.download_quality), width = 72, header = true)
        tableCell(getString(R.string.download_actions), width = 132, header = true)
    }

    private fun LinearLayout.tableCell(text: String = "", width: Int = 0, weight: Float = 0f, header: Boolean = false): TextView =
        styles.label(text, if (header) 12f else 13f, if (header) palette.muted else palette.foreground).apply {
            setPadding(dp(8), 0, dp(4), 0)
            addView(this, LinearLayout.LayoutParams(dp(width), -1, weight))
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { refresh(); delay(900) }
            }
        }
    }

    private suspend fun refresh() {
        try { tasks = downloads.list(); render() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { empty?.text = getString(R.string.download_load_error) }
    }

    private fun render(resetScroll: Boolean = false) {
        renderTabs()
        val keyword = query.trim()
        val shown = tasks.filter { task -> filter.accepts(task) && "${task.song.title} ${task.song.artist}".contains(keyword, ignoreCase = true) }
        empty?.text = getString(if (tasks.isEmpty()) R.string.download_empty else R.string.download_no_matches)
        adapter?.let { if (it.items != shown) { it.items = shown; it.notifyDataSetChanged() } }
        if (resetScroll) list?.setSelection(0)
        pendingPosition?.takeIf { shown.isNotEmpty() }?.let { (position, top) ->
            list?.setSelectionFromTop(position.coerceAtMost(shown.lastIndex), top)
            pendingPosition = null
        }
    }

    private fun status(task: DownloadTask): String = when (task.status) {
        DownloadStatus.RESOLVING -> getString(R.string.download_resolving)
        DownloadStatus.QUEUED -> getString(R.string.download_queued)
        DownloadStatus.RUNNING -> getString(R.string.download_active)
        DownloadStatus.PAUSED -> getString(when (task.reason) {
            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> R.string.download_wait_network
            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> R.string.download_wait_wifi
            DownloadManager.PAUSED_WAITING_TO_RETRY -> R.string.download_wait_retry
            else -> R.string.download_paused
        })
        DownloadStatus.LINK_FAILED -> getString(R.string.download_link_failed)
        DownloadStatus.FAILED -> if (task.reason == 0) getString(R.string.download_failed) else getString(R.string.download_failed_reason, task.reason)
        DownloadStatus.INTERRUPTED -> getString(R.string.download_interrupted)
        DownloadStatus.COMPLETE -> getString(R.string.download_complete)
    }

    private fun details(task: DownloadTask) {
        val size = if (task.total > 0) getString(R.string.download_size_mib, task.total / (1024.0 * 1024)) else null
        val lyric = task.lyricStatus?.let { getString(when (it) {
            DownloadLyricStatus.PENDING -> R.string.download_lyric_pending
            DownloadLyricStatus.SAVED -> R.string.download_lyric_saved
            DownloadLyricStatus.UNAVAILABLE -> R.string.download_lyric_unavailable
            DownloadLyricStatus.FAILED -> R.string.download_lyric_failed
        }) }
        val text = listOfNotNull(task.song.artist.takeIf { it.isNotBlank() }, status(task), task.quality?.let(::downloadQualityLabel),
            size, task.fileName?.let { "${task.directory}$it" }, lyric,
            task.lyricUri?.let { "${task.directory}${task.fileName?.substringBeforeLast('.')}.lrc" }).joinToString("\n")
        AlertDialog.Builder(requireContext()).setTitle(task.song.title).setMessage(text)
            .setNeutralButton(R.string.download_search_song) { _, _ -> host.searchDownloadedSong(task.song) }
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun remove(task: DownloadTask) {
        val message = if (task.status == DownloadStatus.COMPLETE) R.string.download_remove_file else R.string.download_remove_task
        AlertDialog.Builder(requireContext()).setMessage(getString(message, task.song.title))
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.download_remove) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try { downloads.remove(task); refresh() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { Toast.makeText(context, R.string.download_operation_failed, Toast.LENGTH_SHORT).show() }
                }
            }.show()
    }

    private fun activate(task: DownloadTask) {
        if (task.failed && task.quality != null) { host.retryDownload(task); return }
        if (task.status != DownloadStatus.COMPLETE) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val song = downloads.downloadedSong(task) ?: error("missing download")
                host.playPageSongs(listOf(song), 0)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(context, R.string.download_operation_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", query); outState.putString("filter", filter.name)
        val position = list?.let { it.firstVisiblePosition to (it.getChildAt(0)?.top ?: 0) } ?: pendingPosition
        outState.putInt("first", position?.first ?: 0); outState.putInt("top", position?.second ?: 0)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        list?.let { pendingPosition = it.firstVisiblePosition to (it.getChildAt(0)?.top ?: 0) }
        tabs.clear(); list = null; empty = null; adapter = null
        super.onDestroyView()
    }

    private fun dp(value: Int) = styles.dp(value)
    private inner class TaskAdapter : BaseAdapter() {
        var items = emptyList<DownloadTask>()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            ((convertView as? TaskRow) ?: TaskRow()).apply { bind(getItem(position), position) }
    }

    private inner class TaskRow : LinearLayout(requireContext()) {
        private lateinit var task: DownloadTask
        private val number: TextView?
        private val name: TextView
        private val artist: TextView?
        private val progress: TextView?
        private val state: TextView
        private val quality: TextView?
        private val primary = styles.icon(59653, "", 17f) { activate(task) }.apply { tag = "downloads-row-primary"; setTextColor(palette.primary) }

        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(if (wide) 52 else 72)
            if (wide) {
                number = tableCell(width = 36); name = tableCell(weight = 2.4f); artist = null
                progress = tableCell(weight = .85f); state = tableCell(weight = 1.55f); quality = tableCell(width = 72)
            } else {
                number = null; progress = null; quality = null
                val summary = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(12), dp(5), 0, dp(5)) }
                name = styles.label("", 14f).also { summary.addView(it) }
                artist = styles.label("", 12f, palette.muted).also { summary.addView(it) }
                state = styles.label("", 11f, palette.muted).apply { maxLines = 2 }.also { summary.addView(it) }
                addView(summary, LayoutParams(0, -2, 1f))
            }
            addView(primary, LayoutParams(dp(44), dp(48)))
            addView(styles.icon(59660, getString(R.string.download_details), 16f) { details(task) }.apply { setTextColor(palette.primary) }, LayoutParams(dp(44), dp(48)))
            addView(styles.icon(59662, getString(R.string.download_remove), 17f) { remove(task) }.apply {
                tag = "downloads-row-remove"; setTextColor(palette.primary)
            }, LayoutParams(dp(44), dp(48)))
        }

        fun bind(value: DownloadTask, position: Int) {
            task = value; tag = "downloads-task-${value.key}"
            val song = value.song
            val percent = getString(R.string.download_percent, value.percent)
            val bitrate = value.quality?.let(::downloadQualityLabel) ?: "—"
            number?.text = (position + 1).toString()
            name.text = if (wide && song.artist.isNotBlank()) "${song.title} · ${song.artist}" else song.title
            artist?.apply { text = song.artist; visibility = if (song.artist.isBlank()) View.GONE else View.VISIBLE }
            progress?.text = percent; quality?.text = bitrate
            state.text = if (wide) status(value) else "${status(value)} · $percent · $bitrate"
            primary.visibility = if (value.status == DownloadStatus.COMPLETE || value.failed && value.quality != null) View.VISIBLE else View.INVISIBLE
            primary.contentDescription = getString(if (value.status == DownloadStatus.COMPLETE) R.string.download_play else R.string.download_retry)
        }
    }
}
