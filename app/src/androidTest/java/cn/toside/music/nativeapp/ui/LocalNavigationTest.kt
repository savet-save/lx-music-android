package cn.toside.music.nativeapp.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.Playlist
import cn.toside.music.nativeapp.data.Playlists
import cn.toside.music.nativeapp.utils.PermissionPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun field(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun invoke(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun await(description: String = "local navigation", check: () -> Boolean) {
        repeat(150) {
            var ready = false
            main { ready = check() }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for $description")
    }

    private fun assertSelection(activity: MainActivity, local: Boolean) {
        val root = activity.window.decorView
        val localRow = root.findViewWithTag<ViewGroup>("navigation-local")
        val listsRow = root.findViewWithTag<ViewGroup>("navigation-0")
        val palette = field(activity, "palette") as UiPalette
        val entries = localRow.parent as ViewGroup
        assertFalse((0 until entries.childCount).any { index ->
            val row = entries.getChildAt(index) as? ViewGroup
            (0 until (row?.childCount ?: 0)).any { (row?.getChildAt(it) as? TextView)?.text?.toString() == activity.getString(R.string.ui_c2dd14825672) }
        })
        assertEquals(local, localRow.isSelected)
        assertEquals(!local, listsRow.isSelected)
        assertEquals(if (local) palette.primary else palette.muted, (localRow.getChildAt(1) as TextView).currentTextColor)
        assertEquals(if (local) palette.muted else palette.primary, (listsRow.getChildAt(1) as TextView).currentTextColor)
        assertEquals(activity.getString(if (local) R.string.ui_a9531e47b75d else R.string.ui_27c95ec6b766), (field(activity, "pageTitle") as TextView).text.toString())
    }

    @Test fun localSelectionAndTitleStaySeparateFromPlaylistsAcrossLayoutsAndRecreation() = runBlocking {
        val context = instrumentation.targetContext
        val permission = PermissionPolicy.audio(Build.VERSION.SDK_INT)
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        val favorites = Playlists(context).use { lists -> lists.list().first { it.syncId == "love" } }
        var activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            await { activity.settingsPlayer() != null && activity.hasWindowFocus() }
            main { assertSelection(activity, local = true) }
            for (layout in listOf(AdaptiveLayout(360, 800), AdaptiveLayout(1024, 768))) {
                main {
                    val density = activity.resources.displayMetrics.density
                    MainActivity::class.java.getDeclaredField("uiLayout").apply { isAccessible = true }.set(activity, layout)
                    activity.window.setLayout((layout.widthDp * density).toInt(), (layout.heightDp * density).toInt())
                    invoke(activity, "applyThemeInPlace")
                }
                await("${layout.widthDp} dp layout") {
                    val heading = field(activity, "heading") as View
                    heading.isAttachedToWindow && heading.isLaidOut && !heading.rootView.isLayoutRequested
                }
                main {
                    MainActivity::class.java.getDeclaredMethod("loadPlaylist", Playlist::class.java).apply { isAccessible = true }.invoke(activity, favorites)
                    assertSelection(activity, local = false)
                    if (!layout.expanded) invoke(activity, "showNavigation")
                    activity.window.decorView.findViewWithTag<View>("navigation-local").performClick()
                    assertNull(field(activity, "activePlaylist"))
                    assertSelection(activity, local = true)
                    if (!layout.expanded) invoke(activity, "showNavigation")
                    assertSelection(activity, local = true)
                    activity.window.decorView.findViewWithTag<View>("navigation-0").performClick()
                }
                await("my lists at ${layout.widthDp} dp") { (field(activity, "activePlaylist") as? Playlist)?.id == favorites.id }
                main {
                    assertSelection(activity, local = false)
                    assertFalse((field(activity, "playlistDrawer") as HomeDrawerLayout).isOpen)
                    (field(activity, "heading") as View).performClick()
                }
                await("favorites row at ${layout.widthDp} dp") { activity.window.decorView.findViewWithTag<View>("playlist-choice-${favorites.id}")?.isShown == true }
                main {
                    activity.window.decorView.findViewWithTag<View>("playlist-choice-${favorites.id}").performClick()
                    assertEquals(favorites.id, (field(activity, "activePlaylist") as Playlist).id)
                    assertSelection(activity, local = false)
                    activity.window.decorView.findViewWithTag<View>("navigation-local").performClick()
                    assertSelection(activity, local = true)
                }
            }
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            main { activity.recreate() }
            val replacement = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
            instrumentation.removeMonitor(monitor)
            assertNotNull(replacement); activity = replacement as MainActivity
            await { activity.settingsPlayer() != null }
            main {
                if (field(activity, "navigationDrawer") != null) invoke(activity, "showNavigation")
                assertSelection(activity, local = true)
            }
        } finally { main { activity.finish() } }
    }

    @Test fun myListsOpensFavoritesOrLastSelectionWithoutAChooserAndSurvivesFreshLaunch() = runBlocking {
        val context = instrumentation.targetContext
        val permission = PermissionPolicy.audio(Build.VERSION.SDK_INT)
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val preferences = context.getSharedPreferences("app-settings", 0)
        val previous = preferences.getString("list.lastOpenedId", null)
        preferences.edit().remove("list.lastOpenedId").commit()
        val lists = Playlists(context)
        val favorites = lists.list().first { it.syncId == "love" }
        val customId = lists.create("Last opened ${System.nanoTime()}")
        fun launch() = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var activity = launch()
        fun clickMyLists() = main {
            if (field(activity, "navigationDrawer") != null) invoke(activity, "showNavigation")
            activity.window.decorView.findViewWithTag<View>("navigation-0").performClick()
        }
        fun assertOpened(id: Long) {
            await("playlist $id and closed chooser") { (field(activity, "activePlaylist") as? Playlist)?.id == id && (field(activity, "loadJob") as? Job)?.isActive != true && !(field(activity, "playlistDrawer") as HomeDrawerLayout).isOpen }
            main {
                assertSelection(activity, local = false)
                assertFalse((field(activity, "playlistDrawer") as HomeDrawerLayout).isOpen)
            }
        }
        try {
            await { activity.settingsPlayer() != null && activity.hasWindowFocus() }
            clickMyLists(); assertOpened(favorites.id)
            main { (field(activity, "heading") as View).performClick() }
            await { activity.window.decorView.findViewWithTag<View>("playlist-choice-$customId")?.isShown == true }
            main { activity.window.decorView.findViewWithTag<View>("playlist-choice-$customId").performClick() }
            assertOpened(customId)
            assertEquals(customId.toString(), preferences.getString("list.lastOpenedId", null))
            main { activity.window.decorView.findViewWithTag<View>("navigation-1").performClick() }
            clickMyLists(); assertOpened(customId)
            main { activity.finish() }
            await { activity.isDestroyed }
            activity = launch()
            await { activity.settingsPlayer() != null && activity.hasWindowFocus() }
            clickMyLists(); assertOpened(customId)
            main { activity.window.decorView.findViewWithTag<View>("navigation-local").performClick() }
            lists.delete(customId)
            clickMyLists(); assertOpened(favorites.id)
            main {
                activity.window.decorView.findViewWithTag<View>("navigation-0").performClick()
                invoke(activity, "showSettings")
            }
            await { activity.settingsFragment()?.isResumed == true && (field(activity, "loadJob") as? Job)?.isActive != true }
            main { assertEquals(R.id.settings_page, activity.navigationController().currentDestination?.id) }
        } finally {
            main { activity.finish() }
            if (lists.list().any { it.id == customId }) lists.delete(customId)
            lists.close()
            preferences.edit().apply { if (previous == null) remove("list.lastOpenedId") else putString("list.lastOpenedId", previous) }.commit()
        }
    }
}
