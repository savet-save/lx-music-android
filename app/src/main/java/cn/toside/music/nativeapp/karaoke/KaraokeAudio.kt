package cn.toside.music.nativeapp.karaoke

import android.content.Context
import android.net.Uri
import cn.toside.music.nativeapp.data.PcmDecoder
import cn.toside.music.nativeapp.data.PcmFormat
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

internal data class KaraokeBacking(val file: File, val frames: Long, val stereo: Boolean)
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
    private const val BLOCK_FRAMES = 2048

    fun millis(frames: Long): Int = (frames * 1000L / RATE).toInt()
    private fun frames(millis: Int): Long = millis.toLong() * RATE / 1000

    suspend fun prepare(context: Context, source: Uri, target: File): KaraokeBacking = withContext(Dispatchers.IO) {
        WavWriter(target).use { writer ->
            var resampler: StereoResampler? = null
            var format: PcmFormat? = null
            PcmDecoder.decode(context, source) { pcm, samples ->
                require(pcm.rate in 8_000..384_000 && pcm.channels in 1..8)
                if (format == null) {
                    format = pcm
                    resampler = StereoResampler(pcm, writer)
                }
                require(format == pcm) { "Audio format changed during decoding" }
                checkNotNull(resampler).write(samples)
            }
            checkNotNull(resampler).flush()
            check(writer.frames > 0) { "No decoded audio" }
            KaraokeBacking(target, writer.frames, checkNotNull(format).channels >= 2)
        }
    }

    suspend fun mix(take: KaraokeTake, edit: KaraokeEdit, target: File): Long = withContext(Dispatchers.IO) {
        val start = frames(edit.trimStartMs).coerceIn(0, take.frames)
        val end = (take.frames - frames(edit.trimEndMs)).coerceIn(start, take.frames)
        require(end > start)
        val delay = frames(edit.voiceDelayMs)
        val musicGain = edit.music.coerceIn(0, 100) / 100.0
        val voiceGain = edit.voice.coerceIn(0, 100) / 100.0
        val noiseGate = if (edit.reduceNoise) VoiceNoiseGate() else null
        val backingBytes = ByteArray(BLOCK_FRAMES * BYTES_PER_FRAME)
        val voiceBytes = ByteArray(BLOCK_FRAMES * 2)
        val backing = ByteBuffer.wrap(backingBytes).order(ByteOrder.LITTLE_ENDIAN)
        val voice = ByteBuffer.wrap(voiceBytes).order(ByteOrder.LITTLE_ENDIAN)
        val pcm = ShortArray(BLOCK_FRAMES * 2)
        WavWriter(target).use { output ->
            RandomAccessFile(take.backing.file, "r").use { backingFile ->
                RandomAccessFile(take.voice, "r").use { voiceFile ->
                    val voiceFrames = voiceFile.length() / 2
                    backingFile.seek(HEADER_BYTES + start * BYTES_PER_FRAME)
                    var position = start
                    while (position < end) {
                        currentCoroutineContext().ensureActive()
                        val count = minOf(BLOCK_FRAMES.toLong(), end - position).toInt()
                        backingFile.readFully(backingBytes, 0, count * BYTES_PER_FRAME)
                        val voiceStart = position - delay
                        val first = voiceStart.coerceIn(0, voiceFrames)
                        val last = (voiceStart + count).coerceIn(first, voiceFrames)
                        voiceBytes.fill(0, 0, count * 2)
                        if (last > first) {
                            voiceFile.seek(first * 2)
                            voiceFile.readFully(voiceBytes, ((first - voiceStart) * 2).toInt(), ((last - first) * 2).toInt())
                        }
                        backing.rewind()
                        voice.rewind()
                        for (frame in 0 until count) {
                            val input = voice.short.toDouble()
                            val vocal = (noiseGate?.sample(input) ?: input) * voiceGain
                            pcm[frame * 2] = sample16(backing.short * musicGain + vocal)
                            pcm[frame * 2 + 1] = sample16(backing.short * musicGain + vocal)
                        }
                        output.write(pcm, count * 2)
                        position += count
                    }
                }
            }
            output.frames
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

    private class StereoResampler(private val format: PcmFormat, private val output: WavWriter) {
        private val reducer = StereoCenterReduction()
        private val chunk = ShortArray(BLOCK_FRAMES * 2)
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
                    val center = if (format.channels >= 2) reducer.center((l + r) * .5) else 0.0
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
            if (used > 0) output.write(chunk, used)
            used = 0
        }
    }

    /** Removes the shared middle-band signal while preserving stereo sides and center bass. */
    internal class StereoCenterReduction {
        private var bass = 0.0
        private var midrange = 0.0
        private val bassWeight = 1 - exp(-2 * PI * 150 / RATE)
        private val midWeight = 1 - exp(-2 * PI * 6000 / RATE)

        fun center(input: Double): Double {
            bass += (input - bass) * bassWeight
            midrange += (input - midrange) * midWeight
            return midrange - bass
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
