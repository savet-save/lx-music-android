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
import android.widget.ListView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.R
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
    private fun await(check: () -> Boolean) {
        repeat(250) { var done = false; main { done = check() }; if (done) return; SystemClock.sleep(40) }
        throw AssertionError("Platform menu did not settle")
    }
    private fun field(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun invoke(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun menu() = WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<ListView>().firstOrNull { it.tag == "platform-menu" && it.isShown }
    private fun bounds(view: View) = Rect().also { rect -> val p = IntArray(2).also(view::getLocationOnScreen); rect.set(p[0], p[1], p[0] + view.width, p[1] + view.height) }
    private fun verify(anchor: View, selected: String, end: Boolean = false) {
        val list = requireNotNull(menu()); val popup = bounds(list); val button = bounds(anchor)
        assertEquals(button.bottom, popup.top)
        assertTrue(abs((if (end) button.right - popup.right else button.left - popup.left)) <= 1)
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
    private fun capture(activity: MainActivity, name: String) {
        if (InstrumentationRegistry.getArguments().getString("popupScreenshots") != "true") return
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); root.draw(canvas)
        menu()?.rootView?.let { popup ->
            val origin = bounds(root); val position = bounds(popup)
            canvas.save(); canvas.translate((position.left - origin.left).toFloat(), (position.top - origin.top).toFloat()); popup.draw(canvas); canvas.restore()
        }
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "popup-validation").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
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
            await { menu() != null }; instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await { menu() == null }; assertEquals(R.id.music_page, activity.navigationController().currentDestination?.id)

            // An ID without a recognizable provider uses the same anchored platform menu.
            main { activity.openPlaylistLink() }
            await { WindowInspector.getGlobalWindowViews().any { it.findViewById<View>(android.R.id.button1) != null } }
            main {
                val dialog = WindowInspector.getGlobalWindowViews().first { it.findViewById<View>(android.R.id.button1) != null }
                descendants(dialog).filterIsInstance<EditText>().first().setText("12345")
                dialog.findViewById<View>(android.R.id.button1).performClick()
            }
            await { menu()?.childCount == 5 }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); await { menu() == null }

            main { invoke(activity, "showOnlinePlaylists") }
            await { activity.navigationController().currentDestination?.id == R.id.playlist_browser && playlistRequests.get() == 1 }
            main { activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("playlist-source").performClick() }
            await { menu()?.childCount == 5 }
            main {
                verify(activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag("playlist-source"), "kw", end = true)
                capture(activity, "playlist-platforms"); choose("kw"); assertEquals(1, playlistRequests.get())
                activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("playlist-source").performClick()
            }
            await { menu()?.childCount == 5 }; main { choose("tx") }; await { playlistRequests.get() == 2 && menu() == null }

            main { invoke(activity, "showBoards") }; await { activity.chartsFragment()?.isResumed == true && chartRequests.get() == 1 }
            main { activity.chartsFragment()!!.requireView().findViewWithTag<View>("charts-source").performClick() }
            await { menu()?.childCount == 5 }
            main { verify(activity.chartsFragment()!!.requireView().findViewWithTag("charts-source"), "kw"); capture(activity, "chart-platforms") }
            val position = IntArray(2)
            main { menu()!!.getLocationOnScreen(position) }
            // A real outside touch dismisses just the popup, leaving the charts destination intact.
            val time = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                MotionEvent.obtain(time, time + 40, action, (position[0] + 450).toFloat(), (position[1] + 30).toFloat(), 0).also { instrumentation.sendPointerSync(it); it.recycle() }
            }
            await { menu() == null }; assertEquals(R.id.charts_page, activity.navigationController().currentDestination?.id)
            main { activity.chartsFragment()!!.requireView().findViewWithTag<View>("charts-source").performClick() }
            await { menu()?.childCount == 5 }; main { choose("kg") }; await { activity.chartsFragment()?.model?.state?.value?.board?.source == "kg" && chartRequests.get() == 2 }
        } finally { main { activity.finish() } }
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

    @Test fun finishingActivityDoesNotOpenAMenuFromItsStillAttachedAnchor() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val popup = PlatformPopup()
        try {
            await { activity.settingsPlayer() != null }
            main {
                val anchor = field(activity, "heading") as View
                assertTrue(anchor.isAttachedToWindow)
                activity.finish()
                popup.show(anchor, listOf("kw"), listOf("Kuwo"), "kw", field(activity, "palette") as UiPalette) { }
                assertNull(menu())
            }
        } finally { main { popup.dismiss(); activity.finish() } }
    }
}
