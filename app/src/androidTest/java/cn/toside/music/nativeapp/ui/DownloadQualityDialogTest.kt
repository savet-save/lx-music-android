package cn.toside.music.nativeapp.ui

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.data.DownloadProbe
import cn.toside.music.nativeapp.data.Downloads
import cn.toside.music.nativeapp.data.Song
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class DownloadQualityDialogTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(check: () -> Boolean) {
        repeat(150) { var ready = false; main { ready = check() }; if (ready) return; SystemClock.sleep(50) }
        fail("Timed out waiting for download probes")
    }

    @Test fun eachQualityShowsThemedProgressAndResultsAndDismissCancelsPendingWork() = runBlocking {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val song = Song(-140, Uri.EMPTY, "下载可用性检测", "测试歌手", 1000, "kg", """{"types":[{"type":"128k"},{"type":"320k"},{"type":"flac"}]}""")
        var dialog: Dialog? = null
        try {
            await { activity.settingsPlayer() != null && activity.hasWindowFocus() }
            for (theme in NativeThemes(activity).all().filter { it.id in listOf("blue", "black") }) {
                val release = CompletableDeferred<Unit>()
                val cancelled = CompletableDeferred<Unit>()
                var dismissed = false
                var selected: String? = null
                val palette = UiPalette(activity, theme)
                main {
                    dialog = showDownloadQuality(activity, song, palette, LxStyles(activity, palette, Typeface.DEFAULT), scope,
                        probe = { quality, progress ->
                            progress(1024)
                            release.await()
                            when (quality) {
                                "128k" -> progress(DownloadProbe.MAX_BYTES)
                                "320k" -> throw IOException("Unavailable fixture")
                                else -> try { awaitCancellation() } finally { cancelled.complete(Unit) }
                            }
                        }, dismissed = { dismissed = true }
                    ) { selected = it }
                }
                val root = dialog!!.window!!.decorView
                await { root.isLaidOut && root.width > 0 && root.findViewWithTag<ProgressBar>("download-quality-progress-128k").progress == 1024 }
                main {
                    listOf("128k", "320k").forEach { quality ->
                        val progress = root.findViewWithTag<ProgressBar>("download-quality-progress-$quality")
                        assertEquals(View.VISIBLE, progress.visibility); assertEquals(palette.primary, progress.progressTintList!!.defaultColor)
                        assertEquals(View.GONE, root.findViewWithTag<View>("download-quality-result-$quality").visibility)
                        assertTrue((root.findViewWithTag<View>("download-quality-$quality").width) > 72 * activity.resources.displayMetrics.density)
                    }
                }
                release.complete(Unit)
                await { root.findViewWithTag<TextView>("download-quality-result-128k").text == "√" && root.findViewWithTag<TextView>("download-quality-result-320k").text == "×" && root.findViewWithTag<ProgressBar>("download-quality-progress-flac").progress == 1024 }
                main {
                    listOf("128k", "320k").forEach { quality ->
                        val result = root.findViewWithTag<TextView>("download-quality-result-$quality")
                        assertEquals(palette.primary, result.currentTextColor); assertEquals(View.VISIBLE, result.visibility)
                        assertEquals(View.GONE, root.findViewWithTag<View>("download-quality-progress-$quality").visibility)
                    }
                    val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    if (output != null) {
                        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
                        File(output, "download-probe-${theme.id}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                    }
                    root.findViewWithTag<View>(if (theme.dark) "download-quality-320k" else "download-quality-close").performClick()
                }
                withTimeout(2000) { cancelled.await() }
                await { dismissed }
                assertEquals(if (theme.dark) "320k" else null, selected); assertTrue(scope.isActive)
                assertTrue(Downloads(context).list().none { it.song == song })
                await { activity.hasWindowFocus() }
            }
        } finally { main { dialog?.dismiss(); scope.cancel(); activity.finish() } }
    }
}
