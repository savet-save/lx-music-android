package cn.toside.music.nativeapp.karaoke

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Button
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.ui.ForegroundTestActivities
import cn.toside.music.nativeapp.ui.KaraokeActivity
import cn.toside.music.nativeapp.ui.KaraokeStage
import cn.toside.music.nativeapp.ui.KaraokeViewModel
import cn.toside.music.nativeapp.ui.MainActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class KaraokeWorkflowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun mainActivity() = ForegroundTestActivities.start(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity

    private fun silence(file: File, seconds: Int) = KaraokeAudio.WavWriter(file).use { output ->
        val samples = ShortArray(KaraokeAudio.RATE / 10 * 2)
        repeat(seconds * 10) { output.write(samples, samples.size) }
    }

    private suspend fun await(check: () -> Boolean) = withTimeout(12_000) {
        while (true) {
            var ready = false
            instrumentation.runOnMainSync { ready = check() }
            if (ready) break
            delay(40)
        }
    }

    @Test fun playerHeaderOpensStudioAndEarlyStopCanBePreviewedAndSaved() = runBlocking {
        val activity = mainActivity()
        val input = File.createTempFile("karaoke-source", ".wav", context.cacheDir).apply { silence(this, 5) }
        var studio: KaraokeActivity? = null
        var monitor: android.app.Instrumentation.ActivityMonitor? = null
        var saved: KaraokeRecording? = null
        try {
            await { activity.playbackService.value != null }
            val song = Song(System.nanoTime(), Uri.fromFile(input), "Karaoke fixture", "Artist", 5000)
            instrumentation.runOnMainSync {
                activity.playbackService.value!!.apply { clearQueue(); enqueue(listOf(song)) }
                MainActivity::class.java.getDeclaredMethod("showPlayerDetails").apply { isAccessible = true }.invoke(activity)
            }
            await { activity.playerFragment()?.view?.findViewWithTag<View>("player-karaoke")?.isShown == true }
            monitor = instrumentation.addMonitor(KaraokeActivity::class.java.name, null, false)
            instrumentation.runOnMainSync { activity.playerFragment()!!.requireView().findViewWithTag<View>("player-karaoke").performClick() }
            studio = instrumentation.waitForMonitorWithTimeout(monitor, 7000) as? KaraokeActivity
            val screen = checkNotNull(studio)
            await {
                screen.window.decorView.findViewWithTag<Button>("karaoke-capture")?.let {
                    it.isEnabled && it.text == screen.getString(R.string.karaoke_record)
                } == true
            }
            lateinit var model: KaraokeViewModel
            instrumentation.runOnMainSync {
                model = ViewModelProvider(screen)[KaraokeViewModel::class.java]
                model.record(syntheticCapture())
            }
            await { model.state.value.position >= 300 }
            instrumentation.runOnMainSync { screen.window.decorView.findViewWithTag<Button>("karaoke-stop").performClick() }
            await { model.state.value.stage == KaraokeStage.REVIEW }
            assertTrue(model.state.value.duration in 1..4999)
            instrumentation.runOnMainSync { screen.window.decorView.findViewWithTag<Button>("karaoke-preview").performClick() }
            await { model.state.value.playingPreview == KaraokeViewModel.DRAFT }
            instrumentation.runOnMainSync { screen.window.decorView.findViewWithTag<Button>("karaoke-save").performClick() }
            await { model.state.value.saved != null }
            saved = checkNotNull(model.state.value.saved)
            assertEquals(song.title, saved.title)
            assertEquals(saved.durationMs, model.state.value.duration)
            assertTrue(saved.file.isFile)
        } finally {
            monitor?.let(instrumentation::removeMonitor)
            instrumentation.runOnMainSync {
                studio?.finish()
                activity.playbackService.value?.clearQueue()
                activity.finish()
            }
            saved?.let { recording -> recording.file.delete(); recording.file.resolveSibling("${recording.id}.json").delete() }
            input.delete()
        }
    }

    @Test fun audioClockPausesAndResumesCaptureThenStopsAtSongEnd() = runBlocking {
        val activity = mainActivity()
        val input = File.createTempFile("karaoke-timeline", ".wav", context.cacheDir).apply { silence(this, 2) }
        val voice = File.createTempFile("karaoke-take", ".pcm", context.cacheDir)
        try {
            val backing = KaraokeBacking(input, KaraokeAudio.RATE * 2L, true)
            val driver = syntheticCapture()
            val progress = MutableStateFlow(0L to false)
            val take = async { driver.record(context, backing, voice) { frames, paused -> progress.value = frames to paused } }
            withTimeout(5000) { progress.first { it.first >= KaraokeAudio.RATE / 10 } }
            driver.pause()
            val paused = withTimeout(3000) { progress.first { it.second } }
            delay(220)
            assertEquals(paused, progress.value)
            driver.resume()
            val result = withTimeout(8000) { take.await() }
            assertEquals(backing.frames, result.frames)
            assertEquals(backing.frames * 2, voice.length())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            input.delete(); voice.delete()
        }
    }

    private fun syntheticCapture() = KaraokeCapture {
        object : KaraokeMicrophone {
            private var recording = false
            override fun start() { check(!recording); recording = true }
            override fun stop() { check(recording); recording = false }
            override fun close() { recording = false }
            override fun read(samples: ShortArray): Int {
                check(recording)
                samples.fill(1000)
                return KaraokeAudio.RATE / 100
            }
        }
    }

    @Test fun finalMixHasPrivatePlaylistEntryAndCanBeExportedOrShared() = runBlocking {
        val input = File.createTempFile("karaoke-library", ".wav", context.cacheDir).apply { silence(this, 1) }
        val output = File.createTempFile("karaoke-export", ".wav", context.cacheDir)
        val library = KaraokeLibrary(context)
        val id = UUID.randomUUID().toString()
        try {
            val song = Song(1, Uri.EMPTY, "Synthetic recording", "Test artist", 1000)
            val saved = library.save(id, song, input)
            assertEquals(1000, saved.durationMs)
            assertEquals(song.title, library.list().first { it.id == id }.title)
            assertEquals(context.filesDir.resolve("karaoke").canonicalFile, saved.file.parentFile!!.canonicalFile)
            library.export(saved, Uri.fromFile(output))
            assertArrayEquals(input.readBytes(), output.readBytes())
            val share = FileProvider.getUriForFile(context, "${context.packageName}.karaoke", saved.file)
            assertEquals("content", share.scheme)
            assertEquals(input.length(), context.contentResolver.openFileDescriptor(share, "r")!!.use { it.statSize })
        } finally {
            context.filesDir.resolve("karaoke/$id.wav").delete()
            context.filesDir.resolve("karaoke/$id.json").delete()
            input.delete(); output.delete()
        }
    }
}
