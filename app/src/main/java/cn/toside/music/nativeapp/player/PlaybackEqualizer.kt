package cn.toside.music.nativeapp.player

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import cn.toside.music.nativeapp.utils.DiagnosticLog

internal data class EqualizerBand(val frequencyMillihertz: Int, val gainMillibels: Int)

internal data class EqualizerState(
    val bands: List<EqualizerBand>,
    val gainRange: IntRange,
    val enabled: Boolean,
)

/** Keeps one effect on the playback service's private session as tracks and engines change. */
internal class PlaybackEqualizer(
    context: Context,
    audioManager: AudioManager,
    private val settings: PlayerSettings,
) : AutoCloseable {
    private val log = DiagnosticLog(context)
    private var effect: Equalizer? = null
    val audioSessionId: Int
    var state: EqualizerState? = null
        private set

    init {
        var sessionId = AudioManager.AUDIO_SESSION_ID_GENERATE
        applyEffect {
            val id = audioManager.generateAudioSessionId()
            check(id > 0) { "Could not allocate an audio session" }
            val equalizer = Equalizer(0, id).also { effect = it }
            val limits = equalizer.bandLevelRange
            val gainRange = limits[0].toInt()..limits[1].toInt()
            check(gainRange.first < gainRange.last && 0 in gainRange)
            val bands = List(equalizer.numberOfBands.toInt()) { index ->
                val band = index.toShort()
                val frequency = equalizer.getCenterFreq(band)
                val gain = settings.equalizerGain(frequency).coerceIn(gainRange)
                equalizer.setBandLevel(band, gain.toShort())
                EqualizerBand(frequency, gain)
            }
            check(bands.isNotEmpty())
            val enabled = settings.equalizerEnabled
            check(equalizer.setEnabled(enabled) == AudioEffect.SUCCESS)
            state = EqualizerState(bands, gainRange, enabled)
            sessionId = id
        }
        if (state == null) close()
        audioSessionId = sessionId
    }

    fun setEnabled(enabled: Boolean): Boolean {
        val equalizer = effect ?: return false
        val current = state ?: return false
        if (current.enabled == enabled) return true
        return applyEffect {
            check(equalizer.setEnabled(enabled) == AudioEffect.SUCCESS)
            settings.equalizerEnabled = enabled
            state = current.copy(enabled = enabled)
        }
    }

    fun setGain(index: Int, gainMillibels: Int): Boolean {
        val equalizer = effect ?: return false
        val current = state ?: return false
        val band = current.bands.getOrNull(index) ?: return false
        val gain = gainMillibels.coerceIn(current.gainRange)
        if (band.gainMillibels == gain) return true
        return applyEffect {
            equalizer.setBandLevel(index.toShort(), gain.toShort())
            settings.setEqualizerGain(band.frequencyMillihertz, gain)
            val bands = current.bands.toMutableList()
            bands[index] = band.copy(gainMillibels = gain)
            state = current.copy(bands = bands)
        }
    }

    fun reset(): Boolean = state?.bands?.indices?.all { setGain(it, 0) } ?: false

    private inline fun applyEffect(action: () -> Unit): Boolean = try {
        action()
        true
    } catch (e: RuntimeException) {
        log.record("playback", "Equalizer: ${e.javaClass.simpleName}: ${e.localizedMessage}", "WARN")
        false
    }

    override fun close() {
        effect?.let { equalizer -> applyEffect { equalizer.release() } }
        effect = null
        state = null
    }
}
