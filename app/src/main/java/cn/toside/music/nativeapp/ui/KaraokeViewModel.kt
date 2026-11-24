package cn.toside.music.nativeapp.ui

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.AppPreferences
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.karaoke.KaraokeAudio
import cn.toside.music.nativeapp.karaoke.KaraokeBacking
import cn.toside.music.nativeapp.karaoke.KaraokeBackingCache
import cn.toside.music.nativeapp.karaoke.KaraokeCapture
import cn.toside.music.nativeapp.karaoke.KaraokeEdit
import cn.toside.music.nativeapp.karaoke.KaraokeLibrary
import cn.toside.music.nativeapp.karaoke.KaraokePreview
import cn.toside.music.nativeapp.karaoke.KaraokeRecording
import cn.toside.music.nativeapp.karaoke.KaraokeTake
import cn.toside.music.nativeapp.karaoke.MdxParameters
import cn.toside.music.nativeapp.karaoke.MdxSeparation
import cn.toside.music.nativeapp.utils.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

internal enum class KaraokeStage { IDLE, PREPARING, READY, RECORDING, PAUSED, MIXING, REVIEW }
internal enum class KaraokePlaybackStatus { PREPARING, PLAYING, PAUSED }

internal data class KaraokePlayback(
    val id: String,
    val durationMs: Int,
    val positionMs: Int = 0,
    val status: KaraokePlaybackStatus = KaraokePlaybackStatus.PREPARING,
)

internal data class KaraokeDraftPlayback(val positionMs: Int, val durationMs: Int, val playing: Boolean = false)

internal data class KaraokeState(
    val stage: KaraokeStage = KaraokeStage.IDLE,
    val prepareProgress: Int = 0,
    val duration: Int = 0,
    val position: Int = 0,
    val mono: Boolean = false,
    val modelEnabled: Boolean = true,
    val modelInstalled: Boolean = false,
    val modelDownload: Int? = null,
    val separated: Boolean = false,
    val originalEnabled: Boolean = false,
    val originalLoading: Boolean = false,
    val edit: KaraokeEdit = KaraokeEdit(),
    val libraryOpen: Boolean = false,
    val recordings: List<KaraokeRecording> = emptyList(),
    val saved: KaraokeRecording? = null,
    val draftPlayback: KaraokeDraftPlayback? = null,
    val recordingPlayback: KaraokePlayback? = null,
    val message: Int? = null,
)

internal class KaraokeViewModel(application: Application) : AndroidViewModel(application) {
    private val files = application.cacheDir.resolve("karaoke-drafts/${UUID.randomUUID()}")
    private val backingCache = KaraokeBackingCache(application)
    private val preferences = AppPreferences(application)
    private val mdx = MdxSeparation(application)
    private val originalWave = files.resolve("original.wav")
    private val library = KaraokeLibrary(application)
    private val editedWave = files.resolve("preview.wav")
    private val audio = application.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { if (it < 0) pausePlayback() }.setWillPauseWhenDucked(true).build()
    private val mutable = MutableStateFlow(KaraokeState())
    val state = mutable.asStateFlow()
    private var backing: KaraokeBacking? = null
    private var take: KaraokeTake? = null
    private var id: String? = null
    private var recordedAt = 0L
    private var renderedEdit: KaraokeEdit? = null
    private var capture: KaraokeCapture? = null
    private var player: MediaPlayer? = null
    private var playerPrepared = false
    private var seekingPosition: Int? = null
    private var playbackProgress: Job? = null
    private var draftPlayer: KaraokePreview? = null
    private var draftWork: Job? = null
    private var draftFrame = 0L
    private var work: Job? = null
    private var originalWork: Job? = null
    private var loadSource: (suspend (File, (Int) -> Unit) -> Uri)? = null

    fun prepare(song: Song, input: suspend (File, (Int) -> Unit) -> Uri) {
        if (state.value.stage != KaraokeStage.IDLE) return
        loadSource = input
        mutable.update { it.copy(stage = KaraokeStage.PREPARING, prepareProgress = 0, message = null) }
        work = viewModelScope.launch {
            val downloaded = files.resolve("source.audio")
            try {
                check(files.mkdirs() || files.isDirectory)
                val target = files.resolve("backing.wav")
                val modelEnabled = preferences.bool(MdxParameters.ENABLED_KEY, true)
                val parameters = MdxParameters.read(preferences)
                val model = if (modelEnabled) mdx.installedModel() else null
                val prepared = backingCache.reuseOrPrepare(song, target, originalWave, separation = parameters.takeIf { model != null }) {
                    val source = input(downloaded) { updatePreparation(it * DOWNLOAD_SHARE / 100) }
                    val offset = if (source == Uri.fromFile(downloaded)) DOWNLOAD_SHARE else 0
                    updatePreparation(offset)
                    KaraokeAudio.prepare(getApplication(), source, target, song.duration, originalWave, model, parameters, onSeparationError = {
                        DiagnosticLog(getApplication()).record("playback", "Karaoke MDX-Net: ${it.message}", "WARN")
                    }) {
                        updatePreparation(offset + it * (100 - offset) / 100)
                    }
                }
                backing = prepared
                mutable.update {
                    it.copy(
                        stage = KaraokeStage.READY, prepareProgress = 100, duration = KaraokeAudio.millis(prepared.frames),
                        mono = !prepared.stereo && !prepared.separated, separated = prepared.separated,
                        modelEnabled = modelEnabled, modelInstalled = model != null,
                        message = R.string.karaoke_model_fallback.takeIf { model != null && !prepared.separated },
                    )
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(KaraokeStage.IDLE, R.string.karaoke_prepare_error, e) }
            finally { downloaded.delete() }
        }
    }

    private fun updatePreparation(percent: Int) {
        mutable.update { if (it.stage == KaraokeStage.PREPARING && percent > it.prepareProgress) it.copy(prepareProgress = percent) else it }
    }

    fun useSeparationModel(song: Song) {
        val input = loadSource ?: return
        val available = backing ?: return
        val current = state.value
        if (current.stage != KaraokeStage.READY || !current.modelEnabled || current.separated || current.modelDownload != null ||
            originalWork?.isActive == true) return
        mutable.update {
            if (current.modelInstalled) it.copy(stage = KaraokeStage.PREPARING, prepareProgress = 0, message = null)
            else it.copy(modelDownload = 0, message = null)
        }
        work = viewModelScope.launch {
            val downloaded = files.resolve("mdx-source.audio")
            try {
                val parameters = MdxParameters.read(preferences)
                mdx.install { percent -> mutable.update { if (it.modelDownload != null) it.copy(modelDownload = percent) else it } }
                val model = checkNotNull(mdx.installedModel())
                mutable.update {
                    it.copy(stage = KaraokeStage.PREPARING, prepareProgress = 0, modelDownload = null, modelInstalled = true)
                }
                val original = available.original ?: KaraokeAudio.prepareOriginal(
                    getApplication(), input(downloaded) { updatePreparation(it / 5) }, originalWave, available.frames,
                )
                val separated = mdx.run(model, available.copy(original = original), parameters) { updatePreparation(20 + it * 80 / 100) }
                backing = separated
                backingCache.remember(song, separated)
                mutable.update { it.copy(stage = KaraokeStage.READY, prepareProgress = 100, mono = false, separated = true) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                DiagnosticLog(getApplication()).record("playback", "Karaoke MDX-Net: ${e.message}", "WARN")
                mutable.update {
                    it.copy(stage = KaraokeStage.READY, modelDownload = null,
                        message = if (it.modelInstalled) R.string.karaoke_model_fallback else R.string.karaoke_model_download_error)
                }
            } finally { downloaded.delete() }
        }
    }

    fun selectOriginal(song: Song, enabled: Boolean) {
        val prepared = backing ?: return
        val input = loadSource ?: return
        if ((!prepared.stereo && !prepared.separated) || state.value.originalEnabled == enabled ||
            state.value.modelDownload != null) return
        mutable.update {
            it.copy(originalEnabled = enabled, message = it.message.takeUnless { message -> message == R.string.karaoke_original_error })
        }
        capture?.selectOriginal(prepared.original.takeIf { enabled })
        if (!enabled || prepared.original != null || originalWork?.isActive == true) return

        mutable.update { it.copy(originalLoading = true) }
        originalWork = viewModelScope.launch {
            val downloaded = files.resolve("original-source.audio")
            try {
                withContext(Dispatchers.IO) { check(originalWave.delete() || !originalWave.exists()) }
                val source = input(downloaded) {}
                val original = KaraokeAudio.prepareOriginal(getApplication(), source, originalWave, prepared.frames)
                backing = prepared.copy(original = original)
                if (state.value.originalEnabled) capture?.selectOriginal(original)
                mutable.update { it.copy(originalLoading = false) }
                backingCache.rememberOriginal(song, prepared, original)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                DiagnosticLog(getApplication()).record("playback", "Karaoke original: ${e.message}", "WARN")
                mutable.update {
                    it.copy(originalEnabled = false, originalLoading = false,
                        message = if (it.originalEnabled) R.string.karaoke_original_error else it.message)
                }
            } finally { downloaded.delete() }
        }
    }

    fun record(recorder: KaraokeCapture = KaraokeCapture()) {
        if (state.value.stage !in setOf(KaraokeStage.READY, KaraokeStage.REVIEW) || state.value.modelDownload != null) return
        val prepared = backing ?: return
        stopPlayback()
        val previousPreview = draftWork
        recorder.selectOriginal(prepared.original.takeIf { state.value.originalEnabled })
        capture = recorder
        take = null
        id = UUID.randomUUID().toString()
        recordedAt = 0L
        renderedEdit = null
        draftFrame = 0L
        mutable.update {
            it.copy(stage = KaraokeStage.RECORDING, edit = KaraokeEdit(), saved = null, position = 0,
                duration = KaraokeAudio.millis(prepared.frames), draftPlayback = null, message = null)
        }
        work = viewModelScope.launch {
            try {
                previousPreview?.join()
                var startedAt = 0L
                val recorded = recorder.record(getApplication(), prepared, files.resolve("voice.pcm")) { frames, paused ->
                    if (!paused && startedAt == 0L) startedAt = System.currentTimeMillis()
                    mutable.update { it.copy(position = KaraokeAudio.millis(frames), stage = if (paused) KaraokeStage.PAUSED else KaraokeStage.RECORDING) }
                }
                capture = null
                if (recorded.frames == 0L) {
                    mutable.update { it.copy(stage = KaraokeStage.READY, position = 0, message = R.string.karaoke_empty_take) }
                    return@launch
                }
                recordedAt = startedAt
                take = recorded
                val duration = KaraokeAudio.millis(recorded.frames)
                mutable.update {
                    it.copy(stage = KaraokeStage.REVIEW, position = 0, duration = duration,
                        draftPlayback = KaraokeDraftPlayback(0, duration))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                fail(if (take == null) KaraokeStage.READY else KaraokeStage.REVIEW,
                    if (take == null) R.string.karaoke_record_error else R.string.karaoke_mix_error, e)
            } finally { capture = null }
        }
    }

    fun pause() { capture?.pause() }
    fun resume() { capture?.resume() }
    fun stop() { capture?.stop() }

    fun edit(change: (KaraokeEdit) -> KaraokeEdit) {
        val current = state.value
        if (current.stage != KaraokeStage.REVIEW) return
        val recorded = take ?: return
        val edit = change(current.edit)
        if (edit == current.edit) return
        val range = KaraokeAudio.range(recorded, edit)
        val active = draftPlayer
        val position = active?.position ?: draftFrame
        draftFrame = if (active != null && range != KaraokeAudio.range(recorded, current.edit) &&
            position !in range.start until range.end) range.start else position.coerceIn(range.start, range.end)
        active?.edit(edit, draftFrame.takeIf { it != position })
        mutable.update {
            it.copy(edit = edit, saved = null, message = null, draftPlayback = range.preview(draftFrame, active != null))
        }
    }

    fun previewTake() {
        val current = state.value
        if (current.stage != KaraokeStage.REVIEW) return
        if (draftPlayer != null) { stopPlayback(); return }
        val recorded = take ?: return
        val range = KaraokeAudio.range(recorded, current.edit)
        if (range.end <= range.start) return
        stopPlayback()
        val previous = draftWork
        val frame = draftFrame.takeIf { it in range.start until range.end } ?: range.start
        val active = KaraokePreview(recorded, current.edit, frame)
        draftPlayer = active
        updateDraftPlayback(frame, playing = true)
        message(null)
        draftWork = viewModelScope.launch {
            try {
                previous?.join()
                check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                active.play(attributes) { position ->
                    if (active === draftPlayer) updateDraftPlayback(position, playing = true)
                }
                if (active === draftPlayer) stopPlayback()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (active === draftPlayer) {
                    stopPlayback()
                    message(R.string.karaoke_preview_error)
                }
            }
        }
    }

    fun seekDraft(positionMs: Int) {
        if (state.value.stage != KaraokeStage.REVIEW) return
        val recorded = take ?: return
        val frame = KaraokeAudio.range(recorded, state.value.edit).seekFrame(positionMs)
        draftPlayer?.seek(frame)
        updateDraftPlayback(frame, playing = draftPlayer != null)
    }

    private fun updateDraftPlayback(frame: Long, playing: Boolean) {
        val recorded = take ?: return
        val range = KaraokeAudio.range(recorded, state.value.edit)
        draftFrame = frame.coerceIn(range.start, range.end)
        mutable.update { it.copy(draftPlayback = range.preview(draftFrame, playing)) }
    }

    private fun KaraokeAudio.TrimmedRange.preview(frame: Long, playing: Boolean) =
        KaraokeDraftPlayback(positionMs(frame), durationMs, playing)

    fun save(song: Song) {
        val current = state.value
        if (current.stage != KaraokeStage.REVIEW) return
        val recordingId = id ?: return
        val recorded = take ?: return
        stopPlayback()
        mutable.update { it.copy(stage = KaraokeStage.MIXING, message = null) }
        work = viewModelScope.launch {
            try {
                render(recorded, current.edit)
                val result = library.save(recordingId, song, editedWave, recordedAt)
                val recordings = library.list()
                mutable.update {
                    it.copy(stage = KaraokeStage.REVIEW, saved = result, recordings = recordings, message = R.string.karaoke_saved)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(KaraokeStage.REVIEW, R.string.karaoke_mix_error, e) }
        }
    }

    private suspend fun render(recorded: KaraokeTake, edit: KaraokeEdit) {
        if (renderedEdit == edit && editedWave.isFile) return
        renderedEdit = null
        KaraokeAudio.mix(recorded, edit, editedWave)
        renderedEdit = edit
    }

    fun showLibrary(show: Boolean) {
        if (show == state.value.libraryOpen) return
        stopPlayback()
        if (show) pause()
        mutable.update { it.copy(libraryOpen = show) }
        if (show) viewModelScope.launch {
            val recordings = library.list()
            mutable.update { it.copy(recordings = recordings) }
        }
    }

    fun playRecording(recording: KaraokeRecording) {
        val current = state.value.recordingPlayback?.takeIf { it.id == recording.id }
        if (current != null && current.status != KaraokePlaybackStatus.PAUSED) {
            pausePlayback()
            return
        }
        val position = current?.positionMs?.takeIf { it < recording.durationMs } ?: 0
        openPlayback(recording.file, KaraokePlayback(recording.id, recording.durationMs, position))
    }

    fun seekRecording(id: String, positionMs: Int) {
        val playback = state.value.recordingPlayback?.takeIf { it.id == id } ?: return
        val position = positionMs.coerceIn(0, playback.durationMs)
        mutable.update { it.copy(recordingPlayback = playback.copy(positionMs = position)) }
        val active = player
        if (active != null && playerPrepared && seekingPosition == null) seekPlayer(active, position)
    }

    private fun openPlayback(file: File, recording: KaraokePlayback) {
        stopPlayback()
        mutable.update { it.copy(recordingPlayback = recording) }
        try {
            check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            val active = MediaPlayer().also { player = it }
            active.setAudioAttributes(attributes)
            active.setDataSource(file.absolutePath)
            active.setOnPreparedListener { prepared ->
                if (prepared === player) {
                    playerPrepared = true
                    val position = state.value.recordingPlayback?.positionMs ?: 0
                    if (position > 0) seekPlayer(prepared, position) else startPlayer(prepared)
                }
            }
            active.setOnSeekCompleteListener { completed ->
                if (completed === player) {
                    val requested = seekingPosition
                    seekingPosition = null
                    val current = state.value.recordingPlayback
                    if (current != null && requested != null) {
                        if (current.positionMs != requested) seekPlayer(completed, current.positionMs)
                        else if (current.status == KaraokePlaybackStatus.PREPARING) {
                            startPlayer(completed)
                        }
                    }
                }
            }
            active.setOnCompletionListener { completed ->
                if (completed === player) {
                    val current = state.value.recordingPlayback
                    stopPlayback(current?.copy(positionMs = current.durationMs, status = KaraokePlaybackStatus.PAUSED))
                }
            }
            active.setOnErrorListener { current, _, _ ->
                if (current === player) playbackError()
                true
            }
            active.prepareAsync()
        } catch (_: Exception) { playbackError() }
    }

    private fun seekPlayer(active: MediaPlayer, position: Int) {
        seekingPosition = position
        try { active.seekTo(position.toLong(), MediaPlayer.SEEK_CLOSEST) }
        catch (_: IllegalStateException) { playbackError() }
    }

    private fun startPlayer(active: MediaPlayer) {
        try { active.start() }
        catch (_: IllegalStateException) { playbackError(); return }
        val playback = state.value.recordingPlayback ?: return
        mutable.update { it.copy(recordingPlayback = playback.copy(status = KaraokePlaybackStatus.PLAYING), message = null) }
        playbackProgress = viewModelScope.launch {
            while (active === player) {
                delay(200)
                val current = state.value.recordingPlayback ?: break
                if (active === player && seekingPosition == null) {
                    val position = playbackPosition(active, current)
                    if (current.positionMs != position) mutable.update { it.copy(recordingPlayback = current.copy(positionMs = position)) }
                }
            }
        }
    }

    fun pausePlayback() {
        val playback = state.value.recordingPlayback
        val active = player
        val position = if (playback != null && active != null && playerPrepared && seekingPosition == null) {
            playbackPosition(active, playback)
        } else playback?.positionMs
        stopPlayback(playback?.copy(status = KaraokePlaybackStatus.PAUSED, positionMs = position ?: 0))
    }

    private fun playbackPosition(active: MediaPlayer, playback: KaraokePlayback): Int =
        runCatching { active.currentPosition.coerceIn(0, playback.durationMs) }.getOrDefault(playback.positionMs)

    private fun playbackError() {
        val recording = state.value.recordingPlayback
        stopPlayback(recording?.copy(status = KaraokePlaybackStatus.PAUSED))
        message(R.string.karaoke_playback_error)
    }

    private fun stopPlayback(recording: KaraokePlayback? = null) {
        val frame = draftPlayer?.position
        draftPlayer = null
        draftWork?.cancel()
        frame?.let { updateDraftPlayback(it, playing = false) }
        playbackProgress?.cancel()
        playbackProgress = null
        player?.release()
        player = null
        playerPrepared = false
        seekingPosition = null
        audio.abandonAudioFocusRequest(focus)
        mutable.update { it.copy(recordingPlayback = recording) }
    }

    fun export(id: String, target: Uri) {
        viewModelScope.launch {
            try {
                library.export(checkNotNull(library.read(id)), target)
                message(R.string.karaoke_exported)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message(R.string.karaoke_export_error) }
        }
    }

    fun message(text: Int?) { mutable.update { it.copy(message = text) } }

    private fun fail(stage: KaraokeStage, text: Int, e: Exception) {
        DiagnosticLog(getApplication()).record("playback", "Karaoke: ${e.message}", "WARN")
        mutable.update { it.copy(stage = stage, message = text) }
    }

    override fun onCleared() {
        stopPlayback()
        val running = listOfNotNull(work, originalWork, draftWork).filterNot { it.isCompleted }
        if (running.isEmpty()) files.deleteRecursively()
        else {
            val remaining = AtomicInteger(running.size)
            running.forEach { job ->
                job.invokeOnCompletion { if (remaining.decrementAndGet() == 0) files.deleteRecursively() }
                job.cancel()
            }
        }
    }

    companion object {
        private const val DOWNLOAD_SHARE = 45
    }
}
