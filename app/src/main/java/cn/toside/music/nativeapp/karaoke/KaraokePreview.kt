package cn.toside.music.nativeapp.karaoke

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal fun karaokeOutputTrack(attributes: AudioAttributes): AudioTrack {
    val rate = KaraokeAudio.RATE
    val channels = AudioFormat.CHANNEL_OUT_STEREO
    val encoding = AudioFormat.ENCODING_PCM_16BIT
    val minimum = AudioTrack.getMinBufferSize(rate, channels, encoding)
    check(minimum > 0) { "Audio output is unavailable" }
    val format = AudioFormat.Builder().setSampleRate(rate).setChannelMask(channels).setEncoding(encoding).build()
    return AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
        .setBufferSizeInBytes(maxOf(minimum, rate / 10 * KaraokeAudio.BYTES_PER_FRAME))
        .setTransferMode(AudioTrack.MODE_STREAM).build()
}

/** Streams the current draft mix from the original recording rather than rendering an intermediate file. */
internal class KaraokePreview(private val take: KaraokeTake, edit: KaraokeEdit, firstFrame: Long) {
    private class Seek(val frame: Long)
    private data class Request(val edit: KaraokeEdit, val seek: Seek)
    private data class Cursor(val seek: Seek, val frame: Long)

    @Volatile private var request = Request(edit, Seek(firstFrame))
    @Volatile private var cursor = Cursor(request.seek, firstFrame)

    val position: Long
        get() {
            val seek = request.seek
            val played = cursor
            return if (played.seek === seek) played.frame else seek.frame
        }

    fun seek(frame: Long) { request = request.copy(seek = Seek(frame)) }

    fun edit(edit: KaraokeEdit, seekTo: Long? = null) {
        request = Request(edit, seekTo?.let(::Seek) ?: request.seek)
    }

    suspend fun play(attributes: AudioAttributes, report: (Long) -> Unit) = withContext(Dispatchers.IO) {
        val track = karaokeOutputTrack(attributes)
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED)
            KaraokeAudio.withMixer(take) { mixer -> stream(track, mixer, report) }
        } finally {
            track.release()
        }
    }

    private suspend fun stream(track: AudioTrack, mixer: KaraokeAudio.Mixer, report: (Long) -> Unit) {
        val samples = ShortArray(KaraokeAudio.BLOCK_FRAMES * 2)
        val bufferFrames = track.bufferSizeInFrames
        var active = request
        var range = KaraokeAudio.range(take, active.edit)
        var origin = active.seek.frame.coerceIn(range.start, range.end)
        var written = origin
        var offset = 0
        var limit = 0
        var reported = origin
        track.play()
        while (true) {
            val next = request
            var played = (origin + track.playbackHeadPosition.toLong()).coerceIn(origin, written)
            if (next !== active) {
                val nextRange = KaraokeAudio.range(take, next.edit)
                val seeking = next.seek !== active.seek
                val changedRange = nextRange != range
                if (seeking || changedRange && written > minOf(range.end, nextRange.end)) {
                    track.pause()
                    track.flush()
                    origin = (if (seeking) next.seek.frame else played).coerceIn(nextRange.start, nextRange.end)
                    played = origin
                    written = origin
                    reported = origin
                    track.play()
                }
                offset = 0
                limit = 0
                active = next
                range = nextRange
            }
            cursor = Cursor(active.seek, played.coerceIn(range.start, range.end))
            if (played >= range.end) {
                // A seek or edit issued during this iteration can replace the pending end of playback.
                if (request === active) return
                continue
            }
            if (played - reported >= KaraokeAudio.RATE / 10) {
                reported = played
                withContext(Dispatchers.Main.immediate) { report(position) }
            }
            if (offset == limit) {
                // Pad a short tail enough for streaming startup, including after seeking close to the end.
                val boundary = if (written < range.end) range.end else origin + bufferFrames
                val count = (boundary - written).coerceIn(0, KaraokeAudio.BLOCK_FRAMES.toLong()).toInt()
                if (count > 0) {
                    if (written < range.end) mixer.read(written, count, active.edit, samples)
                    else samples.fill(0, 0, count * 2)
                }
                offset = 0
                limit = count * 2
            }
            if (offset < limit) {
                val accepted = track.write(samples, offset, limit - offset, AudioTrack.WRITE_NON_BLOCKING)
                check(accepted >= 0) { "Preview audio output stopped ($accepted)" }
                offset += accepted
                written += accepted / 2
            }
            delay(10)
        }
    }
}
