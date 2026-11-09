package cn.toside.music.nativeapp.ui

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
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

    private fun touch(view: View, down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(view.dispatchTouchEvent(event))
        } finally { event.recycle() }
    }

    private fun tap(view: View, x: Float = view.width / 2f, y: Float = view.height / 2f) {
        val down = SystemClock.uptimeMillis()
        touch(view, down, MotionEvent.ACTION_DOWN, x, y)
        touch(view, down, MotionEvent.ACTION_UP, x, y)
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
        val initialTouchMode = activity.window.decorView.isInTouchMode
        try {
            waitFor("activity focus and playback service") { activity.hasWindowFocus() && activity.settingsPlayer() != null }
            val player = requireNotNull(activity.settingsPlayer())
            val density = activity.resources.displayMetrics.density
            val resizeForPhoneLayout = activity.resources.configuration.screenWidthDp >= 600
            val previousSong = songs[0].copy(id = baseId - 1)
            instrumentation.setInTouchMode(true)
            main {
                if (resizeForPhoneLayout) {
                    field("uiLayout").set(activity, AdaptiveLayout(390, 740))
                    activity.window.setLayout((390 * density).toInt(), (740 * density).toInt())
                    invoke(activity, "applyThemeInPlace")
                }
                MainActivity::class.java.getDeclaredMethod("loadPlaylist", Playlist::class.java).apply { isAccessible = true }
                    .invoke(activity, playlist)
                player.play(listOf(previousSong), 0)
            }
            val list = field("list").get(activity) as ListView
            waitFor("playlist rows and previous playback") { list.childCount == songs.size && player.state.value.playing }
            main { (field("search").get(activity) as EditText).setText("Beta") }
            waitFor("filtered song") { list.count == 1 && (list.getChildAt(0)?.width ?: 0) > 0 }
            main {
                val row = list.getChildAt(0) as ViewGroup
                val anchor = row.getChildAt(row.childCount - 1)
                assertTrue(anchor.isShown && anchor.performClick())
            }
            fun popup() = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull {
                it.findViewWithTag<ListView>("song-actions-menu")?.takeIf(View::isShown)
            }
            waitFor("anchored song actions") { (popup()?.childCount ?: 0) > 0 }
            fun fullQueueIsPlaying() = player.state.value.song?.id == songs[1].id && player.state.value.playing &&
                player.queueState.value?.songs?.map { it.id } == orderedIds
            main {
                val menu = requireNotNull(popup())
                assertEquals(activity.getString(R.string.ui_c3396195e91c), menu.adapter.getItem(0))
                assertEquals(previousSong.id, player.state.value.song?.id)
                assertTrue(menu.performItemClick(menu.getChildAt(0), 0, menu.adapter.getItemId(0)))
            }
            waitFor("menu playback and return to the list") { popup() == null && activity.hasWindowFocus() && fullQueueIsPlaying() }
            main { player.play(listOf(previousSong), 0) }
            waitFor("previous playback to resume") { player.state.value.song?.id == previousSong.id && player.state.value.playing }
            main {
                val row = list.getChildAt(0)
                tap(list, row.left + row.width * .3f, row.top + row.height * .5f)
            }
            waitFor("filtered row to play with the full list", ::fullQueueIsPlaying)
            main {
                player.pause()
                player.seekTo(12000)
                invoke(activity, "showPlayerDetails")
                if (resizeForPhoneLayout) {
                    (field("playerDialog").get(activity) as Dialog).window?.setLayout((390 * density).toInt(), (740 * density).toInt())
                }
            }
            val cover = field("detailCover").get(activity) as View
            val lyrics = field("detailScroll").get(activity) as PlayerLyricScroll
            waitFor("cover and synced lyrics") { cover.hasWindowFocus() && cover.isShown && cover.width > 0 && lyrics.lines.childCount == 11 }
            main { tap(cover) }
            waitFor("lyrics centered at the playback position") { lyrics.isShown && lyrics.scrollY > 100 * density }

            val down = SystemClock.uptimeMillis()
            var target = -1
            main {
                val x = lyrics.width / 2f
                val start = lyrics.height * .3f
                val distance = lyrics.height * .42f
                touch(lyrics, down, MotionEvent.ACTION_DOWN, x, start)
                for (step in 1..8) touch(lyrics, down, MotionEvent.ACTION_MOVE, x, start + distance * step / 8)
                target = requireNotNull(lyrics.previewIndex)
                assertTrue(target in 0..5)
                assertEquals(12000, player.state.value.position)
                touch(lyrics, down, MotionEvent.ACTION_UP, x, start + distance)
            }
            waitFor("seek on release without leaving lyrics") { lyrics.isShown && player.state.value.position == target * 2000 }
            main { tap(lyrics) }
            waitFor("tap on lyrics to return to artwork") { cover.isShown && !lyrics.isShown }
            assertEquals(target * 2000, player.state.value.position)
        } finally {
            instrumentation.setInTouchMode(initialTouchMode)
            main { activity.settingsPlayer()?.clearQueue(); activity.finish() }
            context.stopService(Intent(context, PlaybackService::class.java))
            runBlocking { playlists.delete(playlist.id) }
            lyricFile.delete()
            wav.delete()
        }
    }
}
