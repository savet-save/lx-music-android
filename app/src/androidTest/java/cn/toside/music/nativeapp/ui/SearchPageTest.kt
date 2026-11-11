package cn.toside.music.nativeapp.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.SystemClock
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.ListView
import android.widget.GridView
import android.widget.ImageView
import android.graphics.drawable.BitmapDrawable
import android.widget.TextView
import androidx.activity.BackEventCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.navigation.fragment.NavHostFragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.data.Playlists
import cn.toside.music.nativeapp.online.OnlinePlaylist
import cn.toside.music.nativeapp.online.SearchCatalog
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SearchPageTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun field(activity: MainActivity, name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun set(activity: MainActivity, name: String, value: Any?) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    private fun invoke(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun open(activity: MainActivity) = MainActivity::class.java.getDeclaredMethod("openSearchPage", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
    private fun submit(activity: MainActivity, more: Boolean = false) = MainActivity::class.java.getDeclaredMethod("searchOnline", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, more)
    private fun root(activity: MainActivity) = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
    private fun song(source: String, query: String) = Song(1, Uri.EMPTY, "$query · $source", "Artist", 60_000, source,
        JSONObject().put("source", source).put("songmid", "$source-song").put("types", org.json.JSONArray()).toString())
    private fun fixtureCatalog() = SearchCatalog(
        songSearch = { source, query, _ -> delay(10); listOf(song(source, query)) },
        playlistSearch = { source, query, _ -> delay(10); listOf(OnlinePlaylist("1", "", "$query · $source", "Author", source)) }
    )
    private inner class Fixture(catalog: SearchCatalog = fixtureCatalog(), overrides: Map<String, String> = emptyMap()) : AutoCloseable {
        private val preferences = instrumentation.targetContext.getSharedPreferences("app-settings", 0)
        private val keys = listOf("search.isShowHotSearch", "search.isShowHistorySearch", "theme.dynamicBg") + overrides.keys
        private val saved = keys.associateWith { preferences.all[it] }
        var activity: MainActivity
        init {
            preferences.edit().putBoolean("search.isShowHotSearch", false).putBoolean("search.isShowHistorySearch", false).putBoolean("theme.dynamicBg", false)
                .apply { overrides.forEach { (key, value) -> putString(key, value) } }.commit()
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { set(activity, "searchCatalog", catalog); open(activity) }
        }
        override fun close() {
            instrumentation.runOnMainSync { activity.finish() }
            preferences.edit().apply { saved.forEach { (key, value) -> when (value) {
                is Boolean -> putBoolean(key, value); is String -> putString(key, value); else -> remove(key)
            } } }.commit()
        }
    }
    private fun waitForSearch(activity: MainActivity) {
        repeat(100) {
            instrumentation.waitForIdleSync()
            if ((field(activity, "loadJob") as? Job)?.isActive != true) return
            SystemClock.sleep(50)
        }
        fail("Fixture search did not complete")
    }
    private fun awaitUi(message: String = "Timed out waiting for search layout", check: () -> Boolean) {
        repeat(200) {
            var ready = false
            instrumentation.runOnMainSync { ready = check() }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail(message)
    }
    private fun measure(activity: MainActivity, widthDp: Int, heightDp: Int): ViewGroup {
        val root = root(activity); val density = activity.resources.displayMetrics.density
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((heightDp * density).toInt(), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }
        return root
    }
    private fun snapshot(name: String, view: View) {
        if (InstrumentationRegistry.getArguments().getString("searchScreenshots") != "true") return
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = File(output?.let(::File) ?: instrumentation.targetContext.getExternalFilesDir(null), "search-validation").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun chooseSource(activity: MainActivity, label: String) {
        instrumentation.runOnMainSync { invoke(activity, "showSearchSources") }
        instrumentation.waitForIdleSync()
        if (Build.VERSION.SDK_INT >= 29) {
            instrumentation.runOnMainSync {
                val list = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { root ->
                    root.findViewWithTag<ListView>("platform-menu")?.takeIf(View::isShown)
                } ?: error("Provider menu must be visible")
                val position = (0 until list.count).firstOrNull { list.getItemAtPosition(it) == label }
                    ?: error("Provider choice must be present: $label")
                list.performItemClick(list.getChildAt(position - list.firstVisiblePosition), position, list.adapter.getItemId(position))
            }
            instrumentation.waitForIdleSync()
            return
        }
        val node = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)?.firstOrNull { it.text?.toString() == label }
        assertNotNull("Provider choice must be visible: $label", node)
        var target = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        instrumentation.waitForIdleSync()
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun searchRowAndMenuPlaybackAddOnlyChosenSongsToTrialList() = runBlocking {
        val identity = System.nanoTime()
        val catalog = SearchCatalog(songSearch = { source, query, _ ->
            listOf(song(source, query).copy(id = identity, sourceInfo = """{"songmid":"$identity"}"""))
        })
        Playlists(instrumentation.targetContext).use { lists ->
            val trialId = lists.trialList()
            val original = lists.songs(trialId)
            Fixture(catalog, mapOf("list.addMusicLocationType" to "bottom")).use { fixture ->
                val activity = fixture.activity
                awaitUi { activity.settingsPlayer() != null }
                val player = field(activity, "service")
                try {
                    // Capture the playback handoff without contacting online audio providers.
                    instrumentation.runOnMainSync {
                        set(activity, "service", null)
                        (field(activity, "search") as EditText).setText("trial fixture")
                        submit(activity)
                    }
                    waitForSearch(activity)
                    awaitUi { (field(activity, "list") as ListView).childCount > 1 }
                    val results = (field(activity, "allSongs") as List<*>).filterIsInstance<Song>()
                    assertEquals(SearchCatalog.sources.size, results.size)
                    assertEquals(original, lists.songs(trialId))
                    fun request() = field(activity, "waitingForServicePlayback")
                    fun clickRow() {
                        val previous = request()
                        instrumentation.runOnMainSync {
                            val list = field(activity, "list") as ListView
                            list.performItemClick(list.getChildAt(0), 0, list.adapter.getItemId(0))
                        }
                        awaitUi { request() != null && request() !== previous }
                    }
                    clickRow()
                    assertEquals(original + results[0], lists.songs(trialId))
                    clickRow()
                    assertEquals(original + results[0], lists.songs(trialId))
                    val previous = request()
                    instrumentation.runOnMainSync {
                        val row = (field(activity, "list") as ListView).getChildAt(1) as ViewGroup
                        row.getChildAt(row.childCount - 1).performClick()
                    }
                    fun menu() = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull {
                        it.findViewWithTag<ListView>("song-actions-menu")?.takeIf(View::isShown)
                    }
                    awaitUi { menu()?.childCount?.let { it > 0 } == true }
                    instrumentation.runOnMainSync {
                        val menu = requireNotNull(menu())
                        assertEquals(activity.getString(R.string.ui_c3396195e91c), menu.adapter.getItem(0))
                        menu.performItemClick(menu.getChildAt(0), 0, menu.adapter.getItemId(0))
                    }
                    awaitUi { request() !== previous }
                    assertEquals(original + results.take(2), lists.songs(trialId))
                    val action = requireNotNull(request())
                    assertEquals(results, action.javaClass.getDeclaredField("songs").apply { isAccessible = true }.get(action))
                    assertEquals(1, action.javaClass.getDeclaredField("position").apply { isAccessible = true }.get(action))
                } finally {
                    instrumentation.runOnMainSync {
                        set(activity, "waitingForServicePlayback", null)
                        set(activity, "service", player)
                    }
                    lists.songs(trialId).filter { it.id == identity }.forEach { lists.remove(trialId, it) }
                }
            }
        }
    }

    @Test fun themeChangesKeepThePendingSearchWithoutRestartingRequests() {
        val requests = AtomicInteger()
        val response = CompletableDeferred<Unit>()
        val catalog = SearchCatalog(songSearch = { source, query, _ ->
            requests.incrementAndGet(); response.await(); listOf(song(source, query))
        })
        val preferences = instrumentation.targetContext.getSharedPreferences("app-settings", 0)
        val saved = listOf("theme.id", "theme.lightId", "theme.darkId", "common.isAutoTheme").associateWith { preferences.all[it] }
        try {
            Fixture(catalog).use { fixture ->
                val activity = fixture.activity
                instrumentation.runOnMainSync { (field(activity, "search") as EditText).setText("pending theme"); submit(activity) }
                repeat(100) { if (requests.get() < SearchCatalog.sources.size) SystemClock.sleep(20) }
                assertEquals(SearchCatalog.sources.size, requests.get())
                val originalJob = field(activity, "loadJob") as Job
                instrumentation.runOnMainSync { invoke(activity, "showSettings") }
                instrumentation.waitForIdleSync()
                val fragment = activity.settingsFragment() as SettingsFragment
                val target = if ((field(activity, "palette") as UiPalette).theme.id == "blue") "green" else "blue"
                instrumentation.runOnMainSync {
                    fragment.requireView().findViewWithTag<View>("settings-category-theme").performClick()
                    fragment.requireView().findViewWithTag<View>("settings-theme-$target").performClick()
                }
                instrumentation.waitForIdleSync()
                assertEquals(target, (field(activity, "palette") as UiPalette).theme.id)
                assertSame(originalJob, field(activity, "loadJob")); assertTrue(originalJob.isActive)
                assertEquals(SearchCatalog.sources.size, requests.get())
                assertTrue(field(activity, "catalogLoading") as Boolean)
                response.complete(Unit); waitForSearch(activity)
                assertEquals(SearchCatalog.sources.size, (field(activity, "allSongs") as List<*>).size)
                instrumentation.runOnMainSync { activity.closeSettings() }
                instrumentation.waitForIdleSync()
                assertEquals("pending theme", (field(activity, "search") as EditText).text.toString())
                assertEquals(SearchCatalog.sources.size, requests.get())
                assertEquals(SearchCatalog.sources.size, (field(activity, "list") as ListView).adapter.count)
            }
        } finally {
            response.complete(Unit)
            preferences.edit().apply { saved.forEach { (key, value) -> when (value) {
                is String -> putString(key, value); is Boolean -> putBoolean(key, value); else -> remove(key)
            } } }.commit()
        }
    }

    @Test fun phoneSearchStartsAggregatedAndProviderPickerAndBothLayoutsFit() {
        Fixture().use { fixture ->
            val activity = fixture.activity
            assertEquals(SearchCatalog.ALL, field(activity, "searchSource"))
            chooseSource(activity, UiPreferences.sourceName(activity, "wy"))
            assertEquals("wy", field(activity, "searchSource"))
            chooseSource(activity, activity.getString(R.string.search_all_sources))
            assertEquals(SearchCatalog.ALL, field(activity, "searchSource"))
            listOf(AdaptiveLayout(360, 800), AdaptiveLayout(600, 800), AdaptiveLayout(1024, 768)).forEach { layout ->
                instrumentation.runOnMainSync {
                    set(activity, "uiLayout", layout); invoke(activity, "buildUi"); open(activity)
                    val root = measure(activity, layout.widthDp, layout.heightDp)
                    val search = field(activity, "search") as EditText
                    val source = field(activity, "searchSourceButton") as View
                    val clear = field(activity, "searchClearButton") as View
                    assertTrue((field(activity, "searchTabs") as View).isShown)
                    val resultArea = (field(activity, "list") as View).parent as View
                    assertTrue("The empty/results area must retain usable height", resultArea.height > 200 * activity.resources.displayMetrics.density)
                    assertTrue((field(activity, "miniBar") as View).bottom <= root.height)
                    if (!layout.expanded) {
                        assertEquals(View.GONE, (field(activity, "listHeaderView") as View).visibility)
                        assertEquals(View.GONE, (field(activity, "status") as View).visibility)
                        assertEquals(activity.getString(R.string.search_placeholder), search.hint.toString())
                        assertTrue(source.right <= search.left); assertTrue(search.right <= clear.left)
                        assertTrue(search.width > 100 * activity.resources.displayMetrics.density)
                    }
                    assertEquals(activity.getString(R.string.search_empty), (field(activity, "emptyHint") as TextView).text.toString())
                    snapshot("search-${layout.widthDp}-idle", root)
                }
            }
        }
    }

    @Test fun partialResultsRetryTheMissingPageAndClearDiscardsLateResponses() {
        val attempts = AtomicInteger()
        val requests = ConcurrentLinkedQueue<Pair<String, Int>>()
        val catalog = SearchCatalog(songSearch = { source, query, page ->
            requests += source to page
            if (query == "slow") withContext(NonCancellable) { delay(250) }
            if (source == "kg" && attempts.incrementAndGet() == 1) throw IOException("fixture unavailable")
            listOf(song(source, query))
        })
        Fixture(catalog).use { fixture ->
            val activity = fixture.activity
            instrumentation.runOnMainSync { (field(activity, "search") as EditText).setText("probe"); submit(activity) }
            waitForSearch(activity)
            @Suppress("UNCHECKED_CAST") val first = field(activity, "visibleSongs") as List<Song>
            assertEquals(4, first.size); assertFalse(first.any { it.source == SearchCatalog.ALL })
            first.forEach { assertEquals(it.source, JSONObject(it.sourceInfo!!).getString("source")) }
            assertEquals(View.VISIBLE, (field(activity, "loadMore") as View).visibility)
            instrumentation.runOnMainSync { submit(activity, true) }
            waitForSearch(activity)
            assertEquals(5, (field(activity, "list") as ListView).adapter.count)
            assertEquals(2, requests.count { it == ("kg" to 1) })
            assertEquals(1, requests.count { it == ("kw" to 1) })
            instrumentation.runOnMainSync {
                (field(activity, "search") as EditText).setText("slow"); submit(activity)
                (field(activity, "searchClearButton") as View).performClick()
            }
            SystemClock.sleep(400); instrumentation.waitForIdleSync()
            assertTrue((field(activity, "search") as EditText).text.isEmpty())
            assertEquals(0, (field(activity, "list") as ListView).adapter?.count ?: 0)
            assertEquals(View.GONE, (field(activity, "loadMore") as View).visibility)
            assertEquals(false, field(activity, "catalogLoading"))
        }
    }

    @Test fun playlistTabRejectsOldSongResultsAndRestoresItsResultsAndCursor() {
        val catalog = SearchCatalog(
            songSearch = { source, query, _ -> withContext(NonCancellable) { delay(200) }; listOf(song(source, query)) },
            playlistSearch = { source, query, _ ->
                delay(10)
                (1..if (source == "kg") 20 else 1).map { OnlinePlaylist("$it", "", "$query · $source $it", "Author", source) }
            }
        )
        Fixture(catalog).use { fixture ->
            var activity = fixture.activity
            instrumentation.runOnMainSync {
                (field(activity, "search") as EditText).setText("same"); submit(activity)
                root(activity).findViewWithTag<View>("search-tab-playlists").performClick()
            }
            waitForSearch(activity); SystemClock.sleep(250)
            assertEquals(true, field(activity, "playlistSearchMode"))
            assertEquals(24, (field(activity, "playlistSearchGrid") as GridView).adapter.count)
            assertTrue((field(activity, "playlistSearchGrid") as GridView).adapter.getItem(0) is OnlinePlaylist)
            assertEquals(mapOf("kg" to 2), field(activity, "searchNextPages"))
            instrumentation.runOnMainSync { snapshot("search-playlists", root(activity)) }
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            instrumentation.runOnMainSync { activity.recreate() }
            val restored = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
            instrumentation.removeMonitor(monitor); assertNotNull(restored)
            activity = restored as MainActivity; fixture.activity = activity
            instrumentation.waitForIdleSync()
            assertEquals(SearchCatalog.ALL, field(activity, "searchSource"))
            assertEquals(true, field(activity, "playlistSearchMode"))
            assertEquals("same", (field(activity, "search") as EditText).text.toString())
            assertEquals(24, (field(activity, "playlistSearchGrid") as GridView).adapter.count)
            assertEquals(mapOf("kg" to 2), field(activity, "searchNextPages"))
        }
    }

    @Test fun englishLargeFontSearchFitsACompactDarkWindow() {
        Fixture(overrides = mapOf("common.langId" to "en", "common.fontScale" to "1.3", "theme.id" to "black")).use { fixture ->
            val activity = fixture.activity
            instrumentation.runOnMainSync {
                set(activity, "uiLayout", AdaptiveLayout(360, 800)); invoke(activity, "buildUi"); open(activity)
                val root = measure(activity, 360, 800)
                val search = field(activity, "search") as View
                assertTrue(search.width > 80 * activity.resources.displayMetrics.density)
                val tabs = field(activity, "searchTabs") as View
                assertTrue(tabs.width > 0); assertTrue(tabs.right <= (tabs.parent as View).width)
                snapshot("search-english-large", root)
            }
        }
    }

    @Test fun playlistCoversUseThreePhoneColumnsAndMoreTabletColumnsAndKeepPagingAndBackPosition() {
        CoverHttpFixture().use { server ->
            val catalog = SearchCatalog(
                songSearch = { source, query, _ -> listOf(song(source, query)) },
                playlistSearch = { source, query, page -> (1..20).map { index ->
                    val id = (page - 1) * 20 + index
                    OnlinePlaylist("$id", "8", "$query · $source $id · 适合在海边听的音乐", "Author", source,
                        if (source == "kg" && id == 3) "" else "${server.address}/$source-$id.png")
                } }
            )
            Fixture(catalog).use { fixture ->
                val activity = fixture.activity
                fun grid() = field(activity, "playlistSearchGrid") as GridView
                instrumentation.runOnMainSync {
                    (field(activity, "search") as EditText).setText("大海")
                    root(activity).findViewWithTag<View>("search-tab-playlists").performClick()
                }
                waitForSearch(activity)
                assertEquals(100, grid().count)
                for (layout in listOf(AdaptiveLayout(360, 800), AdaptiveLayout(600, 800), AdaptiveLayout(1024, 768))) {
                    instrumentation.runOnMainSync {
                        set(activity, "uiLayout", layout)
                        val density = activity.resources.displayMetrics.density
                        activity.window.setLayout((layout.widthDp * density).toInt(), (layout.heightDp * density).toInt())
                        invoke(activity, "applyThemeInPlace")
                    }
                    awaitUi("Covers did not settle at ${layout.widthDp} dp") {
                        val target = grid()
                        target.isShown && target.childCount > 0 && !target.isLayoutRequested &&
                            (0 until target.childCount).all { index ->
                                val cover = ((target.getChildAt(index) as ViewGroup).getChildAt(0) as ViewGroup).getChildAt(1) as ImageView
                                cover.tag == "" || cover.drawable is BitmapDrawable
                            }
                    }
                    instrumentation.runOnMainSync {
                        val target = grid()
                        if (!layout.expanded) assertEquals(3, target.numColumns) else assertTrue(target.numColumns > 3)
                        assertEquals(View.GONE, (field(activity, "list") as View).visibility)
                        for (index in 0 until target.childCount) {
                            val card = target.getChildAt(index) as ViewGroup
                            val square = card.getChildAt(0)
                            val item = target.adapter.getItem(target.firstVisiblePosition + index) as OnlinePlaylist
                            assertEquals(square.width, square.height)
                            assertTrue(card.left >= target.paddingLeft && card.right <= target.width - target.paddingRight)
                            assertEquals(item.name, card.findViewWithTag<TextView>("playlist-card-title").text.toString())
                            assertEquals(item.source, card.findViewWithTag<TextView>("playlist-card-source").text.toString())
                        }
                        snapshot("search-cover-grid-${layout.widthDp}", root(activity))
                        target.smoothScrollBy((450 * activity.resources.displayMetrics.density).toInt(), 1)
                    }
                    awaitUi { grid().firstVisiblePosition > 0 }
                    instrumentation.waitForIdleSync(); SystemClock.sleep(100)
                }
                awaitUi { (0 until grid().childCount).all { index ->
                    val cover = ((grid().getChildAt(index) as ViewGroup).getChildAt(0) as ViewGroup).getChildAt(1) as ImageView
                    cover.tag == "" || cover.drawable is BitmapDrawable
                } }
                val bitmaps = mutableMapOf<Any, Bitmap>()
                instrumentation.runOnMainSync {
                    for (index in 0 until grid().childCount) {
                        val cover = ((grid().getChildAt(index) as ViewGroup).getChildAt(0) as ViewGroup).getChildAt(1) as ImageView
                        (cover.drawable as? BitmapDrawable)?.let { bitmaps[cover.tag] = it.bitmap }
                    }
                }
                repeat(8) {
                    instrumentation.runOnMainSync { root(activity).requestLayout() }; SystemClock.sleep(60)
                    instrumentation.runOnMainSync {
                        for (index in 0 until grid().childCount) {
                            val cover = ((grid().getChildAt(index) as ViewGroup).getChildAt(0) as ViewGroup).getChildAt(1) as ImageView
                            bitmaps[cover.tag]?.let { assertSame("An unchanged visible cover must stay loaded", it, (cover.drawable as? BitmapDrawable)?.bitmap) }
                        }
                    }
                }
                val first = grid().firstVisiblePosition
                instrumentation.runOnMainSync { (field(activity, "loadMore") as View).performClick() }
                waitForSearch(activity)
                assertEquals(200, grid().count)
                awaitUi("Paging did not restore row $first") { grid().firstVisiblePosition == first }
                var opened: OnlinePlaylist? = null
                val selected = grid().adapter.getItem(first + 1) as OnlinePlaylist
                instrumentation.runOnMainSync {
                    activity.onlinePlaylistSongs = { playlist, _ -> opened = playlist; listOf(song(playlist.source, "Fixture track")) }
                    grid().performItemClick(grid().getChildAt(1), first + 1, (first + 1).toLong())
                }
                awaitUi { activity.navigationController().currentDestination?.id == R.id.playlist_songs && (field(activity, "list") as ListView).count == 1 }
                assertEquals(selected, opened)
                instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
                awaitUi("Returning from a playlist did not restore row $first") { activity.navigationController().currentDestination?.id == R.id.music_page && grid().isShown && grid().firstVisiblePosition == first }
                assertEquals(200, grid().count)
                instrumentation.runOnMainSync { root(activity).findViewWithTag<View>("search-tab-songs").performClick() }
                waitForSearch(activity)
                assertEquals(View.GONE, grid().visibility)
                assertTrue((field(activity, "list") as ListView).isShown)
                assertEquals(5, (field(activity, "list") as ListView).count)
                instrumentation.runOnMainSync { (field(activity, "search") as EditText).text.clear() }
                assertEquals(0, grid().adapter?.count ?: 0)
            }
        }
    }

    @Test fun cancellingPlaylistBackKeepsContentAndCommittedBackRestoresSearch() {
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,
            cn.toside.music.nativeapp.utils.PermissionPolicy.audio(Build.VERSION.SDK_INT))
        val songRequests = AtomicInteger()
        Fixture().use { fixture ->
            val activity = fixture.activity
            instrumentation.runOnMainSync {
                activity.onlinePlaylistSongs = { _, _ -> songRequests.incrementAndGet(); listOf(song("kw", "Playlist track")) }
                (field(activity, "search") as EditText).setText("saved playlists")
                root(activity).findViewWithTag<View>("search-tab-playlists").performClick()
            }
            waitForSearch(activity)
            val results = (field(activity, "playlistSearchGrid") as GridView).adapter
            val count = results.count
            assertTrue(count > 0)
            val query = (field(activity, "search") as EditText).text.toString()
            val cursors = (field(activity, "searchNextPages") as Map<*, *>).toMap()
            instrumentation.runOnMainSync {
                val list = field(activity, "playlistSearchGrid") as GridView
                list.performItemClick(list.getChildAt(0), 0, list.adapter.getItemId(0))
            }
            waitForSearch(activity); instrumentation.waitForIdleSync()
            val host = activity.supportFragmentManager.findFragmentById(R.id.main_nav_host) as NavHostFragment
            val manager = host.childFragmentManager
            val details = manager.primaryNavigationFragment as MusicPageFragment
            val content = activity.musicPageContent()
            val list = field(activity, "list") as ListView
            val detailAdapter = list.adapter
            val miniPlayer = field(activity, "miniBar") as View
            var previewsCreated = 0
            val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: android.os.Bundle?) {
                    if (fragment is MusicPageFragment && fragment !== details) previewsCreated++
                }
            }
            instrumentation.runOnMainSync { manager.registerFragmentLifecycleCallbacks(callbacks, false) }
            try {
                repeat(3) {
                    instrumentation.runOnMainSync {
                        activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
                    }
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync {
                        activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 400f, .4f, BackEventCompat.EDGE_LEFT))
                        activity.onBackPressedDispatcher.dispatchOnBackCancelled()
                    }
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync {
                        assertEquals(R.id.playlist_songs, activity.navigationController().currentDestination?.id)
                        assertSame(details, manager.primaryNavigationFragment)
                        assertSame("Cancelled back must leave the live content in the current page", details.requireView(), content.parent)
                        assertTrue(content.isShown); assertTrue(list.isShown)
                        assertSame(detailAdapter, list.adapter); assertEquals(1, list.count)
                        assertSame(miniPlayer, field(activity, "miniBar"))
                    }
                }
                assertTrue("The real Fragment back preview must have created the previous music page", previewsCreated > 0)
                assertEquals(1, songRequests.get())
                instrumentation.runOnMainSync {
                    activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 400f, .8f, BackEventCompat.EDGE_LEFT))
                    activity.onBackPressedDispatcher.onBackPressed()
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertEquals(R.id.music_page, activity.navigationController().currentDestination?.id)
                    assertTrue(activity.musicPageContent().isShown)
                    assertEquals(query, (field(activity, "search") as EditText).text.toString())
                    assertEquals(true, field(activity, "playlistSearchMode"))
                    assertEquals(cursors, field(activity, "searchNextPages"))
                    val restored = field(activity, "playlistSearchGrid") as GridView
                    assertTrue(restored.isShown); assertEquals(count, restored.count)
                    assertTrue(restored.adapter.getItem(0) is OnlinePlaylist)
                }
            } finally {
                instrumentation.runOnMainSync { manager.unregisterFragmentLifecycleCallbacks(callbacks) }
            }
        }
    }
}
