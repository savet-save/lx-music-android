package cn.toside.music.nativeapp.ui

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupPageTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val preferences get() = instrumentation.targetContext.getSharedPreferences("app-settings", 0)
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun field(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun invoke(activity: MainActivity, method: String) = MainActivity::class.java.getDeclaredMethod(method).apply { isAccessible = true }.invoke(activity)
    private fun await(message: String, check: () -> Boolean) {
        repeat(200) {
            var ready = false; main { ready = check() }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail(message)
    }
    private fun start() = (ForegroundTestActivities.start(Intent(instrumentation.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity).also { instrumentation.waitForIdleSync() }
    private fun finish(activity: MainActivity) {
        main { activity.finish() }
        await("Activity did not finish") { activity.isDestroyed }
    }
    private fun choose(activity: MainActivity, page: String) = main {
        when (page) {
            "search" -> MainActivity::class.java.getDeclaredMethod("openSearchPage", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
            "local" -> MainActivity::class.java.getDeclaredMethod("openLocalPage", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
            "my-lists" -> invoke(activity, "openMyLists")
            "charts" -> invoke(activity, "showBoards")
            "playlists" -> invoke(activity, "showOnlinePlaylists")
        }
    }
    private fun assertPage(activity: MainActivity, page: String) {
        val destination = when (page) { "charts" -> R.id.charts_page; "playlists" -> R.id.playlist_browser; else -> R.id.music_page }
        val navigationItem = when (page) { "search" -> 1; "my-lists" -> 0; "charts" -> 2; "playlists" -> 3; else -> 5 }
        await("Expected $page after launch") {
            @Suppress("UNCHECKED_CAST") val items = field(activity, "navigationItems") as Map<Int, TextView>
            activity.navigationController().currentDestination?.id == destination && items[navigationItem]?.isSelected == true
        }
        main {
            if (page == "search") {
                assertTrue(field(activity, "onlineMode") as Boolean)
                assertTrue((field(activity, "search") as View).isShown)
            }
            if (page == "my-lists") assertNotNull(field(activity, "activePlaylist"))
            if (page == "local") { assertFalse(field(activity, "onlineMode") as Boolean); assertNull(field(activity, "activePlaylist")) }
        }
    }

    @Test fun firstLaunchAndUnrecognizedHistoryOpenSearch() {
        for (page in listOf(null, "unsupported-page")) {
            preferences.edit().apply { if (page == null) remove("home.lastPage") else putString("home.lastPage", page) }.commit()
            val activity = start()
            try { assertPage(activity, "search") } finally { finish(activity) }
        }
    }

    @Test fun everyHomePageSurvivesFreshActivityLaunchAndRecreation() {
        var activity = start()
        try {
            for (page in listOf("search", "playlists", "charts", "my-lists", "local")) {
                choose(activity, page); assertPage(activity, page)
                assertEquals(page, preferences.getString("home.lastPage", null))
                val previous = activity
                finish(previous); activity = start()
                assertNotSame(previous, activity); assertPage(activity, page)
                assertEquals(page, preferences.getString("home.lastPage", null))
                val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
                try {
                    main { activity.recreate() }
                    activity = requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10000)) as MainActivity
                } finally { instrumentation.removeMonitor(monitor) }
                assertPage(activity, page)
                assertEquals(page, preferences.getString("home.lastPage", null))
            }
        } finally { finish(activity) }
    }

    @Test fun settingsDownloadsAndPlayerDetailsKeepThePreviousHomePage() {
        var activity = start()
        try {
            choose(activity, "my-lists"); assertPage(activity, "my-lists")
            main { activity.openSettings() }
            await("Settings did not open") { activity.navigationController().currentDestination?.id == R.id.settings_page }
            assertEquals("my-lists", preferences.getString("home.lastPage", null))
            main { activity.closeSettings() }; assertPage(activity, "my-lists")
            main { invoke(activity, "showDownloads") }
            await("Downloads did not open") { activity.navigationController().currentDestination?.id == R.id.downloads_page }
            assertEquals("my-lists", preferences.getString("home.lastPage", null))
            main { activity.onBackPressedDispatcher.onBackPressed() }; assertPage(activity, "my-lists")
            await("Player service did not connect") { activity.settingsPlayer() != null }
            main { invoke(activity, "showPlayerDetails") }
            await("Player details did not open") { activity.playerFragment() != null }
            assertEquals("my-lists", preferences.getString("home.lastPage", null))
            finish(activity); activity = start()
            assertPage(activity, "my-lists")
        } finally { finish(activity) }
    }
}
