package cn.toside.music.nativeapp.ui

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.ListView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.AacTestAudio
import cn.toside.music.nativeapp.data.Downloads
import cn.toside.music.nativeapp.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BilibiliDownloadDialogTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(condition: () -> Boolean) {
        repeat(150) {
            instrumentation.waitForIdleSync()
            var ready = false
            main { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("Download UI did not become ready")
    }
    @Test fun cachedChoiceNeedsNoRemoteRequestAndOtherBitratesCanBeSelectedExplicitly() {
        val activity = ForegroundTestActivities.start(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var dialog: Dialog? = null
        var remoteCalls = 0
        var selected: String? = null
        try {
            await { activity.hasWindowFocus() }
            main {
                val palette = UiPalette(activity, NativeThemes(activity).active())
                dialog = showBilibiliDownloadQuality(activity, Song(1, Uri.EMPTY, "Fixture", "UP", 3000, "bili"), palette,
                    LxStyles(activity, palette, android.graphics.Typeface.createFromAsset(activity.assets, "fonts/icomoon.ttf")), scope,
                    cached = { listOf(BilibiliDownloadOption("original", "AAC / M4A · ≈192 kbps · Cache")) },
                    remote = { remoteCalls++; listOf(BilibiliDownloadOption("original", "AAC / M4A · 192 kbps"), BilibiliDownloadOption("aac-30216", "AAC / M4A · 64 kbps")) },
                    dismissed = {}) { selected = it }
            }
            val root = dialog!!.window!!.decorView
            await { root.findViewWithTag<View>("download-quality-original")?.isShown == true }
            assertEquals(0, remoteCalls); assertNull(selected)
            main { root.findViewWithTag<View>("bili-download-more").performClick() }
            await { root.findViewWithTag<View>("download-quality-aac-30216")?.isShown == true }
            assertEquals(1, remoteCalls); assertNull(selected)
            main {
                val preview = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(preview))
                java.io.File(context.getExternalFilesDir("aac-validation"), "bitrate-options.png").outputStream().use {
                    preview.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                preview.recycle()
                val button = root.findViewWithTag<Button>("download-quality-aac-30216")
                assertTrue(button.text.contains("64 kbps")); button.performClick()
            }
            assertEquals("aac-30216", selected); assertFalse(dialog!!.isShowing)
        } finally { main { dialog?.dismiss(); scope.cancel(); activity.finish() } }
    }

    @Test fun phonePlayerMoreStartsASeparateMp3ConversionAndKeepsThePlayingFile() = runBlocking {
        val file = AacTestAudio.create(context)
        val payload = file.readBytes()
        val song = Song(System.nanoTime(), Uri.fromFile(file), "Playback conversion fixture", "", 3000)
        val downloads = Downloads(context)
        val activity = ForegroundTestActivities.start(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            await { activity.hasWindowFocus() && activity.settingsPlayer() != null }
            main {
                val player = requireNotNull(activity.settingsPlayer())
                player.play(listOf(song), 0)
                MainActivity::class.java.getDeclaredMethod("showPlayerDetails").apply { isAccessible = true }.invoke(activity)
            }
            await { activity.window.decorView.findViewWithTag<View>("player-more")?.isShown == true }
            main { activity.window.decorView.findViewWithTag<View>("player-more").performClick() }
            fun menu(): ListView? {
                val popup = MainActivity::class.java.getDeclaredField("songActionsPopup").apply { isAccessible = true }.get(activity)
                val window = PlatformPopup::class.java.getDeclaredField("window").apply { isAccessible = true }.get(popup) as? android.widget.PopupWindow
                return (window?.contentView as? ListView)?.takeIf { window.isShowing && it.isShown }
            }
            await { menu() != null }
            main {
                val list = requireNotNull(menu())
                val position = (0 until list.count).single { list.adapter.getItem(it) == activity.getString(R.string.mp3_convert) }
                list.performItemClick(list.getChildAt(0), position, list.adapter.getItemId(position))
            }
            var key: String? = null
            repeat(150) {
                if (key == null) key = downloads.list().firstOrNull { it.song.id == song.id && it.isConversion }?.key
                if (key == null) kotlinx.coroutines.delay(50)
            }
            assertNotNull(key)
            downloads.convertMp3(requireNotNull(key))
            assertArrayEquals(payload, file.readBytes())
            main { assertEquals(song.id, activity.settingsPlayer()?.state?.value?.song?.id) }
        } finally {
            main { activity.settingsPlayer()?.clearQueue(); activity.finish() }
            downloads.list().filter { it.song.id == song.id }.forEach { downloads.remove(it) }
            file.delete()
        }
    }
}
