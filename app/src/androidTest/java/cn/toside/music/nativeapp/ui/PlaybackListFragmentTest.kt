package cn.toside.music.nativeapp.ui

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.ListView
import android.widget.TextView
import androidx.activity.BackEventCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.AppPreferences
import cn.toside.music.nativeapp.data.RecentSongs
import cn.toside.music.nativeapp.data.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackListFragmentTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun open(activity: MainActivity, method: String) {
        MainActivity::class.java.getDeclaredMethod(method).apply { isAccessible = true }.invoke(activity)
    }

    private fun await(description: String, condition: () -> Boolean) {
        repeat(150) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            SystemClock.sleep(40)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private fun withActivity(block: (MainActivity) -> Unit) {
        val activity = ForegroundTestActivities.start(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            await("playback service") { activity.playbackService.value != null }
            block(activity)
        } finally {
            main { activity.finish() }
        }
    }

    private fun page(activity: MainActivity) =
        (activity.supportFragmentManager.primaryNavigationFragment as? PlaybackListFragment)?.view

    private fun list(activity: MainActivity) = page(activity)?.findViewWithTag<ListView>("playback-list-songs")

    private fun songs() = (1L..3).map { Song(it, Uri.EMPTY, "Track $it", "Artist", 1000) }

    @Test fun openingDuringBackPreviewDoesNotDuplicatePlaybackPages() = withActivity { activity ->
        val manager = activity.supportFragmentManager
        for (method in listOf("showHistory", "showPlaybackQueue", "showPlayerDetails")) {
            val previous = manager.primaryNavigationFragment
            main {
                open(activity, method)
                open(activity, method)
            }
            await("$method opened") { manager.primaryNavigationFragment?.view?.isShown == true }
            val page = manager.primaryNavigationFragment
            assertEquals(method, 1, manager.backStackEntryCount)
            main { activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT)) }
            await("$method back preview") { manager.primaryNavigationFragment === previous }
            main {
                open(activity, method)
                activity.onBackPressedDispatcher.dispatchOnBackCancelled()
            }
            await("$method restored after back cancellation") { manager.primaryNavigationFragment !== previous }
            assertEquals(method, 1, manager.backStackEntryCount)
            assertSame(method, page, manager.primaryNavigationFragment)
            main { activity.onBackPressedDispatcher.onBackPressed() }
            await("$method removed by one back") { manager.backStackEntryCount == 0 && manager.primaryNavigationFragment === previous }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun queueEditsStayOnThePageAndBackReturnsToThePlayer() = withActivity { activity ->
        val service = requireNotNull(activity.playbackService.value)
        val songs = songs()
        try {
            main {
                service.clearQueue()
                service.enqueue(songs)
                open(activity, "showPlayerDetails")
            }
            await("player queue button") { activity.playerFragment()?.view?.findViewWithTag<View>("player-queue")?.isShown == true }
            main { assertTrue(activity.playerFragment()!!.requireView().findViewWithTag<View>("player-queue").performClick()) }
            await("queue rows") { list(activity)?.childCount == songs.size }
            val queuePage = page(activity)
            main {
                assertEquals(activity.window.decorView.windowToken, queuePage?.windowToken)
                assertTrue(list(activity)!!.getChildAt(0).isActivated)
            }
            chooseQueueAction(activity, "down")
            await("reordered queue") { list(activity)?.getChildAt(1)?.isActivated == true }
            assertEquals(listOf(songs[1], songs[0], songs[2]), service.queueState.value?.songs)
            chooseQueueAction(activity, "remove")
            await("removed row") { list(activity)?.childCount == 2 }
            assertEquals(listOf(songs[0], songs[2]), service.queueState.value?.songs)
            main {
                assertSame(queuePage, page(activity))
                assertTrue(queuePage!!.findViewWithTag<View>("playback-list-clear").performClick())
            }
            await("empty queue") { queuePage!!.findViewWithTag<View>("playback-list-empty").isShown }
            main { activity.onBackPressedDispatcher.onBackPressed() }
            await("player restored") { page(activity) == null && activity.playerFragment()?.view?.isShown == true }
        } finally {
            main { service.clearQueue() }
        }
    }

    private fun chooseQueueAction(activity: MainActivity, action: String) {
        main { assertTrue(list(activity)!!.getChildAt(0).findViewWithTag<View>("playback-queue-row-menu").performClick()) }
        await("$action in queue menu") {
            WindowInspector.getGlobalWindowViews().any { it.findViewWithTag<View>("queue-actions-$action")?.isShown == true }
        }
        main {
            val popup = WindowInspector.getGlobalWindowViews().firstNotNullOf { it.findViewWithTag<ListView>("queue-actions-menu") }
            val row = popup.findViewWithTag<View>("queue-actions-$action")
            val position = popup.getPositionForView(row)
            assertTrue(popup.performItemClick(row, position, popup.adapter.getItemId(position)))
        }
    }

    @Test fun historyUsesTheActiveThemeAndRefreshesAfterClear() {
        val context = instrumentation.targetContext
        val preferences = AppPreferences(context)
        val savedTheme = preferences.string("theme.id", "green")
        val savedAutoTheme = preferences.bool("common.isAutoTheme")
        val history = RecentSongs(context)
        val savedHistory = runBlocking { history.list() }
        val song = songs().first()
        try {
            preferences.set("common.isAutoTheme", false)
            preferences.set("theme.id", "black")
            runBlocking {
                assertTrue(history.clear())
                history.record(song)
            }
            withActivity { activity ->
                val destination = activity.navigationController().currentDestination?.id
                main { open(activity, "showHistory") }
                await("history row") { list(activity)?.childCount == 1 }
                main {
                    val page = requireNotNull(page(activity))
                    val palette = UiPalette(activity, NativeThemes(activity).active())
                    assertTrue(palette.theme.dark)
                    assertEquals(palette.surface, (page.background as ColorDrawable).color)
                    assertEquals(palette.primary, page.findViewWithTag<TextView>("page-back").currentTextColor)
                    assertTrue(page.findViewWithTag<View>("playback-list-clear").performClick())
                }
                await("empty history") { page(activity)?.findViewWithTag<View>("playback-list-empty")?.isShown == true }
                assertTrue(runBlocking { history.list().isEmpty() })
                main { assertTrue(page(activity)!!.findViewWithTag<View>("page-back").performClick()) }
                await("home destination") { page(activity) == null && activity.navigationController().currentDestination?.id == destination }
            }
        } finally {
            preferences.set("theme.id", savedTheme)
            preferences.set("common.isAutoTheme", savedAutoTheme)
            runBlocking {
                history.clear()
                savedHistory.asReversed().forEach { history.record(it) }
            }
        }
    }
}
