@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package cn.toside.music.nativeapp.ui

import android.app.Dialog
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.core.graphics.ColorUtils.blendARGB
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.DownloadTestScheduler
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.DownloadStatus
import cn.toside.music.nativeapp.data.Downloads
import cn.toside.music.nativeapp.data.DownloadLyricStatus
import cn.toside.music.nativeapp.lyrics.Lyrics
import cn.toside.music.nativeapp.lyrics.LyricLine
import android.app.job.JobScheduler
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.player.PlaybackService
import cn.toside.music.nativeapp.utils.PermissionPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class DownloadsPageTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private suspend fun waitFor(label: String, check: suspend () -> Boolean) {
        assertTrue("Timed out waiting for $label", withTimeoutOrNull(25_000) { while (!check()) delay(80); true } == true)
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    private fun snapshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("downloadScreenshots") != "true") return
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val folder = File(context.getExternalFilesDir(null), "downloads-validation").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun picksThemedQualityAndTracksRetriesProgressAndPlayback() = runBlocking {
        PermissionPolicy.downloadWrite(Build.VERSION.SDK_INT)?.let { permission ->
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        val preferences = context.getSharedPreferences("app-settings", 0)
        val keys = listOf("theme.id", "common.isAutoTheme", "player.autoSwitchSource")
        val saved = keys.associateWith { preferences.all[it] }
        preferences.edit().putString("theme.id", "blue").putBoolean("common.isAutoTheme", false).putBoolean("player.autoSwitchSource", false).commit()
        val catalog = AtomicFile(File(context.filesDir, "user-sources.json"))
        val previous = if (catalog.baseFile.isFile) catalog.readFully() else null
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val wav = ByteBuffer.allocate(44 + 16000 * 2 * 8).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(capacity() - 8).put("WAVEfmt ".toByteArray()).putInt(16)
            putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            put("data".toByteArray()).putInt(capacity() - 44)
        }.array()
        val worker = thread(isDaemon = true) {
            try { server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${wav.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(wav)
                }
            } } catch (_: java.io.IOException) { }
        }
        val song = Song(-130, Uri.EMPTY, "下载测试歌曲", "下载测试歌手", 8000, "kg",
            """{"songmid":"130","_types":{"128k":{"bytes":3932160},"320k":{}}}""")
        val downloads = Downloads(context)
        Lyrics(context).cache(song, listOf(LyricLine(0, "download lyric fixture")))
        fun source(name: String, body: String) = """/** @name $name */
            lx.on(lx.EVENT_NAMES.request, ({info}) => { $body });
            lx.send(lx.EVENT_NAMES.inited, {sources:{kg:{type:'music',actions:['musicUrl'],qualitys:['128k','320k']}}});
        """.trimIndent()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val root = activity.window.decorView
        fun view(tag: String) = root.findViewWithTag<View>(tag)
        try {
            waitFor("playback service and Activity focus") { activity.settingsPlayer() != null && activity.hasWindowFocus() }
            val player = requireNotNull(activity.settingsPlayer())
            player.importSource(source("Download failure fixture", "return Promise.reject(new Error('temporary failure'))"))
            main {
                val bar = MainActivity::class.java.getDeclaredField("miniBar").apply { isAccessible = true }.get(activity) as ViewGroup
                activity.openChartSongActions(bar.getChildAt(0), listOf(song), 0)
            }
            val downloadLabel = activity.getString(R.string.ui_9be60bb7ddeb)
            waitFor("song menu download action") {
                var clicked = false
                main {
                    val menu = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants).filterIsInstance<ListView>()
                        .firstOrNull { list -> list.tag == "song-actions-menu" && list.isShown }
                        ?: return@main
                    val position = (0 until menu.count).first { menu.adapter.getItem(it).toString() == downloadLabel }
                    val row = menu.getChildAt(position - menu.firstVisiblePosition)
                    if (row == null) menu.setSelection(position)
                    else clicked = menu.performItemClick(row, position, menu.adapter.getItemId(position))
                }
                clicked
            }
            val dialogField = MainActivity::class.java.getDeclaredField("downloadQualityDialog").apply { isAccessible = true }
            waitFor("quality chooser") { (dialogField.get(activity) as? Dialog)?.isShowing == true }
            val dialog = dialogField.get(activity) as Dialog
            waitFor("failed availability probes") {
                var finished = false
                main { finished = listOf("128k", "320k").all { dialog.window!!.decorView.findViewWithTag<TextView>("download-quality-result-$it").text == "×" } }
                finished
            }
            assertTrue(downloads.list().none { it.song == song })
            main {
                val dialogRoot = dialog.window!!.decorView
                val header = dialogRoot.findViewWithTag<View>("download-quality-accent")
                val theme = UiPalette(activity, NativeThemes(activity).active())
                assertEquals(blendARGB(theme.surface, theme.primary, .70f), (header.background as ColorDrawable).color)
                assertTrue(dialogRoot.findViewWithTag<TextView>("download-quality-128k").text.toString().contains("3.75 MiB"))
            }
            instrumentation.waitForIdleSync(); snapshot("quality-blue")
            main { dialog.window!!.decorView.findViewWithTag<View>("download-quality-320k").performClick() }
            waitFor("the failed 320k link task") { downloads.list().any { it.song == song && it.status == DownloadStatus.LINK_FAILED } }
            val failed = downloads.list().single { it.song == song }
            assertEquals("320k", failed.quality)
            main { view("navigation-4").performClick() }
            waitFor("the failed task row") { view("downloads-task-${failed.key}")?.isShown == true }
            val table = view("downloads-table-header")
            assertEquals(activity.downloadTableLayout(), table != null)
            main {
                view("downloads-filter-FAILED").performClick()
                assertTrue(view("downloads-filter-FAILED").isSelected)
                (view("downloads-search") as EditText).setText("no matching track")
                assertEquals(0, (view("downloads-list") as ListView).count)
                (view("downloads-search") as EditText).setText("下载测试歌手")
                assertEquals(1, (view("downloads-list") as ListView).count)
                (view("downloads-search") as EditText).text.clear()
            }
            if (InstrumentationRegistry.getArguments().getString("downloadScreenshots") == "true") delay(2200)
            instrumentation.waitForIdleSync(); snapshot("downloads-blue")
            player.importSource(source("Download success fixture", "if (info.type !== '320k') throw new Error('wrong quality'); return Promise.resolve('http://127.0.0.1:${server.localPort}/track.wav')"))
            main { view("downloads-task-${failed.key}").findViewWithTag<View>("downloads-row-primary").performClick() }
            waitFor("retry start") { downloads.list().any { it.key == failed.key && it.status != DownloadStatus.LINK_FAILED } }
            var scheduler: DownloadTestScheduler? = null
            waitFor("loopback transfer completion") {
                val task = downloads.list().single { it.song == song }
                if (task.systemId != null && scheduler == null) scheduler = DownloadTestScheduler(task.systemId)
                if (task.status == DownloadStatus.QUEUED) scheduler?.onStatus(DownloadManager.STATUS_PENDING)
                check(!task.failed) { "Retry ended in ${task.status}, reason ${task.reason}, scheduler ${scheduler?.diagnostics}" }
                task.status == DownloadStatus.COMPLETE
            }
            val completed = downloads.list().single { it.song == song }
            waitFor("automatic lyrics after the system download completes") {
                val task = downloads.list().single { it.song == song }
                context.getSystemService(JobScheduler::class.java).allPendingJobs.firstOrNull {
                    it.service.className.endsWith("DownloadLyricsJob")
                }?.let { job ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                        "cmd jobscheduler run -f ${context.packageName} ${job.id}"
                    )).use { it.readBytes() }
                }
                task.lyricStatus == DownloadLyricStatus.SAVED
            }
            assertEquals(failed.key, completed.key)
            assertEquals(100, completed.percent)
            // Playing the original online entry must select the completed system download first.
            main { player.play(listOf(song), 0) }
            waitFor("online entry uses its completed download") { player.state.value.playing }
            main {
                assertEquals(song, player.state.value.song)
                val current = PlaybackService::class.java.getDeclaredField("nativePlayer").apply { isAccessible = true }.get(player)
                    as cn.toside.music.nativeapp.player.NativeAudioPlayer
                val item = current.engine.currentMediaItem!!.localConfiguration!!
                assertEquals(context.getSystemService(DownloadManager::class.java).getUriForDownloadedFile(completed.systemId!!), item.uri)
                assertNull(item.customCacheKey)
            }
            main { view("downloads-filter-COMPLETE").performClick() }
            waitFor("the completed task row") { view("downloads-task-${failed.key}")?.isShown == true }
            main { view("downloads-task-${failed.key}").findViewWithTag<View>("downloads-row-primary").performClick() }
            waitFor("downloaded WAV playback") { player.state.value.playing && player.state.value.song?.id == -requireNotNull(completed.systemId) }

            main { activity.navigationController().popBackStack() }
            waitFor("the wide download page to detach") { view("downloads-page")?.isShown != true }
            main {
                val density = activity.resources.displayMetrics.density
                MainActivity::class.java.getDeclaredField("uiLayout").apply { isAccessible = true }.set(activity, AdaptiveLayout(360, 800))
                activity.window.setLayout((360 * density).toInt(), (800 * density).toInt())
                MainActivity::class.java.getDeclaredMethod("applyThemeInPlace").apply { isAccessible = true }.invoke(activity)
            }
            instrumentation.waitForIdleSync()
            main { view("navigation-4").performClick() }
            waitFor("the compact download row") { view("downloads-task-${failed.key}")?.isShown == true }
            assertNull(view("downloads-table-header"))
            instrumentation.waitForIdleSync(); snapshot("downloads-compact")
            main { view("downloads-task-${failed.key}").findViewWithTag<View>("downloads-row-remove").performClick() }
            waitFor("remove confirmation") {
                var clicked = false
                main {
                    clicked = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull {
                        it.findViewById<View>(android.R.id.button1)?.takeIf { button -> button.isShown }
                    }?.performClick() == true
                }
                clicked
            }
            waitFor("download removal") { downloads.list().none { it.key == failed.key } }
        } finally {
            main { activity.settingsPlayer()?.clearQueue(); activity.finish() }
            context.stopService(Intent(context, PlaybackService::class.java))
            downloads.list().filter { it.song == song }.forEach { downloads.remove(it) }
            Lyrics(context).clearOnlineCache(song)
            catalog.delete(); if (previous != null) { val stream = catalog.startWrite(); stream.write(previous); catalog.finishWrite(stream) }
            preferences.edit().apply { saved.forEach { (key, value) -> when (value) {
                is Boolean -> putBoolean(key, value); is String -> putString(key, value); else -> remove(key)
            } } }.commit()
            server.close(); worker.join(2000)
        }
    }
}
