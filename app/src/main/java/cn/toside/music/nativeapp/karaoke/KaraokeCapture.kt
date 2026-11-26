package cn.toside.music.nativeapp.karaoke

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal interface KaraokeMicrophone : AutoCloseable {
    fun start()
    fun stop()
    fun read(samples: ShortArray): Int
}

private class AndroidKaraokeMicrophone private constructor(private val recorder: AudioRecord) : KaraokeMicrophone {
    override fun start() {
        recorder.startRecording()
        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
    }

    override fun stop() { recorder.stop() }

    override fun read(samples: ShortArray) = recorder.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)

    override fun close() {
        runCatching { recorder.stop() }
        recorder.release()
    }

    companion object {
        @SuppressLint("MissingPermission") // The permission is checked at construction time.
        fun open(context: Context): KaraokeMicrophone {
            check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            val rate = KaraokeAudio.RATE
            val buffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(buffer > 0) { "Microphone input is unavailable" }
            val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer * 2)
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                error("Microphone input could not be initialized")
            }
            return AndroidKaraokeMicrophone(recorder)
        }
    }
}

/** Uses the accompaniment's played frames as the timeline for every microphone segment. */
internal class KaraokeCapture(private val openMicrophone: (Context) -> KaraokeMicrophone = AndroidKaraokeMicrophone::open) {
    private enum class Command { RECORD, PAUSE, STOP }
    private val command = MutableStateFlow(Command.RECORD)
    @Volatile private var original: File? = null

    fun pause() { command.compareAndSet(Command.RECORD, Command.PAUSE) }
    fun resume() { command.compareAndSet(Command.PAUSE, Command.RECORD) }
    fun stop() { command.value = Command.STOP }
    fun selectOriginal(file: File?) { original = file }

    suspend fun record(context: Context, backing: KaraokeBacking, voice: File, progress: (Long, Boolean) -> Unit): KaraokeTake =
        withContext(Dispatchers.IO) {
            val manager = context.getSystemService(AudioManager::class.java)
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change -> if (change < 0) pause() }
                .setWillPauseWhenDucked(true).build()
            val track = karaokeOutputTrack(attributes)
            try {
                check(track.state == AudioTrack.STATE_INITIALIZED)
                openMicrophone(context).use { recorder ->
                    KaraokeMonitor(backing.file).use { song ->
                        RandomAccessFile(voice, "rw").use { recording ->
                            recording.setLength(0)
                            capture(track, recorder, manager, focus, backing.frames, song, recording, progress)
                        }
                    }
                }.let { KaraokeTake(backing, voice, it) }
            } finally {
                runCatching { track.pause() }
                track.release()
                manager.abandonAudioFocusRequest(focus)
            }
        }

    private suspend fun capture(
        track: AudioTrack,
        recorder: KaraokeMicrophone,
        manager: AudioManager,
        focus: AudioFocusRequest,
        total: Long,
        song: KaraokeMonitor,
        voice: RandomAccessFile,
        progress: (Long, Boolean) -> Unit,
    ): Long {
        val backingBuffer = ByteArray(8192)
        val samples = ShortArray(1536)
        val encoded = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        var limit = 0
        var offset = 0
        var writtenBytes = 0L
        var bufferOriginal: File? = null
        var running = false
        var head = 0L
        var voicePosition = 0L
        var reported = -KaraokeAudio.RATE.toLong()
        fun feedTrack() {
            val selected = original
            if (offset == limit || selected != bufferOriginal) {
                limit = song.read(backingBuffer, writtenBytes, selected)
                offset = 0
                bufferOriginal = selected
            }
            if (offset < limit) {
                val written = track.write(backingBuffer, offset, limit - offset, AudioTrack.WRITE_NON_BLOCKING)
                check(written >= 0) { "Audio output stopped ($written)" }
                offset += written
                writtenBytes += written
            }
        }

        fun readMicrophone() {
            val count = recorder.read(samples)
            check(count >= 0) { "Microphone input stopped ($count)" }
            val accepted = minOf(count.toLong(), total - voicePosition).toInt()
            if (accepted > 0) {
                encoded.clear()
                encoded.asShortBuffer().put(samples, 0, accepted)
                voice.write(encoded.array(), 0, accepted * 2)
                voicePosition += accepted
            }
        }

        while (head < total && command.value != Command.STOP) {
            currentCoroutineContext().ensureActive()
            if (command.value == Command.PAUSE) {
                if (running) {
                    track.pause()
                    head = track.playbackHeadPosition.toLong().coerceIn(head, total)
                    readMicrophone()
                    recorder.stop()
                    voice.setLength(head * 2)
                    manager.abandonAudioFocusRequest(focus)
                    running = false
                }
                progress(head, true)
                if (head < total) command.first { it != Command.PAUSE }
                continue
            }
            if (!running) {
                check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio focus unavailable" }
                voice.seek(head * 2)
                voicePosition = head
                feedTrack()
                recorder.start()
                track.play()
                running = true
                reported = head
                progress(head, false)
            }
            feedTrack()
            readMicrophone()
            head = track.playbackHeadPosition.toLong().coerceIn(head, total)
            if (head - reported >= KaraokeAudio.RATE / 20 || head == total) {
                reported = head
                progress(head, false)
            }
            if (head < total) delay(10)
        }
        if (running) {
            track.pause()
            head = track.playbackHeadPosition.toLong().coerceIn(head, total)
            readMicrophone()
            recorder.stop()
        }
        voice.setLength(head * 2)
        return head
    }
}

/** Both WAV files use the same PCM layout and the same playback position. */
internal class KaraokeMonitor(file: File) : AutoCloseable {
    private val backing = RandomAccessFile(file, "r")
    private var originalReader: RandomAccessFile? = null

    fun read(buffer: ByteArray, writtenBytes: Long, original: File?): Int {
        val input = if (original == null) backing else {
            originalReader ?: RandomAccessFile(original, "r").also { originalReader = it }
        }
        input.seek(KaraokeAudio.HEADER_BYTES + writtenBytes)
        return input.read(buffer).coerceAtLeast(0)
    }

    override fun close() {
        try { originalReader?.close() }
        finally { backing.close() }
    }
}
