package cn.toside.music.nativeapp.karaoke

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.NativeApplication
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.lyrics.Lyrics
import cn.toside.music.nativeapp.player.AudioStreamCache
import cn.toside.music.nativeapp.ui.ForegroundTestActivities
import cn.toside.music.nativeapp.ui.KaraokeActivity
import cn.toside.music.nativeapp.ui.KaraokePlaybackStatus
import cn.toside.music.nativeapp.ui.KaraokeQueueActivity
import cn.toside.music.nativeapp.ui.KaraokeStage
import cn.toside.music.nativeapp.ui.KaraokeViewModel
import cn.toside.music.nativeapp.ui.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
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
        val song = Song(System.nanoTime(), Uri.fromFile(input), "Karaoke fixture", "Artist", 5000)
        val lrc = File.createTempFile("karaoke-lyrics", ".lrc", context.cacheDir)
        val expectedLyrics = (1..7).map { "Lyric line $it" }
        var studio: KaraokeActivity? = null
        var monitor: android.app.Instrumentation.ActivityMonitor? = null
        var saved: KaraokeRecording? = null
        try {
            await { activity.playbackService.value != null }
            val times = listOf("00:00.20", "00:00.50", "00:02.00", "00:02.50", "00:03.00", "00:03.50", "00:04.00")
            lrc.writeText(times.zip(expectedLyrics) { time, text -> "[$time]$text" }.joinToString("\n"))
            Lyrics(context).import(song.id, song.source, Uri.fromFile(lrc))
            instrumentation.runOnMainSync {
                activity.playbackService.value!!.apply { clearQueue(); enqueue(listOf(song)) }
                MainActivity::class.java.getDeclaredMethod("showPlayerDetails").apply { isAccessible = true }.invoke(activity)
            }
            await { activity.playerFragment()?.view?.findViewWithTag<View>("player-karaoke")?.isShown == true }
            monitor = instrumentation.addMonitor(KaraokeActivity::class.java.name, null, false)
            instrumentation.runOnMainSync { activity.playerFragment()!!.requireView().findViewWithTag<View>("player-karaoke").performClick() }
            studio = instrumentation.waitForMonitorWithTimeout(monitor, 7000) as? KaraokeActivity
            val screen = checkNotNull(studio)
            fun lyricRows(): List<TextView> {
                val panel = screen.window.decorView.findViewWithTag<LinearLayout>("karaoke-lyrics")
                if (panel?.isShown != true) return emptyList()
                return (0 until panel.childCount).map { panel.getChildAt(it) as TextView }.filter { it.isShown }
            }

            await {
                screen.window.decorView.findViewWithTag<Button>("karaoke-capture")?.let {
                    it.isEnabled && it.text == screen.getString(R.string.karaoke_record)
                } == true
            }
            await { lyricRows().map { it.text.toString() } == expectedLyrics.take(5) && lyricRows().none { it.isSelected } }
            lateinit var model: KaraokeViewModel
            val beforeRecording = System.currentTimeMillis()
            instrumentation.runOnMainSync {
                val original = screen.window.decorView.findViewWithTag<Switch>("karaoke-original")
                assertTrue(original.isShown && original.isEnabled)
                assertFalse(original.isChecked)
                model = ViewModelProvider(screen)[KaraokeViewModel::class.java]
                model.record(syntheticCapture())
            }
            val secondWindow = expectedLyrics.take(6)
            await { lyricRows().map { it.text.toString() } == secondWindow && lyricRows().singleOrNull { it.isSelected }?.text == expectedLyrics[1] }
            val beforeSwitch = model.state.value.position
            instrumentation.runOnMainSync { screen.window.decorView.findViewWithTag<Switch>("karaoke-original").performClick() }
            await {
                model.state.value.let { it.originalEnabled && !it.originalLoading &&
                    it.stage == KaraokeStage.RECORDING && it.position > beforeSwitch }
            }
            instrumentation.runOnMainSync { screen.window.decorView.findViewWithTag<Button>("karaoke-capture").performClick() }
            await {
                model.state.value.stage == KaraokeStage.PAUSED &&
                    lyricRows().map { it.text.toString() } == secondWindow && lyricRows().singleOrNull { it.isSelected }?.text == expectedLyrics[1]
            }
            instrumentation.runOnMainSync {
                screen.window.decorView.findViewWithTag<Switch>("karaoke-original").performClick()
                assertFalse(model.state.value.originalEnabled)
                screen.window.decorView.findViewWithTag<Button>("karaoke-stop").performClick()
            }
            await { model.state.value.stage == KaraokeStage.REVIEW }
            assertTrue(model.state.value.duration in 1..4999)
            instrumentation.runOnMainSync { screen.window.decorView.findViewWithTag<Button>("karaoke-preview").performClick() }
            await { model.state.value.draftPlayback?.playing == true }
            instrumentation.runOnMainSync { screen.window.decorView.findViewWithTag<Button>("karaoke-save").performClick() }
            await { model.state.value.saved != null }
            saved = checkNotNull(model.state.value.saved)
            assertEquals(song.title, saved.title)
            assertEquals(saved.durationMs, model.state.value.duration)
            assertTrue(checkNotNull(saved.recordedAt) in beforeRecording..System.currentTimeMillis())
            assertTrue(saved.file.isFile)
        } finally {
            monitor?.let(instrumentation::removeMonitor)
            instrumentation.runOnMainSync {
                studio?.finish()
                activity.playbackService.value?.clearQueue()
                activity.finish()
            }
            saved?.let { recording -> recording.file.delete(); recording.file.resolveSibling("${recording.id}.json").delete() }
            context.filesDir.resolve("lyrics/${song.id}.lrc").delete()
            lrc.delete()
            input.delete()
        }
    }

    @Test fun draftPreviewSeeksAndRemainsPlayingWhileTheMixChanges() = runBlocking {
        val input = File.createTempFile("karaoke-draft-seek", ".wav", context.cacheDir).apply { silence(this, 7) }
        val song = Song(System.nanoTime(), Uri.fromFile(input), "Draft preview fixture", "", 7000)
        val screen = ForegroundTestActivities.start(KaraokeActivity.intent(context, song)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as KaraokeActivity
        try {
            lateinit var model: KaraokeViewModel
            instrumentation.runOnMainSync { model = ViewModelProvider(screen)[KaraokeViewModel::class.java] }
            await { model.state.value.stage == KaraokeStage.READY }
            instrumentation.runOnMainSync { model.record(syntheticCapture()) }
            await { model.state.value.stage == KaraokeStage.RECORDING && model.state.value.position >= 3600 }
            instrumentation.runOnMainSync { model.stop() }
            await { model.state.value.stage == KaraokeStage.REVIEW }
            val duration = model.state.value.duration
            val root = screen.window.decorView
            val bar = root.findViewWithTag<SeekBar>("karaoke-preview-position")
            val play = root.findViewWithTag<Button>("karaoke-preview")
            fun change(tag: String, value: Int) {
                val slider = root.findViewWithTag<SeekBar>(tag)
                assertTrue(slider.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value.toFloat()) }))
            }
            instrumentation.runOnMainSync {
                assertTrue(bar.isShown && bar.isEnabled)
                assertEquals(duration, bar.max)
                change("karaoke-preview-position", 1800)
                assertEquals(1800, model.state.value.draftPlayback?.positionMs)
                play.performClick()
            }
            await { model.state.value.draftPlayback?.let { it.playing && it.positionMs in 1900..3000 } == true }
            instrumentation.runOnMainSync {
                change("karaoke-preview-position", 300)
                change("karaoke-music-gain", 36)
                change("karaoke-voice-gain", 64)
                root.findViewWithTag<CheckBox>("karaoke-noise").performClick()
                val state = model.state.value
                assertTrue(state.draftPlayback?.playing == true)
                assertEquals(36, state.edit.music)
                assertEquals(64, state.edit.voice)
                assertTrue(state.edit.reduceNoise)
            }
            await { model.state.value.draftPlayback?.let { it.playing && it.positionMs in 450..1200 } == true }
            instrumentation.runOnMainSync {
                model.edit { it.copy(trimStartMs = 1600, trimEndMs = 200, voiceDelayMs = 40) }
                assertTrue(model.state.value.draftPlayback?.playing == true)
            }
            await { model.state.value.draftPlayback?.let {
                it.playing && it.durationMs == duration - 1800 && it.positionMs in 100..1000
            } == true }
            instrumentation.runOnMainSync {
                play.performClick()
                assertEquals(false, model.state.value.draftPlayback?.playing)
                change("karaoke-preview-position", duration - 1820)
                assertEquals(false, model.state.value.draftPlayback?.playing)
                play.performClick()
            }
            await { model.state.value.draftPlayback?.let { !it.playing && it.positionMs == it.durationMs } == true }
        } finally {
            instrumentation.runOnMainSync { screen.finish() }
            AudioStreamCache.get(context).removeSong(AudioStreamCache.key(song, ""))
            input.delete()
        }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun myListsOpensKaraokePlaybackAndRecordingDetails() = runBlocking {
        val activity = mainActivity()
        val input = File.createTempFile("karaoke-picker", ".wav", context.cacheDir).apply { silence(this, 8) }
        val id = UUID.randomUUID().toString()
        val title = "Karaoke picker fixture"
        val recordedAt = 1_700_000_000_000L
        val savedAt = 1_730_000_000_000L
        var library: KaraokeActivity? = null
        val monitor = instrumentation.addMonitor(KaraokeActivity::class.java.name, null, false)
        try {
            val recording = KaraokeLibrary(context).save(id, Song(1, Uri.EMPTY, title, "", 8000), input, recordedAt)
            assertTrue(recording.file.setLastModified(savedAt))
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("openMyLists").apply { isAccessible = true }.invoke(activity)
            }
            await {
                activity.window.decorView.findViewWithTag<TextView>("music-page-title")?.text?.toString()
                    ?.contains(activity.getString(R.string.playlist_favorites)) == true
            }
            instrumentation.runOnMainSync {
                activity.window.decorView.findViewWithTag<View>("music-page-title").performClick()
            }
            await { activity.window.decorView.findViewWithTag<View>("playlist-choice-karaoke")?.isShown == true }
            instrumentation.runOnMainSync {
                activity.window.decorView.findViewWithTag<View>("playlist-choice-karaoke").performClick()
            }
            library = instrumentation.waitForMonitorWithTimeout(monitor, 7000) as? KaraokeActivity
            val opened = checkNotNull(library)
            await {
                val matches = arrayListOf<View>()
                opened.window.decorView.findViewsWithText(matches, title, View.FIND_VIEWS_WITH_TEXT)
                matches.any { it.isShown }
            }
            lateinit var model: KaraokeViewModel
            lateinit var play: Button
            lateinit var seek: SeekBar
            instrumentation.runOnMainSync {
                val root = opened.window.decorView
                model = ViewModelProvider(opened)[KaraokeViewModel::class.java]
                play = root.findViewWithTag("karaoke-play-$id")
                seek = root.findViewWithTag("karaoke-seek-$id")
                assertTrue(root.findViewWithTag<TextView>("karaoke-date-$id").text.contains("2024"))
                assertEquals(opened.getString(R.string.karaoke_play), play.text)
                play.performClick()
            }
            await { model.state.value.recordingPlayback?.let { it.status == KaraokePlaybackStatus.PLAYING && seek.progress > 0 } == true }
            instrumentation.runOnMainSync { play.performClick() }
            await { model.state.value.recordingPlayback?.status == KaraokePlaybackStatus.PAUSED && seek.isShown }
            fun seekTo(position: Float) {
                assertTrue(seek.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, position) }))
            }
            instrumentation.runOnMainSync {
                assertEquals(opened.getString(R.string.karaoke_continue_playback), play.text)
                seekTo(5000f)
                assertEquals(5000, model.state.value.recordingPlayback?.positionMs)
                play.performClick()
            }
            await { model.state.value.recordingPlayback?.let { it.status == KaraokePlaybackStatus.PLAYING && it.positionMs in 5001..7800 } == true }
            instrumentation.runOnMainSync { seekTo(2000f) }
            await { model.state.value.recordingPlayback?.let { it.status == KaraokePlaybackStatus.PLAYING && it.positionMs in 2001..4000 } == true }
            instrumentation.runOnMainSync {
                play.performClick()
                opened.window.decorView.findViewWithTag<Button>("karaoke-more-$id").performClick()
            }
            fun detailsMenuItem(): Pair<ListView, Int>? {
                val title = opened.getString(R.string.karaoke_details)
                val items = arrayListOf<View>()
                WindowInspector.getGlobalWindowViews().forEach { it.findViewsWithText(items, title, View.FIND_VIEWS_WITH_TEXT) }
                val label = items.filterIsInstance<TextView>().firstOrNull { it.isShown && it.text == title } ?: return null
                val menu = generateSequence(label.parent) { it.parent }.filterIsInstance<ListView>().firstOrNull() ?: return null
                return menu to menu.getPositionForView(label)
            }
            await { detailsMenuItem() != null }
            instrumentation.runOnMainSync {
                val (menu, position) = checkNotNull(detailsMenuItem())
                assertTrue(menu.performItemClick(menu.getChildAt(position - menu.firstVisiblePosition), position, menu.adapter.getItemId(position)))
            }
            await {
                val details = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { it.findViewWithTag<View>("karaoke-details") }
                if (details?.isShown != true) return@await false
                val required = listOf("2023", opened.getString(R.string.karaoke_bitrate_value, 1536),
                    opened.getString(R.string.karaoke_file_size), opened.getString(R.string.karaoke_recording_duration))
                required.all { value ->
                    val matches = arrayListOf<View>()
                    details.findViewsWithText(matches, value, View.FIND_VIEWS_WITH_TEXT)
                    matches.any { it.isShown }
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { library?.finish(); activity.finish() }
            context.filesDir.resolve("karaoke/$id.wav").delete()
            context.filesDir.resolve("karaoke/$id.json").delete()
            input.delete()
        }
    }

    @Test fun accompanimentPreparationContinuesAfterClosingAndReopeningStudio() = runBlocking {
        val activity = mainActivity()
        val input = File.createTempFile("karaoke-progress", ".wav", context.cacheDir).apply { silence(this, 3) }
        val output = File.createTempFile("karaoke-result", ".wav", context.cacheDir)
        val downloadProgress = CompletableDeferred<(Int) -> Unit>()
        val finishDownload = CompletableDeferred<Unit>()
        val song = Song(System.nanoTime(), Uri.fromFile(input), "Progress fixture", "", 0)
        var reopened: KaraokeActivity? = null
        try {
            lateinit var model: KaraokeViewModel
            instrumentation.runOnMainSync {
                model = ViewModelProvider(activity)[KaraokeViewModel::class.java]
                model.prepare(song) { file, report ->
                    report(50)
                    downloadProgress.complete(report)
                    finishDownload.await()
                    withContext(Dispatchers.IO) { input.copyTo(file) }
                    Uri.fromFile(file)
                }
            }
            await { model.state.value.stage == KaraokeStage.PREPARING && model.state.value.prepareProgress == 22 }
            instrumentation.runOnMainSync { activity.finish() }
            await { activity.isDestroyed }

            val screen = ForegroundTestActivities.start(KaraokeActivity.intent(context, song)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as KaraokeActivity
            reopened = screen
            lateinit var restored: KaraokeViewModel
            instrumentation.runOnMainSync { restored = ViewModelProvider(screen)[KaraokeViewModel::class.java] }
            await { restored.state.value.stage == KaraokeStage.PREPARING && restored.state.value.prepareProgress == 22 }
            downloadProgress.await()(80)
            await { restored.state.value.stage == KaraokeStage.PREPARING && restored.state.value.prepareProgress == 36 }
            finishDownload.complete(Unit)
            await { restored.state.value.stage == KaraokeStage.READY && restored.state.value.prepareProgress == 100 }

            val processing = mutableListOf<Int>()
            KaraokeAudio.prepare(context, Uri.fromFile(input), output, songDurationMs = 0, progress = processing::add)
            assertTrue(processing.any { it in 1..50 })
            assertTrue(processing.last() in 90..99)
            assertTrue(processing.zipWithNext().all { (first, second) -> second > first })
        } finally {
            finishDownload.complete(Unit)
            instrumentation.runOnMainSync { reopened?.finish(); activity.finish() }
            AudioStreamCache.get(context).removeSong(AudioStreamCache.key(song, ""))
            input.delete()
            output.delete()
        }
    }

    @Test fun conversionQueueRunsOneSongAtATimeAndCancelsWaitingAndActiveSongs() = runBlocking {
        val preparation = (context.applicationContext as NativeApplication).karaokePreparation
        val directory = context.cacheDir.resolve("karaoke-queue-test-${UUID.randomUUID()}").apply { mkdir() }
        val songs = List(4) { index ->
            val input = directory.resolve("input-$index.wav").apply { silence(this, 1) }
            Song(System.nanoTime(), Uri.fromFile(input), "Queue song ${index + 1}", "Artist", 1000)
        }
        val started = List(songs.size) { CompletableDeferred<Unit>() }
        val proceed = List(songs.size) { CompletableDeferred<Unit>() }
        val stopping = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val jobs = mutableMapOf<Int, Deferred<Result<KaraokePreparation.Prepared>>>()
        fun enqueue(index: Int) {
            val song = songs[index]
            jobs[index] = async {
                runCatching {
                    preparation.prepare(song, directory.resolve("result-$index"), { _, report ->
                        report(50)
                        started[index].complete(Unit)
                        if (index == 0) try { awaitCancellation() } finally {
                            withContext(NonCancellable) {
                                stopping.complete(Unit)
                                stopped.await()
                            }
                        }
                        proceed[index].await()
                        song.uri
                    }, installModel = false, previous = null) {}
                }
            }
        }
        fun entries() = preparation.queue.value.filter { it.song in songs }
        suspend fun result(index: Int) = withTimeout(12_000) { jobs.getValue(index).await() }
        var openedStudio: KaraokeActivity? = null
        var openedQueue: KaraokeQueueActivity? = null
        val monitor = instrumentation.addMonitor(KaraokeQueueActivity::class.java.name, null, false)
        try {
            enqueue(0)
            await { started[0].isCompleted }
            val studio = ForegroundTestActivities.start(KaraokeActivity.intent(context, songs[0])
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as KaraokeActivity
            openedStudio = studio
            lateinit var model: KaraokeViewModel
            instrumentation.runOnMainSync { model = ViewModelProvider(studio)[KaraokeViewModel::class.java] }
            await { model.state.value.prepareProgress == 22 }
            for (index in 1..3) {
                enqueue(index)
                await { entries().map { it.song } == songs.take(index + 1) }
            }

            instrumentation.runOnMainSync { studio.window.decorView.findViewWithTag<Button>("karaoke-open-queue").performClick() }
            val queue = instrumentation.waitForMonitorWithTimeout(monitor, 7000) as KaraokeQueueActivity
            openedQueue = queue
            val ids = entries().map { it.id }
            fun row(index: Int) = queue.window.decorView.findViewWithTag<View>("karaoke-queue:${ids[index]}")
            fun status(index: Int) = row(index)?.findViewWithTag<TextView>("karaoke-queue-status")?.text?.toString()
            fun cancel(index: Int) = row(index)?.findViewWithTag<Button>("karaoke-queue-cancel")
            await { status(0) == queue.getString(R.string.karaoke_preparing_progress, 22) &&
                status(1) == queue.getString(R.string.karaoke_queue_waiting, 1) }
            assertTrue(started.drop(1).none { it.isCompleted })

            instrumentation.runOnMainSync { checkNotNull(cancel(1)).performClick() }
            assertTrue(result(1).exceptionOrNull() is KaraokePreparation.Cancelled)
            await { row(1) == null && status(2) == queue.getString(R.string.karaoke_queue_waiting, 1) }

            instrumentation.runOnMainSync { checkNotNull(cancel(0)).performClick() }
            await { stopping.isCompleted && status(0) == queue.getString(R.string.karaoke_queue_cancelling) &&
                cancel(0)?.isEnabled == false }
            assertFalse(started[2].isCompleted)
            stopped.complete(Unit)
            assertTrue(result(0).exceptionOrNull() is KaraokePreparation.Cancelled)
            await { row(0) == null && started[2].isCompleted && status(3) == queue.getString(R.string.karaoke_queue_waiting, 1) }
            assertFalse(started[3].isCompleted)

            instrumentation.runOnMainSync { queue.window.decorView.findViewWithTag<View>("page-back").performClick() }
            await { studio.hasWindowFocus() && model.state.value.stage == KaraokeStage.IDLE &&
                model.state.value.message == R.string.karaoke_preparation_cancelled }
            assertEquals(songs.drop(2), entries().map { it.song })
            proceed[2].complete(Unit)
            assertTrue(result(2).isSuccess)
            await { started[3].isCompleted }
            proceed[3].complete(Unit)
            assertTrue(result(3).isSuccess)
            assertTrue(entries().isEmpty())
            assertFalse(started[1].isCompleted)

            instrumentation.runOnMainSync { studio.window.decorView.findViewWithTag<Button>("karaoke-capture").performClick() }
            await { model.state.value.stage == KaraokeStage.READY }
        } finally {
            stopped.complete(Unit)
            entries().forEach { preparation.cancel(it.id) }
            jobs.values.forEach { withTimeout(12_000) { it.join() } }
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { openedQueue?.finish(); openedStudio?.finish() }
            val cache = AudioStreamCache.get(context)
            songs.forEach { cache.removeSong(AudioStreamCache.key(it, "")) }
            directory.deleteRecursively()
        }
    }

    @Test fun sameRecordingJoinsItsExistingConversionAndACompletedCacheBypassesTheQueue() = runBlocking {
        val preparation = (context.applicationContext as NativeApplication).karaokePreparation
        val directory = context.cacheDir.resolve("karaoke-dedup-test-${UUID.randomUUID()}").apply { mkdir() }
        val input = directory.resolve("input.wav").apply { silence(this, 1) }
        val id = System.nanoTime()
        val song = Song(id, Uri.EMPTY, "Online recording", "Artist", 1000, "kw", """{"songmid":$id}""")
        val enriched = song.copy(duration = 1100, sourceInfo = """{"songmid":$id,"img":"cover"}""")
        val blocker = Song(id + 1, Uri.fromFile(input), "Other recording", "Artist", 1000)
        val settings = context.getSharedPreferences("app-settings", Context.MODE_PRIVATE)
        val savedModel = settings.all[MdxParameters.ENABLED_KEY] as? Boolean
        val savedLimit = settings.getString("player.cacheSize", null)
        settings.edit().putBoolean(MdxParameters.ENABLED_KEY, false).putString("player.cacheSize", "32").commit()
        val release = CompletableDeferred<Unit>()
        val blockerRelease = CompletableDeferred<Unit>()
        try {
            val original = async {
                preparation.prepare(song, directory.resolve("first"), { _, report ->
                    report(50); release.await(); Uri.fromFile(input)
                }, installModel = false, previous = null) {}
            }
            await { preparation.queue.value.any { it.song == song && it.progress.percent == 22 } }
            val originalId = preparation.queue.value.single { it.song == song }.id
            val joined = CompletableDeferred<Unit>()
            val duplicate = async {
                preparation.prepare(enriched, directory.resolve("second"), { _, _ -> error("duplicate conversion") },
                    installModel = false, previous = null) { if (it.percent == 22) joined.complete(Unit) }
            }
            withTimeout(4000) { joined.await() }
            assertEquals(listOf(originalId), preparation.queue.value.filter { it.song.id == id && it.song.source == "kw" }.map { it.id })
            release.complete(Unit)
            assertArrayEquals(original.await().backing.file.readBytes(), duplicate.await().backing.file.readBytes())

            val other = async {
                preparation.prepare(blocker, directory.resolve("other"), { _, report ->
                    report(50); blockerRelease.await(); Uri.fromFile(input)
                }, installModel = false, previous = null) {}
            }
            await { preparation.queue.value.any { it.song == blocker && it.progress.percent == 22 } }
            val cached = withTimeout(3000) {
                preparation.prepare(song, directory.resolve("cached"), { _, _ -> error("cached recording must not convert again") },
                    installModel = false, previous = null) {}
            }
            assertArrayEquals(original.await().backing.file.readBytes(), cached.backing.file.readBytes())
            assertFalse(preparation.queue.value.any { it.song.id == id && it.song.source == "kw" })
            blockerRelease.complete(Unit)
            other.await()
        } finally {
            release.complete(Unit); blockerRelease.complete(Unit)
            settings.edit().apply {
                if (savedModel == null) remove(MdxParameters.ENABLED_KEY) else putBoolean(MdxParameters.ENABLED_KEY, savedModel)
                if (savedLimit == null) remove("player.cacheSize") else putString("player.cacheSize", savedLimit)
            }.commit()
            val cache = AudioStreamCache.get(context)
            listOf(song, blocker).forEach { cache.removeSong(AudioStreamCache.key(it, "")) }
            directory.deleteRecursively()
        }
        Unit
    }

    @Test fun generatedAccompanimentIsReusedAndRefreshesWhenLocalAudioChanges() = runBlocking {
        val directory = context.cacheDir.resolve("karaoke-cache-test-${UUID.randomUUID()}").apply { mkdir() }
        val input = directory.resolve("input.wav").apply { silence(this, 1) }
        val song = Song(System.nanoTime(), Uri.fromFile(input), "Backing cache fixture", "", 0)
        val cache = KaraokeBackingCache(context)
        val first = directory.resolve("first.wav")
        val second = directory.resolve("second.wav")
        val third = directory.resolve("third.wav")
        val cachedOriginal = directory.resolve("cached-original.wav")
        try {
            val original = cache.reuseOrPrepare(song, first, cachedOriginal) {
                KaraokeAudio.prepare(context, song.uri, first, song.duration, cachedOriginal) {}
            }
            assertTrue(first.delete())
            assertTrue(cachedOriginal.delete())
            val reused = KaraokeBackingCache(context).reuseOrPrepare(song, second, cachedOriginal) {
                error("Accompaniment should be cached")
            }
            assertEquals(original.frames, reused.frames)
            assertEquals(original.stereo, reused.stereo)
            assertEquals(cachedOriginal, reused.original)
            assertEquals(second.length(), cachedOriginal.length())

            silence(input, 2)
            val refreshed = cache.reuseOrPrepare(song, third) {
                KaraokeAudio.prepare(context, song.uri, third, song.duration) {}
            }
            assertTrue(refreshed.frames > reused.frames)
            AudioStreamCache.get(context).removeSong(AudioStreamCache.key(song, ""))
            assertTrue(second.isFile && third.isFile)
            var regenerated = false
            cache.reuseOrPrepare(song, first) {
                regenerated = true
                KaraokeAudio.prepare(context, song.uri, first, song.duration) {}
            }
            assertTrue(regenerated)
        } finally {
            AudioStreamCache.get(context).removeSong(AudioStreamCache.key(song, ""))
            directory.deleteRecursively()
        }
    }

    @Test fun separatedAccompanimentCacheMatchesAudioParameters() = runBlocking {
        val directory = context.cacheDir.resolve("karaoke-mdx-cache-test-${UUID.randomUUID()}").apply { mkdir() }
        val input = directory.resolve("input.wav").apply { silence(this, 1) }
        val target = directory.resolve("output.wav")
        val song = Song(System.nanoTime(), Uri.fromFile(input), "MDX cache fixture", "", 0)
        val cache = KaraokeBackingCache(context)
        var generations = 0
        suspend fun using(parameters: MdxParameters?) = cache.reuseOrPrepare(song, target, separation = parameters) {
            generations++
            KaraokeAudio.prepare(context, song.uri, target, song.duration) {}.copy(separation = parameters)
        }
        val defaults = MdxParameters.Default
        val stronger = defaults.copy(strengthPercent = 110)
        val longerOverlap = stronger.copy(overlapMs = 500)
        try {
            assertTrue(using(defaults).separated)
            assertTrue(using(stronger).separated)
            assertTrue(using(longerOverlap).separated)
            assertEquals(3, generations)
            assertTrue(using(longerOverlap.copy(threads = 1)).separated)
            assertTrue(using(defaults).separated)
            assertEquals(3, generations)
            assertFalse(using(null).separated)
            assertEquals(4, generations)
        } finally {
            AudioStreamCache.get(context).removeSong(AudioStreamCache.key(song, ""))
            directory.deleteRecursively()
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
            val saved = library.save(id, song, input, 1_700_000_000_000L)
            assertEquals(1000, saved.durationMs)
            assertEquals(song.title, library.list().first { it.id == id }.title)
            assertEquals(context.filesDir.resolve("karaoke").canonicalFile, saved.file.parentFile!!.canonicalFile)
            library.export(saved, Uri.fromFile(output))
            assertArrayEquals(input.readBytes(), output.readBytes())
            val share = FileProvider.getUriForFile(context, "${context.packageName}.karaoke", saved.file)
            assertEquals("content", share.scheme)
            assertEquals(input.length(), context.contentResolver.openFileDescriptor(share, "r")!!.use { it.statSize })
            val metadata = context.filesDir.resolve("karaoke/$id.json")
            metadata.writeText(JSONObject(metadata.readText()).apply { remove("recordedAt") }.toString())
            val legacy = checkNotNull(library.read(id))
            assertNull(legacy.recordedAt)
            assertEquals(legacy.file.lastModified(), legacy.savedAt)
        } finally {
            context.filesDir.resolve("karaoke/$id.wav").delete()
            context.filesDir.resolve("karaoke/$id.json").delete()
            input.delete(); output.delete()
        }
    }
}
