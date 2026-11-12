package cn.toside.music.nativeapp.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.Playlist
import cn.toside.music.nativeapp.data.Playlists
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.online.*
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class PlatformPopupTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(description: String = "platform menu state", check: () -> Boolean) {
        repeat(250) { var done = false; main { done = check() }; if (done) return; SystemClock.sleep(40) }
        throw AssertionError("Timed out waiting for $description")
    }
    private fun field(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun invoke(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun menu() = WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<ListView>().firstOrNull { it.tag == "platform-menu" && it.isShown }
    private fun navigationActions() = WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<ListView>()
        .firstOrNull { it.tag == "app-choice-menu" && it.isShown }
    private fun bounds(view: View) = Rect().also { rect -> val p = IntArray(2).also(view::getLocationOnScreen); rect.set(p[0], p[1], p[0] + view.width, p[1] + view.height) }
    private fun visibleWindow(view: View) = Rect().also { frame ->
        view.getWindowVisibleDisplayFrame(frame)
        assertTrue("Visible frame $frame must intersect the app window ${bounds(view.rootView)}", frame.intersect(bounds(view.rootView)))
    }
    private fun awaitLayout(view: () -> View?) {
        await("the menu anchor and its window to finish layout") {
            view()?.let { it.isShown && it.isLaidOut && !it.isLayoutRequested && !it.rootView.isLayoutRequested } == true
        }
    }
    private fun awaitHostFocus(activity: MainActivity) {
        await("the live Activity window to regain input focus") {
            assertFalse("Menu dismissal must not finish the Activity", activity.isFinishing)
            assertFalse("Menu dismissal must not destroy the Activity", activity.isDestroyed)
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && activity.window.decorView.hasWindowFocus()
        }
    }
    private fun dismissWithBack(activity: MainActivity) {
        // A visible window may not yet own input focus; BACK must reach the popup, not the Activity.
        await("popup input focus before BACK") { menu()?.rootView?.hasWindowFocus() == true }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await("popup dismissal after BACK") { menu() == null }
        awaitHostFocus(activity)
    }
    private fun outsidePoint(window: Rect, popup: Rect): Pair<Float, Float> {
        val inset = (minOf(window.width(), window.height()) / 40).coerceAtLeast(2)
        return listOf(window.right - inset to window.top + inset, window.left + inset to window.top + inset,
            window.right - inset to window.bottom - inset, window.left + inset to window.bottom - inset)
            .first { (x, y) -> window.contains(x, y) && !popup.contains(x, y) }
            .let { (x, y) -> x.toFloat() to y.toFloat() }
    }
    private fun verify(anchor: View, selected: String, end: Boolean = false) {
        val list = requireNotNull(menu()); val popup = bounds(list); val button = bounds(anchor)
        val frame = visibleWindow(anchor)
        val geometry = "anchor=${anchor.tag} $button, popup=$popup, visible window=$frame, alignEnd=$end"
        assertTrue("The whole menu must stay inside the visible app window: $geometry", frame.contains(popup))
        assertEquals("Menu must open below its anchor: $geometry", button.bottom, popup.top)
        // Scrollable filters can leave a button partially clipped; the menu must then avoid the window edge.
        val alignedLeft = if (end) button.right - popup.width() else button.left
        val expectedLeft = alignedLeft.coerceIn(frame.left, frame.right - popup.width())
        assertTrue("Expected menu left=$expectedLeft: $geometry", abs(expectedLeft - popup.left) <= 1)
        assertEquals(0, (list.rootView.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val selectedRow = descendants(list).filterIsInstance<TextView>().first { it.tag == "platform-$selected" }
        assertEquals(UiPalette(anchor.context, NativeThemes(anchor.context).active()).primary, selectedRow.currentTextColor)
        assertTrue(selectedRow.isActivated)
    }
    private fun choose(id: String) {
        val list = requireNotNull(menu())
        val row = descendants(list).first { it.tag == "platform-$id" }
        val position = list.getPositionForView(row)
        list.performItemClick(row, position, list.adapter.getItemId(position))
    }
    private fun capture(activity: MainActivity, name: String, popup: ListView? = menu()) {
        if (InstrumentationRegistry.getArguments().getString("popupScreenshots") != "true") return
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); root.draw(canvas)
        popup?.rootView?.let { popupRoot ->
            val origin = bounds(root); val position = bounds(popupRoot)
            canvas.save(); canvas.translate((position.left - origin.left).toFloat(), (position.top - origin.top).toFloat()); popupRoot.draw(canvas); canvas.restore()
        }
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "popup-validation").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }

    @Test fun playlistNamesSwitchDirectlyAndMoreOpensTheAnchoredMenu() = kotlinx.coroutines.runBlocking {
        val context = instrumentation.targetContext
        Playlists(context).use { lists ->
            val trial = lists.trialList()
            val favorites = lists.loveList()
            val custom = lists.create("测试歌单")
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val root = activity.window.decorView
            fun option(tag: String) = root.findViewWithTag<View>(tag)
            fun actions() = WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<ListView>()
                .firstOrNull { it.tag == "playlist-actions-menu" && it.isShown }
            fun actionPosition(menu: ListView, key: String) = menu.getPositionForView(descendants(menu).first { it.tag == "playlist-actions-$key" })
            fun tap(tag: String) {
                var target = Rect()
                main { target = bounds(requireNotNull(option(tag))) }
                val time = SystemClock.uptimeMillis()
                for (event in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val motion = MotionEvent.obtain(time, SystemClock.uptimeMillis(), event, target.exactCenterX(), target.exactCenterY(), 0)
                    try { instrumentation.sendPointerSync(motion) } finally { motion.recycle() }
                }
            }
            try {
                awaitHostFocus(activity)
                main {
                    val density = activity.resources.displayMetrics.density
                    MainActivity::class.java.getDeclaredField("uiLayout").apply { isAccessible = true }.set(activity, AdaptiveLayout(360, 800))
                    activity.window.setLayout((360 * density).toInt(), (800 * density).toInt())
                    invoke(activity, "applyThemeInPlace")
                }
                awaitLayout { field(activity, "pageTitle") as View }
                main { invoke(activity, "openMyLists") }
                await("favorites page") { (field(activity, "activePlaylist") as? Playlist)?.id == favorites && (field(activity, "loadJob") as? Job)?.isActive != true }
                main { (field(activity, "pageTitle") as View).performClick() }
                await("the playlist rows") {
                    option("playlist-drawer-panel")?.translationX == 0f && option("playlist-more-$favorites")?.isShown == true &&
                        option("playlist-choice-$custom")?.isShown == true
                }
                main {
                    assertNull(option("playlist-choice-local"))
                    assertNull(option("playlist-more-local"))
                    assertTrue(requireNotNull(option("playlist-choice-$favorites")).isSelected)
                    assertTrue(bounds(requireNotNull(option("playlist-choice-$trial"))).top < bounds(requireNotNull(option("playlist-choice-$favorites"))).top)
                    assertEquals(activity.getString(R.string.playlist_trial), (option("playlist-choice-$trial") as TextView).text.toString())
                }
                for (builtin in listOf(trial, favorites)) {
                    tap("playlist-more-$builtin")
                    await("built-in playlist popup input focus") { actions()?.rootView?.hasWindowFocus() == true }
                    main {
                        val popup = requireNotNull(actions())
                        val button = bounds(requireNotNull(option("playlist-more-$builtin")))
                        val menu = bounds(popup)
                        assertEquals(button.bottom, menu.top)
                        assertTrue(abs(button.right - menu.right) <= 1)
                        assertTrue(visibleWindow(option("playlist-more-$builtin")!!).contains(menu))
                        assertEquals(favorites, (field(activity, "activePlaylist") as Playlist).id)
                        val remove = actionPosition(popup, "DELETE")
                        assertFalse(popup.adapter.isEnabled(remove))
                        assertFalse(popup.adapter.isEnabled(actionPosition(popup, "RENAME")))
                        val refresh = descendants(popup).filterIsInstance<TextView>().first { it.tag == "playlist-actions-REFRESH" }
                        assertEquals(0, refresh.layout.getEllipsisCount(0))
                        popup.performItemClick(popup.getChildAt(remove - popup.firstVisiblePosition), remove, remove.toLong())
                        assertSame(popup, actions())
                        capture(activity, "playlist-picker-phone-$builtin", popup)
                    }
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    await("popup dismissal") { actions() == null && option("playlist-choice-$custom")?.isShown == true }
                    awaitHostFocus(activity)
                }
                tap("playlist-choice-$custom")
                await("single-tap playlist switch") {
                    (field(activity, "activePlaylist") as? Playlist)?.id == custom && option("playlist-drawer-panel")?.isShown == false
                }
                main { (field(activity, "pageTitle") as View).performClick() }
                await("the selected playlist row") { option("playlist-choice-$custom")?.let { it.isShown && it.isSelected } == true && option("playlist-drawer-panel")?.translationX == 0f }
                tap("playlist-more-$custom")
                await("custom playlist actions") { actions()?.rootView?.hasWindowFocus() == true }
                main {
                    val popup = requireNotNull(actions())
                    val rename = actionPosition(popup, "RENAME")
                    assertTrue(popup.adapter.isEnabled(rename))
                    popup.performItemClick(popup.getChildAt(rename - popup.firstVisiblePosition), rename, rename.toLong())
                }
                await("rename dialog") { WindowInspector.getGlobalWindowViews().any { it.findViewById<View>(android.R.id.button1)?.isShown == true } }
                main {
                    val dialog = WindowInspector.getGlobalWindowViews().first { it.findViewById<View>(android.R.id.button1)?.isShown == true }
                    descendants(dialog).filterIsInstance<EditText>().single().setText("Renamed playlist")
                    dialog.findViewById<View>(android.R.id.button1).performClick()
                }
                await("renamed row in the open drawer") { (option("playlist-choice-$custom") as? TextView)?.text?.endsWith("Renamed playlist") == true }
                assertEquals("Renamed playlist", lists.list().single { it.id == custom }.name)
                main { activity.onBackPressedDispatcher.onBackPressed() }
                await("drawer dismissal") { option("playlist-drawer-panel")?.isShown == false }
            } finally {
                main { activity.finish() }
                lists.delete(custom)
            }
        }
    }

    @Test fun providerMenusAnchorToSearchPlaylistsAndChartsAndDoNotResetCurrentChoices() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val searches = AtomicInteger(); val playlistRequests = AtomicInteger(); val chartRequests = AtomicInteger()
        try {
            await { activity.settingsPlayer() != null }
            main {
                MainActivity::class.java.getDeclaredField("searchCatalog").apply { isAccessible = true }.set(activity,
                    SearchCatalog(songSearch = { source, query, _ -> searches.incrementAndGet(); listOf(Song(1, Uri.EMPTY, query, "Artist", 1000, source, "")) }))
                activity.playlistBrowserRepository = object : PlaylistBrowserRepository {
                    override suspend fun page(query: PlaylistBrowseQuery, page: Int): List<OnlinePlaylist> { playlistRequests.incrementAndGet(); return emptyList() }
                    override suspend fun categories(source: String) = emptyList<OnlineTagGroup>()
                }
                activity.chartsRepository = object : ChartsRepository {
                    override fun boards(source: String) = NativeChartsRepository().boards(source)
                    override suspend fun page(board: Board, page: Int): List<Song> { chartRequests.incrementAndGet(); return emptyList() }
                }
                MainActivity::class.java.getDeclaredMethod("openSearchPage", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
                val density = activity.resources.displayMetrics.density
                MainActivity::class.java.getDeclaredField("uiLayout").apply { isAccessible = true }.set(activity, AdaptiveLayout(360, 800))
                activity.window.setLayout((360 * density).toInt(), (800 * density).toInt())
                invoke(activity, "applyThemeInPlace")
                (field(activity, "search") as EditText).setText("menu draft")
                (field(activity, "suggestionJob") as? Job)?.cancel()
                MainActivity::class.java.getDeclaredMethod("searchOnline", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
            }
            await { searches.get() == 5 && (field(activity, "loadJob") as? Job)?.isActive != true }
            awaitLayout { (field(activity, "searchSourceButton") as View).takeIf { it.isShown } ?: field(activity, "heading") as View }
            main { invoke(activity, "showSearchSources") }; await { menu()?.childCount == 6 }
            main {
                val anchor = (field(activity, "searchSourceButton") as View).takeIf { it.isShown } ?: field(activity, "heading") as View
                verify(anchor, SearchCatalog.ALL); capture(activity, "search-platforms")
                choose(SearchCatalog.ALL)
                assertEquals(5, searches.get()); assertEquals(5, (field(activity, "allSongs") as List<*>).size)
                assertEquals("menu draft", (field(activity, "search") as EditText).text.toString())
                invoke(activity, "showSearchSources")
            }
            await { menu()?.childCount == 6 }
            main { choose("wy") }; await { searches.get() == 6 && (field(activity, "loadJob") as? Job)?.isActive != true }
            main { assertEquals("wy", field(activity, "searchSource")); invoke(activity, "showSearchSources") }
            dismissWithBack(activity)
            assertEquals(R.id.music_page, activity.navigationController().currentDestination?.id)

            // An ID without a recognizable provider uses the same anchored platform menu.
            main { activity.openPlaylistLink() }
            await("playlist ID dialog input focus") { WindowInspector.getGlobalWindowViews().any { it.findViewById<View>(android.R.id.button1) != null && it.hasWindowFocus() } }
            main {
                val dialog = WindowInspector.getGlobalWindowViews().first { it.findViewById<View>(android.R.id.button1) != null }
                descendants(dialog).filterIsInstance<EditText>().first().setText("12345")
                dialog.findViewById<View>(android.R.id.button1).performClick()
            }
            await { menu()?.childCount == 5 }
            dismissWithBack(activity)

            main { invoke(activity, "showOnlinePlaylists") }
            await { activity.navigationController().currentDestination?.id == R.id.playlist_browser && playlistRequests.get() == 1 }
            awaitLayout { activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag("playlist-source") }
            main { activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("playlist-source").performClick() }
            await { menu()?.childCount == 5 }
            main {
                verify(activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag("playlist-source"), "kw", end = true)
                capture(activity, "playlist-platforms"); choose("kw"); assertEquals(1, playlistRequests.get())
                activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("playlist-source").performClick()
            }
            await { menu()?.childCount == 5 }; main { choose("tx") }; await { playlistRequests.get() == 2 && menu() == null }

            main { invoke(activity, "showBoards") }; await { activity.chartsFragment()?.isResumed == true && chartRequests.get() == 1 }
            awaitLayout { activity.chartsFragment()?.view?.findViewWithTag("charts-source") }
            main { activity.chartsFragment()!!.requireView().findViewWithTag<View>("charts-source").performClick() }
            await { menu()?.childCount == 5 }
            main { verify(activity.chartsFragment()!!.requireView().findViewWithTag("charts-source"), "kw"); capture(activity, "chart-platforms") }
            await("chart popup input focus before outside touch") { menu()?.rootView?.hasWindowFocus() == true }
            var outside = 0f to 0f
            main {
                val window = bounds(activity.window.decorView)
                val visible = Rect().also(activity.window.decorView::getWindowVisibleDisplayFrame)
                assertTrue(window.intersect(visible))
                outside = outsidePoint(window, bounds(menu()!!))
            }
            // A real outside touch dismisses just the popup, leaving the charts destination intact.
            val time = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                MotionEvent.obtain(time, time + 40, action, outside.first, outside.second, 0).also { instrumentation.sendPointerSync(it); it.recycle() }
            }
            await { menu() == null }; awaitHostFocus(activity)
            assertEquals(R.id.charts_page, activity.navigationController().currentDestination?.id)
            main { activity.chartsFragment()!!.requireView().findViewWithTag<View>("charts-source").performClick() }
            await { menu()?.childCount == 5 }; main { choose("kg") }; await { activity.chartsFragment()?.model?.state?.value?.board?.source == "kg" && chartRequests.get() == 2 }
        } finally { main { activity.finish() } }
    }

    @Test fun partiallyClippedAnchorsKeepMenusInsideBothWindowEdges() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val popup = PlatformPopup()
        try {
            await { activity.settingsPlayer() != null }
            lateinit var viewport: FrameLayout
            lateinit var anchor: TextView
            main {
                val density = activity.resources.displayMetrics.density
                fun dp(value: Int) = (value * density).toInt()
                viewport = FrameLayout(activity)
                anchor = TextView(activity).apply { tag = "clipped-platform-anchor"; text = "Platform" }
                viewport.addView(anchor, FrameLayout.LayoutParams(dp(160), dp(48)).apply { topMargin = dp(16) })
                activity.findViewById<ViewGroup>(android.R.id.content).addView(viewport, ViewGroup.LayoutParams(-1, -1))
            }
            awaitLayout { anchor }
            for (end in listOf(false, true)) {
                main {
                    val frame = visibleWindow(anchor)
                    val origin = bounds(viewport)
                    anchor.x = ((if (end) frame.right else frame.left) - origin.left - anchor.width / 2).toFloat()
                    val button = bounds(anchor)
                    assertTrue("Anchor must remain partially visible", button.intersects(frame.left, frame.top, frame.right, frame.bottom))
                    assertTrue("Exercise a clipped anchor at the ${if (end) "right" else "left"} edge", if (end) button.right > frame.right else button.left < frame.left)
                    popup.show(anchor, PlatformPopup.sources, PlatformPopup.sources.map { UiPreferences.sourceName(activity, it) },
                        "kw", field(activity, "palette") as UiPalette, alignEnd = end) { fail("Opening a menu must not select a provider") }
                }
                await { menu()?.childCount == 5 }
                main {
                    verify(anchor, "kw", end)
                    val frame = visibleWindow(anchor); val menuBounds = bounds(menu()!!)
                    assertEquals("Clipped anchors must pin the menu to the visible window edge", if (end) frame.right else frame.left,
                        if (end) menuBounds.right else menuBounds.left)
                    popup.dismiss()
                }
                await { menu() == null }
            }
        } finally { main { popup.dismiss(); activity.finish() } }
    }

    @Test fun outsideTouchCoordinatesStayOutsideTheMenuAtPhoneAndTabletDensities() {
        for (density in listOf(1f, 1.33f, 2.75f, 4f)) {
            val window = Rect(20, 50, 20 + (360 * density).toInt(), 50 + (800 * density).toInt())
            val popup = Rect(window.left, window.top + (100 * density).toInt(),
                window.left + (224 * density).toInt(), window.top + (360 * density).toInt())
            val (x, y) = outsidePoint(window, popup)
            assertTrue(window.contains(x.toInt(), y.toInt()))
            assertFalse(popup.contains(x.toInt(), y.toInt()))
        }
    }

    @Test fun darkLargeFontMenuScrollsWithinShortWindowsAndClosesWhenAnchorIsRebuilt() {
        val preferences = instrumentation.targetContext.getSharedPreferences("app-settings", 0)
        val values = mapOf("common.langId" to "en", "common.fontScale" to "1.6", "theme.id" to "black")
        val saved = (values.keys + "common.isAutoTheme").associateWith { preferences.all[it] }
        preferences.edit().putBoolean("common.isAutoTheme", false).apply { values.forEach { (key, value) -> putString(key, value) } }.commit()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            await { activity.settingsPlayer() != null }
            main {
                MainActivity::class.java.getDeclaredMethod("openSearchPage", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
                val density = activity.resources.displayMetrics.density
                activity.window.setLayout((600 * density).toInt(), (280 * density).toInt())
            }
            instrumentation.waitForIdleSync()
            main { invoke(activity, "showSearchSources") }
            await { menu()?.childCount?.let { it > 0 } == true }
            main {
                val list = menu()!!; val visible = Rect().also(list::getWindowVisibleDisplayFrame); val rect = bounds(list)
                assertTrue(rect.top >= visible.top && rect.bottom <= visible.bottom)
                assertTrue(rect.left >= visible.left && rect.right <= visible.right)
                val window = bounds(activity.window.decorView)
                assertTrue("Menu $rect must fit the app window $window", window.contains(rect))
                assertTrue("Short windows must scroll the six choices", list.canScrollVertically(-1) || list.canScrollVertically(1))
                val palette = field(activity, "palette") as UiPalette
                assertTrue(palette.theme.dark)
                descendants(list).filterIsInstance<TextView>().forEach { assertEquals(if (it.isActivated) palette.primary else palette.foreground, it.currentTextColor) }
                capture(activity, "platforms-dark-large-font-short-window")
                list.setSelection(5)
            }
            await { menu()?.findViewWithTag<View>("platform-${SearchCatalog.ALL}")?.isShown == true }
            main { choose(SearchCatalog.ALL); invoke(activity, "showSearchSources") }
            await { menu() != null }
            main { invoke(activity, "applyThemeInPlace") }
            await { menu() == null }
        } finally {
            main { activity.finish() }
            preferences.edit().apply { saved.forEach { (key, value) -> when (value) {
                is String -> putString(key, value); is Boolean -> putBoolean(key, value); else -> remove(key)
            } } }.commit()
        }
    }

    @Test fun sidebarMoreAlignsWithNavigationIconsAndContainsOnlyIndependentActions() {
        val preferences = instrumentation.targetContext.getSharedPreferences("app-settings", 0)
        val keys = listOf("common.drawerLayoutPosition", "common.showBackBtn", "common.showExitBtn")
        val saved = keys.associateWith { preferences.all[it] }
        preferences.edit().putBoolean("common.showBackBtn", true).putBoolean("common.showExitBtn", true).commit()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            awaitHostFocus(activity)
            for (width in listOf(600, 1024)) for (right in listOf(false, true)) {
                preferences.edit().putString("common.drawerLayoutPosition", if (right) "right" else "left").commit()
                main {
                    MainActivity::class.java.getDeclaredField("uiLayout").apply { isAccessible = true }.set(activity, AdaptiveLayout(width, 768))
                    val density = activity.resources.displayMetrics.density
                    activity.window.setLayout((width * density).toInt(), (768 * density).toInt())
                    invoke(activity, "applyThemeInPlace")
                }
                awaitLayout { activity.window.decorView.findViewWithTag<View>("navigation-more") }
                main {
                    val more = activity.window.decorView.findViewWithTag<ViewGroup>("navigation-more")
                    val local = activity.window.decorView.findViewWithTag<ViewGroup>("navigation-local")
                    assertEquals(bounds(local.getChildAt(0)).centerX(), bounds(more.getChildAt(0)).centerX())
                    assertEquals(bounds(local.getChildAt(0)).width(), bounds(more.getChildAt(0)).width())
                    assertEquals(local.width, more.width)
                    more.performClick()
                }
                await("sidebar actions") { navigationActions()?.rootView?.hasWindowFocus() == true }
                main {
                    val popup = requireNotNull(navigationActions())
                    val labels = (0 until popup.count).map { popup.adapter.getItem(it).toString() }
                    val sidebar = activity.window.decorView.findViewWithTag<View>("home-sidebar")
                    val primary = descendants(sidebar).filterIsInstance<TextView>().map { it.text.toString() }.toSet()
                    assertTrue("More must not repeat sidebar entries", labels.none { it in primary })
                    assertFalse(labels.contains(activity.getString(R.string.ui_599818eb56df)))
                    listOf(R.string.ui_3158c8d56b7b, R.string.ui_a5cd0a9169e8, R.string.ui_3facd3e00be5,
                        R.string.ui_8c38525734c0, R.string.ui_f01b6815d826, R.string.ui_426744e26e7e,
                        R.string.ui_f75a93326a3c, R.string.ui_b0d269c8707c).forEach { assertTrue(labels.contains(activity.getString(it))) }
                    assertTrue(visibleWindow(sidebar).contains(bounds(popup)))
                    capture(activity, "sidebar-more-$width-${if (right) "right" else "left"}", popup)
                    (field(activity, "choicesPopup") as PlatformPopup).dismiss()
                }
                awaitHostFocus(activity)
            }
        } finally {
            main { activity.finish() }
            preferences.edit().apply { saved.forEach { (key, value) -> when (value) {
                is String -> putString(key, value); is Boolean -> putBoolean(key, value); else -> remove(key)
            } } }.commit()
        }
    }

    @Test fun phoneMoreKeepsIndependentActionsAndDispatchesTheSelectedSetting() {
        val preferences = instrumentation.targetContext.getSharedPreferences("app-settings", 0)
        val keys = listOf("common.drawerLayoutPosition", "common.showBackBtn", "common.showExitBtn")
        val saved = keys.associateWith { preferences.all[it] }
        preferences.edit().putBoolean("common.showBackBtn", true).putBoolean("common.showExitBtn", true).commit()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            awaitHostFocus(activity)
            for (right in listOf(false, true)) {
                preferences.edit().putString("common.drawerLayoutPosition", if (right) "right" else "left").commit()
                main {
                    MainActivity::class.java.getDeclaredField("uiLayout").apply { isAccessible = true }.set(activity, AdaptiveLayout(360, 800))
                    val density = activity.resources.displayMetrics.density
                    activity.window.setLayout((360 * density).toInt(), (800 * density).toInt())
                    invoke(activity, "applyThemeInPlace"); invoke(activity, "showNavigation")
                }
                awaitLayout { activity.window.decorView.findViewWithTag<View>("navigation-more") }
                main { activity.window.decorView.findViewWithTag<View>("navigation-more").performClick() }
                await("phone actions") { navigationActions()?.rootView?.hasWindowFocus() == true }
                main {
                    val popup = requireNotNull(navigationActions())
                    val labels = (0 until popup.count).map { popup.adapter.getItem(it).toString() }
                    val panel = activity.window.decorView.findViewWithTag<View>("home-drawer-panel")
                    val primary = descendants(panel).filterIsInstance<TextView>().map { it.text.toString() }.toSet()
                    assertTrue("More must not repeat drawer entries", labels.none { it in primary })
                    assertTrue(labels.contains(activity.getString(R.string.ui_727036c7fc2a)))
                    assertTrue(labels.contains(activity.getString(R.string.ui_788db1cfec2a)))
                    assertTrue((field(activity, "navigationDrawer") as HomeDrawerLayout).isOpen)
                    if (!right) (field(activity, "choicesPopup") as PlatformPopup).dismiss()
                    else {
                        val position = (0 until popup.count).single { popup.adapter.getView(it, null, popup).tag == "app-choice-11" }
                        popup.performItemClick(popup.getChildAt(position - popup.firstVisiblePosition), position, position.toLong())
                    }
                }
                if (!right) awaitHostFocus(activity)
            }
            await("playback setting selected by its original action ID") { activity.settingsFragment()?.savePageState()?.getString("category") == "playback" }
            main { assertFalse((field(activity, "navigationDrawer") as HomeDrawerLayout).isOpen) }
        } finally {
            main { activity.finish() }
            preferences.edit().apply { saved.forEach { (key, value) -> when (value) {
                is String -> putString(key, value); is Boolean -> putBoolean(key, value); else -> remove(key)
            } } }.commit()
        }
    }

    @Test fun finishingActivityDoesNotOpenAMenuFromItsStillAttachedAnchor() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val popup = PlatformPopup()
        try {
            await { activity.settingsPlayer() != null }
            main {
                val anchor = field(activity, "pageTitle") as View
                assertTrue(anchor.isAttachedToWindow)
                activity.finish()
                popup.show(anchor, listOf("kw"), listOf("Kuwo"), "kw", field(activity, "palette") as UiPalette) { }
                assertNull(menu())
            }
        } finally { main { popup.dismiss(); activity.finish() } }
    }
}
