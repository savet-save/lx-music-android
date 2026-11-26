package cn.toside.music.nativeapp.player

import android.content.Context
import android.net.Uri
import cn.toside.music.nativeapp.data.PcmDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/** Bounded series of short RMS windows indexed by media time. */
internal class PlaybackLevels {
    private val times = LongArray(128) { -1 }
    private val powers = FloatArray(times.size)
    private val samples = IntArray(times.size)

    @Synchronized
    fun add(slot: Long, power: Float, count: Int) {
        if (slot < 0 || count == 0) return
        val index = (slot % times.size).toInt()
        if (times[index] != slot) {
            times[index] = slot
            powers[index] = 0f
            samples[index] = 0
        }
        powers[index] += power
        samples[index] += count
    }

    @Synchronized
    fun at(positionMs: Long): Float {
        val slot = positionMs.coerceAtLeast(0) / INTERVAL_MS
        val index = (slot % times.size).toInt()
        if (times[index] == slot) return sqrt(powers[index] / samples[index])
        val prior = (index + times.lastIndex) % times.size
        return if (slot > 0 && times[prior] == slot - 1) sqrt(powers[prior] / samples[prior]) else 0f
    }

    @Synchronized
    fun clear() { times.fill(-1) }

    companion object {
        const val INTERVAL_MS = 40
    }
}

internal inline fun PlaybackLevels.measure(
    timeUs: Long, rate: Int, channels: Int, frames: Int, sample: (Int) -> Float,
) {
    if (rate <= 0 || channels <= 0 || frames == 0) return
    val stride = (rate / 12_000).coerceAtLeast(1)
    val intervalUs = PlaybackLevels.INTERVAL_MS * 1000L
    var slot = Math.floorDiv(timeUs, intervalUs)
    var power = 0f
    var count = 0
    for (frame in 0 until frames step stride) {
        val frameSlot = Math.floorDiv(timeUs + frame * 1_000_000L / rate, intervalUs)
        if (frameSlot != slot) {
            add(slot, power, count)
            slot = frameSlot
            power = 0f
            count = 0
        }
        for (channel in 0 until channels) {
            val value = sample(frame * channels + channel)
            power += value * value
        }
        count += channels
    }
    add(slot, power, count)
}

/** MediaPlayer does not expose PCM. Analyze a short lookahead from the same local file. */
internal class LocalPlaybackLevels(private val context: Context, private val scope: CoroutineScope) : AutoCloseable {
    private class Analysis(val uri: Uri, positionMs: Int) {
        @Volatile var positionMs = positionMs
        val levels = PlaybackLevels()
    }

    private var analysis: Analysis? = null
    private var job: Job? = null

    fun at(uri: Uri, positionMs: Int): Float {
        val current = analysis?.takeIf { it.uri == uri } ?: start(uri, positionMs)
        current.positionMs = positionMs
        return current.levels.at(positionMs.toLong())
    }

    private fun start(uri: Uri, positionMs: Int): Analysis {
        close()
        val next = Analysis(uri, positionMs)
        analysis = next
        job = scope.launch {
            try {
                PcmDecoder.decodeBatches(context, uri, startUs = positionMs * 1000L) { format, timeUs, samples ->
                    while (timeUs > next.positionMs * 1000L + 1_200_000) delay(80)
                    val frames = samples.size / format.channels
                    val endUs = timeUs + frames * 1_000_000L / format.rate
                    if (endUs >= (next.positionMs - PlaybackLevels.INTERVAL_MS) * 1000L) {
                        next.levels.measure(timeUs, format.rate, format.channels, frames) { samples[it] / 32768f }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Reading levels is optional and must not interrupt local playback.
            }
        }
        return next
    }

    override fun close() {
        job?.cancel()
        job = null
        analysis = null
    }
}
