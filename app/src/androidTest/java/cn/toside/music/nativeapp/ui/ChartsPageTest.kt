package cn.toside.music.nativeapp.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.ListView
import android.widget.TextView
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.online.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class ChartsPageTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(check: () -> Boolean) {
        repeat(500) { var done = false; main { done = check() }; if (done) return; SystemClock.sleep(40) }
        throw AssertionError("Charts did not settle")
    }
    private fun field(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun invoke(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun start() = (instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity).also { activity -> await { activity.settingsPlayer() != null } }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun songs(board: Board, count: Int = 100, start: Int = 0) = (start until start + count).map {
        Song(it.toLong(), Uri.EMPTY, "${board.source} ${board.name} Song $it", "Artist $it", 156000, board.source,
            """{"songmid":"$it","types":[{"type":"${if (it % 2 == 0) "flac" else "flac24bit"}"}]}""")
    }
    private fun capture(name: String, root: View) {
        if (InstrumentationRegistry.getArguments().getString("chartScreenshots") != "true") return
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "chart-validation").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun measure(activity: MainActivity, width: Int, height: Int) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val density = activity.resources.displayMetrics.density
        repeat(3) { root.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY)); root.layout(0, 0, root.measuredWidth, root.measuredHeight) }
    }
    private fun choose(label: String) {
        instrumentation.waitForIdleSync()
        if (Build.VERSION.SDK_INT >= 29) {
            main {
                val list = WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<ListView>().first { view ->
                    view.adapter != null && (0 until view.adapter.count).any { view.adapter.getItem(it).toString() == label }
                }
                val index = (0 until list.adapter.count).first { list.adapter.getItem(it).toString() == label }
                list.performItemClick(list.getChildAt(index - list.firstVisiblePosition), index, list.adapter.getItemId(index))
            }
        } else {
            var node = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(label).first { it.text?.toString() == label }
            while (!node.isClickable) node = node.parent
            assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        }
    }

    @Test fun defaultHotChartSwitchingPaginationRetryAndLateResultsStayConsistent() {
        val requests = CopyOnWriteArrayList<Pair<Board, Int>>()
        var failAppend = true
        val repository = object : ChartsRepository {
            override fun boards(source: String) = NativeChartsRepository().boards(source)
            override suspend fun page(board: Board, page: Int): List<Song> {
                requests += board to page
                if (board.source == "kw" && board.id == "17") withContext(NonCancellable) { delay(250) }
                if (page == 2 && failAppend) { failAppend = false; throw IOException("retry") }
                return songs(board, if (page > 2) 0 else 100, if (page == 2) 90 else 0)
            }
        }
        lateinit var model: ChartsViewModel
        val saved = SavedStateHandle(); val store = ViewModelStore()
        main { model = ChartsViewModel(saved, repository); store.put("charts", model); model.ensureLoaded() }
        try {
            await { model.state.value.page == 1 }
            assertEquals(Board("16", "热歌榜", "kw"), model.state.value.board)
            main { model.loadMore() }; await { model.state.value.error != null }
            assertEquals(100, model.state.value.songs.size); assertEquals(1, model.state.value.page)
            main { model.loadMore() }; await { model.state.value.page == 2 }
            assertEquals(190, model.state.value.songs.size); assertNull(model.state.value.error)
            assertEquals(listOf(1, 2, 2), requests.map { it.second })
            main { model.loadMore() }; await { !model.state.value.hasMore }
            main { model.select(Board("17", "新歌榜", "kw")) }; await { requests.any { it.first.id == "17" } }
            main { model.selectSource("tx") }; await { model.state.value.board.source == "tx" && !model.state.value.loading }
            SystemClock.sleep(350)
            assertEquals("26", model.state.value.board.id); assertTrue(model.state.value.songs.all { it.source == "tx" }); assertFalse(model.state.value.hasMore)
            assertEquals("tx", saved.get<String>("source"))
        } finally { main { store.clear() } }
    }

    @Test fun pageMatchesPhoneLayoutAndRestoresSelectorsScrollAndHomeAfterSettingsAndRotation() {
        var activity = start()
        val requests = CopyOnWriteArrayList<Board>()
        val repository = object : ChartsRepository {
            override fun boards(source: String) = NativeChartsRepository().boards(source)
            override suspend fun page(board: Board, page: Int): List<Song> { requests += board; return songs(board) }
        }
        try {
            main {
                activity.chartsRepository = repository
                MainActivity::class.java.getDeclaredMethod("openSearchPage", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
                (field(activity, "search") as TextView).text = "home draft"
                (field(activity, "suggestionJob") as? Job)?.cancel()
                invoke(activity, "showBoards")
            }
            await { activity.chartsFragment()?.model?.state?.value?.songs?.size == 100 && activity.chartsFragment()?.isResumed == true }
            var fragment = activity.chartsFragment()!!; val model = fragment.model
            var list = fragment.requireView().findViewWithTag<ListView>("charts-songs")
            await { list.childCount > 0 }
            main {
                assertEquals("kw", model.state.value.board.source); assertEquals("16", model.state.value.board.id)
                listOf(360 to 800, 1024 to 768).forEach { (width, height) ->
                    MainActivity::class.java.getDeclaredField("uiLayout").apply { isAccessible = true }.set(activity, AdaptiveLayout(width, height))
                    invoke(activity, "applyThemeInPlace"); measure(activity, width, height)
                    val texts = descendants(list.getChildAt(0)).filterIsInstance<TextView>().map { it.text.toString() }
                    assertTrue(texts.containsAll(listOf("1", "SQ", "Artist 0", "02:36")))
                    descendants(list.getChildAt(0)).forEach { view -> val parent = view.parent as? ViewGroup; if (parent != null) assertTrue(view.left >= 0 && view.right <= parent.width) }
                    capture("charts-$width", activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                }
                fragment.requireView().findViewWithTag<View>("charts-board").performClick()
            }
            choose("新歌榜"); await { model.state.value.board.id == "17" && !model.state.value.loading }
            main { fragment.requireView().findViewWithTag<View>("charts-source").performClick() }
            choose(UiPreferences.sourceName(activity, "tx")); await { model.state.value.board.source == "tx" && !model.state.value.loading }
            main { list.setSelectionFromTop(30, -8) }; await { list.firstVisiblePosition == 30 }
            main { invoke(activity, "showSettings") }; await { activity.settingsFragment()?.isResumed == true }
            main { activity.closeSettings() }; await { activity.chartsFragment()?.isResumed == true }
            fragment = activity.chartsFragment()!!; assertSame(model, fragment.model)
            list = fragment.requireView().findViewWithTag("charts-songs"); await { list.firstVisiblePosition == 30 }
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            main { activity.recreate() }
            val replacement = instrumentation.waitForMonitorWithTimeout(monitor, 6000)
            instrumentation.removeMonitor(monitor); assertNotNull(replacement); activity = replacement as MainActivity
            await { activity.chartsFragment()?.isResumed == true }
            fragment = activity.chartsFragment()!!; assertSame(model, fragment.model)
            list = fragment.requireView().findViewWithTag("charts-songs"); await { list.firstVisiblePosition == 30 }
            assertEquals(3, requests.size)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await { activity.navigationController().currentDestination?.id == R.id.music_page }
            assertEquals("home draft", (field(activity, "search") as TextView).text.toString())
        } finally { main { activity.finish() } }
    }

    @Test fun liveDefaultKuwoHotChartLoadsSongMetadata() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCharts") == "true")
        val activity = start()
        try {
            main { invoke(activity, "showBoards") }
            await { activity.chartsFragment()?.model?.state?.value?.let { !it.loading && (it.songs.isNotEmpty() || it.error != null) } == true }
            val state = activity.chartsFragment()!!.model.state.value
            assertNull(state.error); assertEquals("kw", state.board.source); assertEquals("16", state.board.id); assertTrue(state.songs.isNotEmpty())
            main {
                MainActivity::class.java.getDeclaredField("uiLayout").apply { isAccessible = true }.set(activity, AdaptiveLayout(360, 800))
                invoke(activity, "applyThemeInPlace"); measure(activity, 360, 800)
                capture("charts-live-phone", activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
            }
        } finally { main { activity.finish() } }
    }
}
