package cn.toside.music.nativeapp.ui

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.ListView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.AppPreferences
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.karaoke.KaraokeAudio
import cn.toside.music.nativeapp.player.PlaybackService
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class PlayerDetailTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(message: String, condition: () -> Boolean) {
        repeat(150) {
            var done = false
            main { done = condition() }
            if (done) return
            SystemClock.sleep(40)
        }
        error(message)
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun playerMoreAnchorsToTheHeaderAndOpensSettingsAndSleepTimer() {
        val activity = ForegroundTestActivities.start(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val open = MainActivity::class.java.getDeclaredMethod("showPlayerDetails").apply { isAccessible = true }
        val timerText = activity.getString(R.string.ui_7afda7dc6745)
        val closeTimerText = activity.getString(R.string.ui_0446dbd36ecc)
        fun visibleText(text: String): TextView? {
            val matches = arrayListOf<View>()
            WindowInspector.getGlobalWindowViews().forEach { it.findViewsWithText(matches, text, View.FIND_VIEWS_WITH_TEXT) }
            return matches.filterIsInstance<TextView>().firstOrNull { it.isShown && it.text.toString() == text }
        }
        fun timerShown() = visibleText(timerText) != null
        fun menu() = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull {
            it.findViewWithTag<ListView>("song-actions-menu")?.takeIf(View::isShown)
        }
        fun selectAction(action: Int) {
            val more = activity.playerFragment()!!.requireView().findViewWithTag<View>("player-more")
            main { assertTrue(more.performClick()) }
            await("Player menu was not shown") { menu() != null }
            main {
                val list = requireNotNull(menu())
                val anchorPosition = IntArray(2).also(more::getLocationOnScreen)
                val menuPosition = IntArray(2).also(list::getLocationOnScreen)
                assertTrue(menuPosition[1] >= anchorPosition[1] + more.height)
                val row = requireNotNull(list.findViewWithTag<View>("song-actions-$action"))
                val position = list.getPositionForView(row)
                assertTrue(list.performItemClick(row, position, list.adapter.getItemId(position)))
            }
        }
        try {
            await("Playback service did not bind") { activity.settingsPlayer() != null }
            main {
                requireNotNull(activity.settingsPlayer()).clearQueue()
                open.invoke(activity)
            }
            await("Player header was not shown") { activity.playerFragment()?.view?.let { it.isShown && it.alpha == 1f } == true }
            main {
                val player = activity.playerFragment()!!.requireView()
                player.findViewWithTag<View>("player-artwork").performLongClick()
                assertNull(menu())
                player.findViewWithTag<View>("player-queue").performLongClick()
                assertFalse(timerShown())
            }
            selectAction(R.string.ui_7afda7dc6745)
            await("Sleep timer dialog did not open") { timerShown() && menu() == null && visibleText(closeTimerText) != null }
            main {
                val row = requireNotNull(visibleText(closeTimerText))
                val options = generateSequence(row.parent) { it.parent }.filterIsInstance<ListView>().first()
                val position = options.getPositionForView(row)
                assertTrue(options.performItemClick(row, position, options.adapter.getItemId(position)))
            }
            await("Sleep timer dialog did not close") { !timerShown() }
            selectAction(R.string.ui_8c38525734c0)
            await("Playback settings did not open") { activity.settingsFragment()?.savePageState()?.getString("category") == "playback" }
            main { activity.closeSettings() }
            await("Playback page was not restored from settings") { activity.playerFragment()?.view?.isShown == true }
        } finally { main { activity.finish() } }
    }

    @Test fun recordAndHaloFollowPlaybackWithEitherEngine() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        val context = instrumentation.targetContext
        val preferences = AppPreferences(context)
        val oldOffload = preferences.audioOffload
        val permission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        val wave = File(context.cacheDir, "record-motion-${System.nanoTime()}.wav")
        KaraokeAudio.WavWriter(wave).use { writer ->
            val oneSecond = 44_100 * 2
            val tone = ShortArray(oneSecond) { (12_000 * sin(it / 2 * 2.0 * Math.PI * 440 / 44_100)).toInt().toShort() }
            repeat(2) { writer.write(ShortArray(oneSecond), oneSecond) }
            repeat(6) { writer.write(tone, oneSecond) }
        }
        val activity = ForegroundTestActivities.start(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val serviceField = MainActivity::class.java.getDeclaredField("service").apply { isAccessible = true }
        val open = MainActivity::class.java.getDeclaredMethod("showPlayerDetails").apply { isAccessible = true }
        fun haloAlpha(record: View): Int {
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.translate(-record.width / 2f, -record.height * .045f)
                record.draw(canvas)
                return Color.alpha(bitmap.getPixel(0, 0))
            } finally { bitmap.recycle() }
        }
        try {
            await("Playback service did not bind") { serviceField.get(activity) != null }
            val service = serviceField.get(activity) as PlaybackService
            main { open.invoke(activity) }
            val content = activity.playerFragment()!!.requireView() as PlayerDetailContent
            val record = content.cover.getChildAt(0)
            val disc = content.findViewWithTag<View>("player-disc")
            await("Record has no size") { record.width > 0 }
            var idleGlow = 0
            main { idleGlow = haloAlpha(record) }
            for (offload in listOf(false, true)) {
                val song = Song(if (offload) 2 else 1, Uri.fromFile(wave), "Record motion", "", 8000)
                main {
                    preferences.set("player.isEnableAudioOffload", offload)
                    service.play(listOf(song), 0)
                }
                await("The disc did not rotate during playback") { service.state.value.playing && disc.rotation > 1f }
                main { assertEquals(idleGlow, haloAlpha(record)) }
                await("The halo did not respond to decoded audio (Media3: $offload)") {
                    service.playbackLevel() > .10f && haloAlpha(record) > idleGlow + 2
                }
                var pausedAt = 0f
                main { service.pause() }
                await("The paused halo did not settle") { haloAlpha(record) == idleGlow }
                main { pausedAt = disc.rotation }
                SystemClock.sleep(230)
                main {
                    assertEquals(pausedAt, disc.rotation, .01f)
                    service.resume()
                }
                await("The disc did not resume its rotation") { disc.rotation > pausedAt + 1f }
                if (!offload) main { service.pause() }
            }
            main { activity.closePlayerDetails() }
            await("Closing the player did not stop visualization while keeping audio playing") {
                activity.playerFragment() == null && service.state.value.playing && service.playbackLevel() == 0f
            }
            assertEquals(permission, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        } finally {
            main {
                (serviceField.get(activity) as? PlaybackService)?.pause()
                preferences.set("player.isEnableAudioOffload", oldOffload)
                activity.finish()
            }
            wave.delete()
        }
    }

    @Test fun opensAndClosesPlayerDetails() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val serviceField = MainActivity::class.java.getDeclaredField("service").apply { isAccessible = true }
            repeat(50) {
                if (serviceField.get(activity) != null) return@repeat
                SystemClock.sleep(100)
            }
            assertNotNull(serviceField.get(activity))
            val cover = find(activity.window.decorView, "播放详情")
            assertNotNull(cover)
            instrumentation.runOnMainSync { cover!!.performClick() }
            instrumentation.waitForIdleSync()
            val fragment = activity.playerFragment()
            assertTrue(fragment?.isAdded == true)
            assertTrue(fragment!!.requireView().isShown)
            assertEquals(activity.window.decorView.windowToken, fragment.requireView().windowToken)
            val toggleField = MainActivity::class.java.getDeclaredField("detailPageToggle").apply { isAccessible = true }
            val toggle = toggleField.get(activity) as View
            instrumentation.runOnMainSync { toggle.performClick() }
            val scrollField = MainActivity::class.java.getDeclaredField("detailScroll").apply { isAccessible = true }
            val split = activity.resources.configuration.let { it.screenWidthDp >= 600 && it.screenHeightDp >= 480 }
            assertEquals(if (split) View.GONE else View.VISIBLE, (scrollField.get(activity) as View).visibility)
            if (split) instrumentation.runOnMainSync { toggle.performClick() }
            instrumentation.runOnMainSync { activity.closePlayerDetails() }
            val openSync = MainActivity::class.java.getDeclaredMethod("showSyncSettings").apply { isAccessible = true }
            instrumentation.runOnMainSync { openSync.invoke(activity) }
            instrumentation.waitForIdleSync()
            val syncPage = activity.settingsFragment()!!.requireView()
            assertTrue(syncPage.isShown)
            assertEquals("连接", syncPage.findViewWithTag<TextView>("sync-connect").text.toString())
            instrumentation.runOnMainSync { activity.closeSettings() }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    private fun find(view: View, description: String): View? {
        if (view.contentDescription == description) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            find(view.getChildAt(index), description)?.let { return it }
        }
        return null
    }
}
