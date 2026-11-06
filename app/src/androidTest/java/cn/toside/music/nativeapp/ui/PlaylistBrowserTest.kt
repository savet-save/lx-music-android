package cn.toside.music.nativeapp.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.GridView
import android.widget.ListView
import android.widget.TextView
import android.widget.ImageView
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.navigation.fragment.NavHostFragment
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PlaylistBrowserTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(description: (() -> String)? = null, check: () -> Boolean) {
        repeat(450) { var result = false; main { result = check() }; if (result) return; SystemClock.sleep(40) }
        throw AssertionError(description?.invoke() ?: "Playlist state did not settle")
    }
    private fun field(activity: MainActivity, name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun invoke(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun browser(activity: MainActivity) = ((activity.supportFragmentManager.findFragmentById(R.id.main_nav_host) as NavHostFragment).childFragmentManager.primaryNavigationFragment as? PlaylistBrowserFragment)
    private fun fixtureItems(query: PlaylistBrowseQuery, count: Int = 36, start: Int = 0) = (start until start + count).map {
        OnlinePlaylist(it.toString(), "8", "${query.source} ${query.order} 歌单 $it · 长标题测试", "Creator", query.source)
    }
    private fun start() = (instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity).also { activity -> await { field(activity, "service") != null } }
    private fun capture(name: String, view: View) {
        if (InstrumentationRegistry.getArguments().getString("playlistScreenshots") != "true") return
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "playlist-validation").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    private fun measure(activity: MainActivity, width: Int, height: Int) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val density = root.resources.displayMetrics.density
        repeat(3) {
            root.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }
    }

    @Test fun tabletCoversRemainVisibleAndKeepTheirBitmapsUnderRepeatedParentLayouts() {
        assumeTrue(instrumentation.targetContext.resources.configuration.screenWidthDp >= 600)
        val activity = start()
        try {
            CoverHttpFixture().use { server ->
                main {
                    activity.playlistBrowserRepository = object : PlaylistBrowserRepository {
                        override suspend fun categories(source: String) = emptyList<OnlineTagGroup>()
                        override suspend fun page(query: PlaylistBrowseQuery, page: Int) = fixtureItems(query, count = 42).map { it.copy(coverUrl = "${server.address}/${it.id}.png") }
                    }
                    invoke(activity, "showOnlinePlaylists")
                }
                await { browser(activity)?.isResumed == true }
                val grid = browser(activity)!!.requireView().findViewWithTag<GridView>("playlist-grid")
                fun visibleCovers(): List<ImageView> = (0 until grid.childCount).map {
                    ((grid.getChildAt(it) as ViewGroup).getChildAt(0) as ViewGroup).getChildAt(1) as ImageView
                }
                await { grid.childCount > 8 && visibleCovers().all { it.drawable is BitmapDrawable } }
                val baseline = mutableMapOf<String, Bitmap>()
                main {
                    visibleCovers().forEach { baseline[it.tag as String] = (it.drawable as BitmapDrawable).bitmap }
                    assertTrue("Visible tablet images must exceed the 8 MiB LRU", baseline.values.sumOf { it.allocationByteCount.toLong() } > 8L * 1024 * 1024)
                }
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val layouts = AtomicInteger()
                val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener { layouts.incrementAndGet() }
                main { root.viewTreeObserver.addOnGlobalLayoutListener(listener) }
                repeat(15) {
                    main { root.requestLayout() }; SystemClock.sleep(60)
                    main {
                        visibleCovers().forEach { cover ->
                            assertTrue("Loaded cover reverted to placeholder: ${cover.tag}", cover.drawable is BitmapDrawable)
                            assertSame("An unchanged visible cover must not be decoded or rebound", baseline[cover.tag], (cover.drawable as BitmapDrawable).bitmap)
                        }
                    }
                }
                SystemClock.sleep(250)
                main {
                    root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                    assertTrue("Layout requests must settle, actual ${layouts.get()}", layouts.get() in 5..35)
                    capture("tablet-covers-stable", root)
                }
                assertTrue(server.requests.get() <= 42)
            }
        } finally { main { activity.finish() } }
    }

    @Test fun failedAppendRetriesTheSamePageAndLateOldFiltersCannotReplaceNewResults() {
        val requests = CopyOnWriteArrayList<Pair<PlaylistBrowseQuery, Int>>()
        var failPage = true
        val repository = object : PlaylistBrowserRepository {
            override suspend fun categories(source: String) = emptyList<OnlineTagGroup>()
            override suspend fun page(query: PlaylistBrowseQuery, page: Int): List<OnlinePlaylist> {
                requests += query to page
                if (query.category == "slow") withContext(NonCancellable) { delay(250) }
                if (page == 2 && failPage) { failPage = false; throw IOException("retry fixture") }
                return fixtureItems(query, if (page > 2) 0 else 6, if (page == 2) 4 else 0)
            }
        }
        lateinit var model: PlaylistBrowserViewModel
        val saved = SavedStateHandle()
        val store = ViewModelStore()
        main { model = PlaylistBrowserViewModel(saved, repository); store.put("browser", model); model.ensureLoaded() }
        try {
            await { model.state.value.page == 1 }
            main { model.firstVisible = 3; model.scrollTop = -8; model.loadMore() }
            await { model.state.value.error != null }
            assertEquals(1, model.state.value.page); assertEquals(6, model.state.value.items.size)
            main { model.loadMore() }; await { model.state.value.page == 2 }
            assertNull(model.state.value.error); assertEquals(10, model.state.value.items.size)
            assertEquals(listOf(1, 2, 2), requests.map { it.second })
            main { model.select(PlaylistBrowseQuery(category = "slow", categoryName = "Slow")) }
            await { requests.any { it.first.category == "slow" } }
            main { model.select(PlaylistBrowseQuery(source = "wy", order = "hot")) }
            await { model.state.value.page == 1 && model.state.value.query.source == "wy" }
            SystemClock.sleep(350)
            assertTrue(model.state.value.items.all { it.source == "wy" }); assertEquals("hot", model.state.value.query.order)
            assertEquals(0, model.firstVisible); assertEquals("wy", saved.get<String>("source"))
            main { model.loadMore() }; await { model.state.value.page == 2 }
            main { model.loadMore() }; await { !model.state.value.hasMore }
            assertEquals(10, model.state.value.items.size)
        } finally { main { store.clear() } }
    }

    @Test fun gridSortingNavigationBackAndRotationKeepTheBrowsingState() {
        var activity = start()
        val requests = CopyOnWriteArrayList<PlaylistBrowseQuery>()
        val repository = object : PlaylistBrowserRepository {
            override suspend fun categories(source: String) = listOf(OnlineTagGroup("风格", listOf(OnlineTag("10-10000", "摇滚"))))
            override suspend fun page(query: PlaylistBrowseQuery, page: Int): List<OnlinePlaylist> { requests += query; return fixtureItems(query, count = 72) }
        }
        try {
            main {
                activity.playlistBrowserRepository = repository
                activity.onlinePlaylistSongs = { _, _ -> listOf(Song(1, Uri.EMPTY, "Fixture song", "Artist", 1000, "kw", "{}")) }
                MainActivity::class.java.getDeclaredMethod("openSearchPage", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
                (field(activity, "search") as TextView).text = "saved query"
                (field(activity, "suggestionJob") as? Job)?.cancel()
                invoke(activity, "showOnlinePlaylists")
            }
            await { browser(activity)?.model?.state?.value?.items?.size == 72 && browser(activity)?.isResumed == true }
            var fragment = browser(activity)!!
            val model = fragment.model
            var grid = fragment.requireView().findViewWithTag<GridView>("playlist-grid")
            await({ "Grid ${grid.width}x${grid.height}, visibility ${grid.visibility}, shown ${grid.isShown}, count ${grid.count}, adapter ${grid.adapter?.count}, first ${grid.firstVisiblePosition}, columns ${grid.numColumns}, parent ${(grid.parent as View).width}x${(grid.parent as View).height}, root ${fragment.requireView().height}" }) { grid.childCount > 0 }
            assertEquals("new", requests.single().order)
            main {
                listOf(360 to 800, 600 to 800, 1024 to 768).forEach { (width, height) ->
                    MainActivity::class.java.getDeclaredField("uiLayout").apply { isAccessible = true }.set(activity, AdaptiveLayout(width, height))
                    invoke(activity, "applyThemeInPlace"); measure(activity, width, height)
                    assertTrue(grid.numColumns >= 3)
                    if (width == 360) assertEquals(3, grid.numColumns)
                    for (i in 0 until grid.childCount) {
                        val card = grid.getChildAt(i) as ViewGroup
                        val cover = card.getChildAt(0)
                        assertEquals(cover.width, cover.height)
                        assertTrue(card.left >= 0 && card.right <= grid.width)
                    }
                    capture("playlists-$width", activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                }
                fragment.requireView().findViewWithTag<View>("playlist-order-hot").performClick()
            }
            await { model.state.value.query.order == "hot" && !model.state.value.loading }
            instrumentation.waitForIdleSync(); SystemClock.sleep(100)
            main { grid.smoothScrollBy((450 * activity.resources.displayMetrics.density).toInt(), 1) }
            await { grid.firstVisiblePosition > 0 }
            instrumentation.waitForIdleSync(); SystemClock.sleep(100)
            val first = grid.firstVisiblePosition
            main { grid.performItemClick(grid.getChildAt(0), first, first.toLong()) }
            await { activity.navigationController().currentDestination?.id == R.id.playlist_songs }
            await { (field(activity, "list") as ListView).adapter?.count == 1 }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await { browser(activity)?.isResumed == true }
            fragment = browser(activity)!!; assertSame(model, fragment.model)
            grid = fragment.requireView().findViewWithTag("playlist-grid")
            await({ "Expected restored row $first; actual ${grid.firstVisiblePosition}; saved ${model.firstVisible}; columns ${grid.numColumns}" }) { grid.firstVisiblePosition == first }
            assertEquals(2, requests.size)
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            main { activity.recreate() }
            val replacement = instrumentation.waitForMonitorWithTimeout(monitor, 6000)
            instrumentation.removeMonitor(monitor); assertNotNull(replacement); activity = replacement as MainActivity
            await { browser(activity)?.isResumed == true }
            fragment = browser(activity)!!; assertSame(model, fragment.model)
            assertEquals("hot", fragment.model.state.value.query.order); assertEquals(2, requests.size)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await { activity.navigationController().currentDestination?.id == R.id.music_page }
            assertEquals("saved query", (field(activity, "search") as TextView).text.toString())
            assertTrue(field(activity, "onlineMode") as Boolean)
        } finally { main { activity.finish() } }
    }

    @Test fun liveDefaultFeedHasCoverMetadataAndRendersPhoneCards() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePlaylists") == "true")
        val activity = start()
        try {
            main { invoke(activity, "showOnlinePlaylists") }
            await { browser(activity)?.model?.state?.value?.let { !it.loading && (it.items.isNotEmpty() || it.error != null) } == true }
            val fragment = browser(activity)!!
            assertNull(fragment.model.state.value.error); assertTrue(fragment.model.state.value.items.any { it.coverUrl.isNotBlank() })
            val grid = fragment.requireView().findViewWithTag<GridView>("playlist-grid")
            await { grid.childCount > 0 && (0 until grid.childCount).any { index ->
                (((grid.getChildAt(index) as ViewGroup).getChildAt(0) as ViewGroup).getChildAt(1) as ImageView).drawable != null
            } }
            main {
                MainActivity::class.java.getDeclaredField("uiLayout").apply { isAccessible = true }.set(activity, AdaptiveLayout(360, 800))
                invoke(activity, "applyThemeInPlace"); measure(activity, 360, 800)
                assertEquals(3, grid.numColumns); assertTrue(grid.childCount > 0)
                capture("playlists-live-phone", activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
            }
        } finally { main { activity.finish() } }
    }
}
