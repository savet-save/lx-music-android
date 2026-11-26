package cn.toside.music.nativeapp.karaoke

import android.content.Context
import android.net.Uri
import cn.toside.music.nativeapp.data.PcmDecoder
import cn.toside.music.nativeapp.data.PcmFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

internal data class KaraokeBacking(
    val file: File,
    val frames: Long,
    val stereo: Boolean,
    val original: File? = null,
    val cacheKey: String? = null,
    val separation: MdxParameters? = null,
) {
    val separated get() = separation != null
}
internal data class KaraokeTake(val backing: KaraokeBacking, val voice: File, val frames: Long)
internal data class KaraokeEdit(
    val music: Int = 70,
    val voice: Int = 90,
    val trimStartMs: Int = 0,
    val trimEndMs: Int = 0,
    val voiceDelayMs: Int = 0,
    val reduceNoise: Boolean = false,
)

internal object KaraokeAudio {
    const val RATE = 48_000
    const val HEADER_BYTES = 44L
    const val BYTES_PER_FRAME = 4
    const val MAX_FRAMES = RATE * 60L * 30
    const val BLOCK_FRAMES = 2048

    fun millis(frames: Long): Int = (frames * 1000L / RATE).toInt()
    private fun frames(millis: Int): Long = millis.toLong() * RATE / 1000

    data class TrimmedRange(val start: Long, val end: Long) {
        val durationMs get() = millis(end - start)
        fun positionMs(frame: Long) = millis(frame.coerceIn(start, end) - start)
        fun seekFrame(positionMs: Int) = if (positionMs >= durationMs) end else start + frames(positionMs.coerceAtLeast(0))
    }

    fun range(take: KaraokeTake, edit: KaraokeEdit): TrimmedRange {
        val start = frames(edit.trimStartMs).coerceIn(0, take.frames)
        return TrimmedRange(start, (take.frames - frames(edit.trimEndMs)).coerceIn(start, take.frames))
    }

    suspend fun prepare(
        context: Context,
        source: Uri,
        target: File,
        songDurationMs: Long,
        original: File? = null,
        model: File? = null,
        parameters: MdxParameters = MdxParameters.Default,
        onSeparationError: (Exception) -> Unit = {},
        progress: (Int) -> Unit,
    ): KaraokeBacking {
        val tryModel = model != null && original != null
        val backing = render(context, source, target, songDurationMs, original, removeCenter = true) {
            progress(if (tryModel) it * 45 / 100 else it)
        }
        if (model == null || original == null) return backing
        return try {
            MdxSeparation(context).run(model, backing, parameters) { progress(45 + it * 55 / 100) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onSeparationError(e)
            backing
        }
    }

    suspend fun prepareOriginal(context: Context, source: Uri, target: File, frames: Long): File {
        val original = render(context, source, target, 0, null, removeCenter = false) {}
        check(original.frames == frames) { "Original audio does not match the accompaniment" }
        return target
    }

    private suspend fun render(
        context: Context,
        source: Uri,
        target: File,
        songDurationMs: Long,
        original: File?,
        removeCenter: Boolean,
        progress: (Int) -> Unit,
    ): KaraokeBacking = withContext(Dispatchers.IO) {
        WavWriter(target).use { writer ->
            original?.let(::WavWriter).use { dry ->
                var resampler: StereoResampler? = null
                var format: PcmFormat? = null
                var durationMs = songDurationMs
                var reported = 0
                PcmDecoder.decode(context, source, onDurationUs = { if (it >= 1000) durationMs = it / 1000 }) { pcm, samples ->
                    require(pcm.rate in 8_000..384_000 && pcm.channels in 1..8)
                    if (format == null) {
                        format = pcm
                        resampler = StereoResampler(pcm, writer, dry, removeCenter)
                    }
                    require(format == pcm) { "Audio format changed during decoding" }
                    checkNotNull(resampler).write(samples)
                    if (durationMs > 0) {
                        val percent = (millis(writer.frames) * 100L / durationMs).coerceAtMost(99).toInt()
                        if (percent > reported) {
                            reported = percent
                            progress(percent)
                        }
                    }
                }
                checkNotNull(resampler).flush()
                check(writer.frames > 0) { "No decoded audio" }
                val stereo = checkNotNull(format).channels >= 2
                KaraokeBacking(target, writer.frames, stereo, original)
            }
        }
    }

    suspend fun mix(take: KaraokeTake, edit: KaraokeEdit, target: File): Long = withContext(Dispatchers.IO) {
        val range = range(take, edit)
        require(range.end > range.start)
        val pcm = ShortArray(BLOCK_FRAMES * 2)
        WavWriter(target).use { output ->
            withMixer(take) { mixer ->
                var position = range.start
                while (position < range.end) {
                    currentCoroutineContext().ensureActive()
                    val count = minOf(BLOCK_FRAMES.toLong(), range.end - position).toInt()
                    mixer.read(position, count, edit, pcm)
                    output.write(pcm, count * 2)
                    position += count
                }
            }
            output.frames
        }
    }

    internal inline fun <T> withMixer(take: KaraokeTake, block: (Mixer) -> T): T =
        RandomAccessFile(take.backing.file, "r").use { backing ->
            RandomAccessFile(take.voice, "r").use { voice -> block(Mixer(backing, voice)) }
        }

    internal class Mixer(private val backingFile: RandomAccessFile, private val voiceFile: RandomAccessFile) {
        private val voiceFrames = voiceFile.length() / 2
        private val backingBytes = ByteArray(BLOCK_FRAMES * BYTES_PER_FRAME)
        private val voiceBytes = ByteArray(BLOCK_FRAMES * 2)
        private val backing = ByteBuffer.wrap(backingBytes).order(ByteOrder.LITTLE_ENDIAN)
        private val voice = ByteBuffer.wrap(voiceBytes).order(ByteOrder.LITTLE_ENDIAN)
        private var gate: VoiceNoiseGate? = null
        private var nextVoiceFrame = 0L

        fun read(position: Long, count: Int, edit: KaraokeEdit, pcm: ShortArray) {
            val musicGain = edit.music.coerceIn(0, 100) / 100.0
            val voiceGain = edit.voice.coerceIn(0, 100) / 100.0
            backingFile.seek(HEADER_BYTES + position * BYTES_PER_FRAME)
            backingFile.readFully(backingBytes, 0, count * BYTES_PER_FRAME)
            val voiceStart = position - frames(edit.voiceDelayMs)
            val first = voiceStart.coerceIn(0, voiceFrames)
            val last = (voiceStart + count).coerceIn(first, voiceFrames)
            voiceBytes.fill(0, 0, count * 2)
            if (last > first) {
                voiceFile.seek(first * 2)
                voiceFile.readFully(voiceBytes, ((first - voiceStart) * 2).toInt(), ((last - first) * 2).toInt())
            }
            gate = if (edit.reduceNoise) gate?.takeIf { nextVoiceFrame == voiceStart } ?: VoiceNoiseGate() else null
            nextVoiceFrame = voiceStart + count
            backing.rewind()
            voice.rewind()
            for (frame in 0 until count) {
                val input = voice.short.toDouble()
                val vocal = (gate?.sample(input) ?: input) * voiceGain
                pcm[frame * 2] = sample16(backing.short * musicGain + vocal)
                pcm[frame * 2 + 1] = sample16(backing.short * musicGain + vocal)
            }
        }
    }

    internal fun sample16(value: Double): Short = value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    internal class WavWriter(file: File) : AutoCloseable {
        private val output = RandomAccessFile(file, "rw")
        private var encoded = ByteBuffer.allocate(BLOCK_FRAMES * BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN)
        var frames = 0L
            private set

        init { output.setLength(HEADER_BYTES); output.seek(HEADER_BYTES) }

        fun write(samples: ShortArray, count: Int) {
            require(count % 2 == 0 && frames + count / 2 <= MAX_FRAMES) { "Karaoke audio exceeds 30 minutes" }
            if (encoded.capacity() < count * 2) encoded = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
            encoded.clear()
            encoded.asShortBuffer().put(samples, 0, count)
            output.write(encoded.array(), 0, count * 2)
            frames += count / 2
        }

        override fun close() {
            output.use { file ->
                val bytes = (frames * BYTES_PER_FRAME).toInt()
                val header = ByteBuffer.allocate(HEADER_BYTES.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                    .put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + bytes)
                    .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(2)
                    .putInt(RATE).putInt(RATE * BYTES_PER_FRAME).putShort(BYTES_PER_FRAME.toShort()).putShort(16)
                    .put("data".toByteArray(Charsets.US_ASCII)).putInt(bytes)
                file.seek(0)
                file.write(header.array())
            }
        }
    }

    private class StereoResampler(
        private val format: PcmFormat,
        private val output: WavWriter,
        private val original: WavWriter?,
        removeCenter: Boolean,
    ) {
        private val reducer = if (removeCenter && format.channels >= 2) StereoCenterReduction() else null
        private val chunk = ShortArray(BLOCK_FRAMES * 2)
        private val originalChunk = if (original != null) ShortArray(chunk.size) else null
        private var used = 0
        private var inputFrame = 0L
        private var nextOutput = 0L
        private var left = 0.0
        private var right = 0.0

        fun write(samples: ShortArray) {
            require(samples.size % format.channels == 0)
            for (index in samples.indices step format.channels) {
                val nextLeft = samples[index].toDouble()
                val nextRight = if (format.channels >= 2) samples[index + 1].toDouble() else nextLeft
                if (inputFrame == 0L) { left = nextLeft; right = nextRight }
                val clock = inputFrame * RATE
                while (nextOutput <= clock) {
                    val fraction = 1.0 - (clock - nextOutput).toDouble() / RATE
                    val l = left + (nextLeft - left) * fraction
                    val r = right + (nextRight - right) * fraction
                    val center = reducer?.center((l + r) * .5) ?: 0.0
                    originalChunk?.let {
                        it[used] = sample16(l)
                        it[used + 1] = sample16(r)
                    }
                    chunk[used++] = sample16(l - center)
                    chunk[used++] = sample16(r - center)
                    if (used == chunk.size) flush()
                    nextOutput += format.rate
                }
                left = nextLeft
                right = nextRight
                inputFrame++
            }
        }

        fun flush() {
            if (used > 0) {
                output.write(chunk, used)
                originalChunk?.let { checkNotNull(original).write(it, used) }
            }
            used = 0
        }
    }

    /** Removes the shared vocal band while preserving sub-bass and extreme-high stereo content. */
    internal class StereoCenterReduction {
        private var bass = 0.0
        private var fullBand = 0.0
        private val bassWeight = 1 - exp(-2 * PI * 80 / RATE)
        private val fullBandWeight = 1 - exp(-2 * PI * 12000 / RATE)

        fun center(input: Double): Double {
            bass += (input - bass) * bassWeight
            fullBand += (input - fullBand) * fullBandWeight
            return fullBand - bass
        }
    }

    private class VoiceNoiseGate {
        private var low = 0.0
        private var envelope = 0.0
        private var gain = 0.0
        private val lowWeight = 1 - exp(-2 * PI * 80 / RATE)

        fun sample(input: Double): Double {
            low += (input - low) * lowWeight
            val filtered = input - low
            envelope = maxOf(abs(filtered), envelope * .998)
            val target = if (envelope > 320) 1.0 else .13
            gain += (target - gain) * if (target > gain) .08 else .001
            return filtered * gain
        }
    }
}
