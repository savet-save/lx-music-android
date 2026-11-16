package cn.toside.music.nativeapp.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Insets
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.activity.BackEventCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.R
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerFragmentTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun invoke(activity: MainActivity, name: String) =
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun waitFor(label: String, condition: () -> Boolean) {
        repeat(100) {
            instrumentation.waitForIdleSync()
            var ready = false
            main { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for $label")
    }
    private fun withPlayer(block: (MainActivity) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            waitFor("service") { activity.settingsPlayer() != null }
            block(activity)
        } finally { main { activity.finish() } }
    }

    @Test fun backClosesOnlyThePlayerAndLeavesTheBrowsingDestinationAndService() = withPlayer { activity ->
        val service = activity.settingsPlayer()
        main {
            activity.navigationController().navigate(R.id.downloads_page)
            activity.supportFragmentManager.executePendingTransactions()
        }
        waitFor("downloads") { activity.navigationController().currentDestination?.id == R.id.downloads_page }
        main {
            invoke(activity, "showPlayerDetails")
            invoke(activity, "showPlayerDetails")
            assertEquals(1, activity.supportFragmentManager.backStackEntryCount)
            assertSame(activity.playerFragment(), activity.supportFragmentManager.primaryNavigationFragment)
        }
        waitFor("player") { activity.playerFragment()?.view?.isShown == true }
        main { activity.onBackPressedDispatcher.onBackPressed() }
        waitFor("player close") { activity.playerFragment() == null }
        assertFalse(activity.isFinishing)
        assertEquals(R.id.downloads_page, activity.navigationController().currentDestination?.id)
        assertSame(service, activity.settingsPlayer())
    }

    @Test fun settingsReturnToTheSameLyricsPage() = withPlayer { activity ->
        var lyrics = false
        main {
            invoke(activity, "showPlayerDetails")
            invoke(activity, "toggleDetailPage")
            lyrics = activity.playerLyricsVisible()
            invoke(activity, "showPlaybackSettings")
        }
        waitFor("settings") { activity.settingsFragment()?.view?.isShown == true }
        main { activity.closeSettings() }
        waitFor("player restored") { activity.playerFragment()?.view?.isShown == true }
        assertEquals(lyrics, activity.playerLyricsVisible())
    }

    @Test fun playerSharesTheActivityWindowAndReservesSystemInsetsExactlyOnce() = withPlayer { activity ->
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        main {
            invoke(activity, "showPlayerDetails")
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
            val content = activity.playerFragment()!!.requireView()
            assertEquals(activity.window.decorView.windowToken, content.windowToken)
            assertEquals(Color.TRANSPARENT, activity.window.statusBarColor)
            assertFalse(activity.window.isStatusBarContrastEnforced)
            val insets = WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 83, 0, 57))
                .setInsets(WindowInsets.Type.displayCutout(), Insets.of(19, 90, 0, 0)).build()
            repeat(2) { root.dispatchApplyWindowInsets(insets) }
            assertEquals(90, root.paddingTop); assertEquals(57, root.paddingBottom)
            assertEquals(19, root.paddingLeft)
            repeat(2) {
                root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            }
            val rootPosition = IntArray(2); val contentPosition = IntArray(2)
            root.getLocationOnScreen(rootPosition); content.getLocationOnScreen(contentPosition)
            assertEquals(90, contentPosition[1] - rootPosition[1])
            assertEquals(19, contentPosition[0] - rootPosition[0])
            assertEquals(root.height - 90 - 57, content.height)
            assertEquals(0, content.paddingTop)
            root.requestApplyInsets()
        }
    }

    @Test fun predictiveBackCancellationKeepsThePlayerAndCommitRevealsThePreviousPage() = withPlayer { activity ->
        assumeTrue("Predictive fragment animation requires Android 14+", Build.VERSION.SDK_INT >= 34)
        val previousScale = android.provider.Settings.Global.getString(instrumentation.targetContext.contentResolver, "animator_duration_scale")
        fun setScale(value: String?) {
            val command = if (value == null) "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $value"
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        // CI disables animations globally; this test explicitly exercises a seekable Animator.
        setScale("1")
        try {
            waitFor("enabled Animator duration") { android.animation.ValueAnimator.areAnimatorsEnabled() }
            main { invoke(activity, "showPlayerDetails") }
            waitFor("player animation") { activity.playerFragment()?.view?.alpha == 1f }
            val fragment = activity.playerFragment()!!
            val service = activity.settingsPlayer()
            for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
                main { activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, edge)) }
                instrumentation.waitForIdleSync()
                main { activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(150f, 300f, .65f, edge)) }
                SystemClock.sleep(80)
                main {
                    assertSame(fragment, activity.playerFragment())
                    assertTrue("Animation follows the gesture", fragment.requireView().alpha < 1f)
                    activity.onBackPressedDispatcher.dispatchOnBackCancelled()
                }
                waitFor("cancelled gesture") { fragment.isAdded && fragment.requireView().alpha == 1f }
            }
            main { activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT)) }
            instrumentation.waitForIdleSync()
            main {
                activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 300f, .8f, BackEventCompat.EDGE_LEFT))
                activity.onBackPressedDispatcher.onBackPressed()
            }
            waitFor("committed gesture") { activity.playerFragment() == null }
            assertFalse(activity.isFinishing)
            assertSame(service, activity.settingsPlayer())
        } finally { setScale(previousScale) }
    }
}
