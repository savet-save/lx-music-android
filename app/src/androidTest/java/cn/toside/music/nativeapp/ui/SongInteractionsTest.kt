package cn.toside.music.nativeapp.ui

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.ListView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.Playlist
import cn.toside.music.nativeapp.data.Playlists
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.player.PlaybackService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class SongInteractionsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun invoke(activity: MainActivity, name: String) =
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)

    private fun waitFor(label: String, condition: () -> Boolean) {
        repeat(120) {
            instrumentation.waitForIdleSync()
            var met = false
            main { met = condition() }
            if (met) return
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for $label")
    }

    private fun point(view: View, x: Float, y: Float): Pair<Float, Float> {
        var result = 0f to 0f
        main {
            val origin = IntArray(2).also(view::getLocationOnScreen)
            result = origin[0] + view.width * x to origin[1] + view.height * y
        }
        return result
    }

    private fun touch(down: Long, action: Int, point: Pair<Float, Float>) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.first, point.second, 0)
        try {
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
        } finally { event.recycle() }
    }

    private fun tap(view: View, x: Float = .5f, y: Float = .5f) {
        val point = point(view, x, y)
        val down = SystemClock.uptimeMillis()
        touch(down, MotionEvent.ACTION_DOWN, point)
        touch(down, MotionEvent.ACTION_UP, point)
    }

    @Test fun songTapReplacesQueueAndLyricsDistinguishTappingFromScrubbing() {
        val context = instrumentation.targetContext
        val wav = File.createTempFile("song-interactions", ".wav", context.cacheDir)
        wav.writeBytes(ByteBuffer.allocate(44 + 8000 * 2 * 24).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(capacity() - 8).put("WAVEfmt ".toByteArray()).putInt(16)
            putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            put("data".toByteArray()).putInt(capacity() - 44)
        }.array())
        val baseId = SystemClock.elapsedRealtime() * 10
        val songs = listOf("Alpha", "Beta", "Gamma").mapIndexed { index, title ->
            Song(baseId + index, Uri.fromFile(wav), title, "Fixture", 24000)
        }
        val lyricFile = File(context.filesDir, "lyrics/${songs[1].id}.lrc").apply {
            parentFile?.mkdirs()
            writeText((0..10).joinToString("\n") { "[00:${(it * 2).toString().padStart(2, '0')}.00]Line $it" })
        }
        val playlists = Playlists(context)
        val playlist = runBlocking {
            val name = "Interaction fixture $baseId"
            Playlist(playlists.create(name), name).also { list -> songs.forEach { playlists.add(list.id, it) } }
        }
        val orderedIds = runBlocking { playlists.songs(playlist.id).map { it.id } }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            waitFor("activity focus and playback service") { activity.hasWindowFocus() && activity.settingsPlayer() != null }
            val player = requireNotNull(activity.settingsPlayer())
            val density = activity.resources.displayMetrics.density
            main {
                field("uiLayout").set(activity, AdaptiveLayout(390, 740))
                activity.window.setLayout((390 * density).toInt(), (740 * density).toInt())
                invoke(activity, "applyThemeInPlace")
                MainActivity::class.java.getDeclaredMethod("loadPlaylist", Playlist::class.java).apply { isAccessible = true }
                    .invoke(activity, playlist)
                player.play(listOf(songs[0].copy(id = baseId - 1)), 0)
            }
            val list = field("list").get(activity) as ListView
            waitFor("playlist rows") { list.childCount == songs.size }
            main { (field("search").get(activity) as EditText).setText("Beta") }
            waitFor("filtered song") { list.count == 1 && (list.getChildAt(0)?.width ?: 0) > 0 }
            val row = list.getChildAt(0) as ViewGroup
            tap(row.getChildAt(row.childCount - 1))
            fun popup() = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull {
                it.findViewWithTag<ListView>("song-actions-menu")?.takeIf(View::isShown)
            }
            waitFor("anchored song actions") { popup() != null }
            main {
                val menu = requireNotNull(popup())
                assertEquals(activity.getString(R.string.ui_c3396195e91c), menu.adapter.getItem(0))
                assertEquals(baseId - 1, player.state.value.song?.id)
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor("menu to close") { popup() == null }
            tap(list.getChildAt(0), .3f)
            waitFor("filtered row to play with the full list") {
                player.state.value.song?.id == songs[1].id && player.state.value.playing &&
                    player.queueState.value?.songs?.map { it.id } == orderedIds
            }
            main {
                player.pause()
                player.seekTo(12000)
                invoke(activity, "showPlayerDetails")
                (field("playerDialog").get(activity) as Dialog).window?.setLayout((390 * density).toInt(), (740 * density).toInt())
            }
            val cover = field("detailCover").get(activity) as View
            val lyrics = field("detailScroll").get(activity) as PlayerLyricScroll
            waitFor("cover and synced lyrics") { cover.isShown && cover.width > 0 && lyrics.lines.childCount == 11 }
            tap(cover)
            waitFor("lyrics centered at the playback position") { lyrics.isShown && lyrics.scrollY > 100 * density }

            val start = point(lyrics, .5f, .3f)
            val finish = point(lyrics, .5f, .72f)
            val down = SystemClock.uptimeMillis()
            touch(down, MotionEvent.ACTION_DOWN, start)
            for (step in 1..8) touch(down, MotionEvent.ACTION_MOVE, start.first to start.second + (finish.second - start.second) * step / 8)
            var target = -1
            main {
                target = requireNotNull(lyrics.previewIndex)
                assertTrue(target in 0..5)
                assertEquals(12000, player.state.value.position)
            }
            touch(down, MotionEvent.ACTION_UP, finish)
            waitFor("seek on release without leaving lyrics") { lyrics.isShown && player.state.value.position == target * 2000 }
            tap(lyrics)
            waitFor("tap on lyrics to return to artwork") { cover.isShown && !lyrics.isShown }
            assertEquals(target * 2000, player.state.value.position)
        } finally {
            main { activity.settingsPlayer()?.clearQueue(); activity.finish() }
            context.stopService(Intent(context, PlaybackService::class.java))
            runBlocking { playlists.delete(playlist.id) }
            lyricFile.delete()
            wav.delete()
        }
    }
}
