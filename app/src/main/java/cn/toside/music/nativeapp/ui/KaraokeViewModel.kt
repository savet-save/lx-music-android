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
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.karaoke.KaraokeAudio
import cn.toside.music.nativeapp.karaoke.KaraokeBacking
import cn.toside.music.nativeapp.karaoke.KaraokeCapture
import cn.toside.music.nativeapp.karaoke.KaraokeEdit
import cn.toside.music.nativeapp.karaoke.KaraokeLibrary
import cn.toside.music.nativeapp.karaoke.KaraokeRecording
import cn.toside.music.nativeapp.karaoke.KaraokeTake
import cn.toside.music.nativeapp.utils.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

internal enum class KaraokeStage { IDLE, PREPARING, READY, RECORDING, PAUSED, MIXING, REVIEW }

internal data class KaraokeState(
    val stage: KaraokeStage = KaraokeStage.IDLE,
    val duration: Int = 0,
    val position: Int = 0,
    val mono: Boolean = false,
    val edit: KaraokeEdit = KaraokeEdit(),
    val libraryOpen: Boolean = false,
    val recordings: List<KaraokeRecording> = emptyList(),
    val saved: KaraokeRecording? = null,
    val playingPreview: String? = null,
    val message: Int? = null,
)

internal class KaraokeViewModel(application: Application) : AndroidViewModel(application) {
    private val files = application.cacheDir.resolve("karaoke-drafts/${UUID.randomUUID()}")
    private val library = KaraokeLibrary(application)
    private val editedWave = files.resolve("preview.wav")
    private val audio = application.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { if (it < 0) stopPreview() }.setWillPauseWhenDucked(true).build()
    private val mutable = MutableStateFlow(KaraokeState())
    val state = mutable.asStateFlow()
    private var backing: KaraokeBacking? = null
    private var take: KaraokeTake? = null
    private var id: String? = null
    private var renderedEdit: KaraokeEdit? = null
    private var capture: KaraokeCapture? = null
    private var previewPlayer: MediaPlayer? = null
    private var work: Job? = null

    fun prepare(input: suspend (File) -> Uri) {
        if (state.value.stage != KaraokeStage.IDLE) return
        mutable.update { it.copy(stage = KaraokeStage.PREPARING, message = null) }
        work = viewModelScope.launch {
            val downloaded = files.resolve("source.audio")
            try {
                check(files.mkdirs() || files.isDirectory)
                val prepared = KaraokeAudio.prepare(getApplication(), input(downloaded), files.resolve("backing.wav"))
                backing = prepared
                mutable.update { it.copy(stage = KaraokeStage.READY, duration = KaraokeAudio.millis(prepared.frames), mono = !prepared.stereo) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(KaraokeStage.IDLE, R.string.karaoke_prepare_error, e) }
            finally { downloaded.delete() }
        }
    }

    fun record(recorder: KaraokeCapture = KaraokeCapture()) {
        if (state.value.stage !in setOf(KaraokeStage.READY, KaraokeStage.REVIEW)) return
        val prepared = backing ?: return
        stopPreview()
        capture = recorder
        take = null
        id = UUID.randomUUID().toString()
        renderedEdit = null
        mutable.update {
            it.copy(stage = KaraokeStage.RECORDING, edit = KaraokeEdit(), saved = null, position = 0,
                duration = KaraokeAudio.millis(prepared.frames), message = null)
        }
        work = viewModelScope.launch {
            try {
                val recorded = recorder.record(getApplication(), prepared, files.resolve("voice.pcm")) { frames, paused ->
                    mutable.update { it.copy(position = KaraokeAudio.millis(frames), stage = if (paused) KaraokeStage.PAUSED else KaraokeStage.RECORDING) }
                }
                capture = null
                if (recorded.frames == 0L) {
                    mutable.update { it.copy(stage = KaraokeStage.READY, position = 0, message = R.string.karaoke_empty_take) }
                    return@launch
                }
                take = recorded
                mutable.update { it.copy(stage = KaraokeStage.MIXING, position = 0, duration = KaraokeAudio.millis(recorded.frames)) }
                render(recorded, state.value.edit)
                mutable.update { it.copy(stage = KaraokeStage.REVIEW) }
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
        val edit = change(current.edit)
        if (edit == current.edit) return
        stopPreview()
        mutable.update { it.copy(edit = edit, saved = null, message = null) }
    }

    fun previewTake() {
        if (state.value.playingPreview == DRAFT) { stopPreview(); return }
        if (state.value.stage == KaraokeStage.REVIEW) withMix { playPreview(DRAFT, editedWave) }
    }

    fun save(song: Song) {
        if (state.value.stage != KaraokeStage.REVIEW) return
        val recordingId = id ?: return
        withMix {
            val result = library.save(recordingId, song, editedWave)
            val recordings = library.list()
            mutable.update { it.copy(saved = result, recordings = recordings, message = R.string.karaoke_saved) }
        }
    }

    private fun withMix(consume: suspend () -> Unit) {
        val recorded = take ?: return
        val edit = state.value.edit
        stopPreview()
        mutable.update { it.copy(stage = KaraokeStage.MIXING, message = null) }
        work = viewModelScope.launch {
            try {
                render(recorded, edit)
                consume()
                mutable.update { it.copy(stage = KaraokeStage.REVIEW) }
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
        stopPreview()
        if (show) pause()
        mutable.update { it.copy(libraryOpen = show) }
        if (show) viewModelScope.launch {
            val recordings = library.list()
            mutable.update { it.copy(recordings = recordings) }
        }
    }

    fun previewRecording(recording: KaraokeRecording) {
        if (state.value.playingPreview == recording.id) stopPreview() else playPreview(recording.id, recording.file)
    }

    private fun playPreview(key: String, file: File) {
        stopPreview()
        try {
            check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            val player = MediaPlayer().also { previewPlayer = it }
            player.setAudioAttributes(attributes)
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener { if (it === previewPlayer) it.start() }
            player.setOnCompletionListener { if (it === previewPlayer) stopPreview() }
            player.setOnErrorListener { current, _, _ ->
                if (current === previewPlayer) { stopPreview(); message(R.string.karaoke_preview_error) }
                true
            }
            player.prepareAsync()
            mutable.update { it.copy(playingPreview = key) }
        } catch (e: Exception) { stopPreview(); message(R.string.karaoke_preview_error) }
    }

    fun stopPreview() {
        previewPlayer?.release()
        previewPlayer = null
        audio.abandonAudioFocusRequest(focus)
        mutable.update { it.copy(playingPreview = null) }
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
        stopPreview()
        val running = work
        running?.cancel()
        if (running != null && !running.isCompleted) running.invokeOnCompletion { files.deleteRecursively() }
        else files.deleteRecursively()
    }

    companion object { const val DRAFT = "draft" }
}
