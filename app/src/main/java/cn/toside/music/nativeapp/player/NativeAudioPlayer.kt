@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package cn.toside.music.nativeapp.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.HttpDataSource
import java.nio.ByteBuffer

/** Native Media3 path for cached streaming and optional hardware audio offload. */
internal class NativeAudioPlayer(context: Context, cache: AudioStreamCache?, private val offload: Boolean,
    private val changed: (NativeAudioPlayer) -> Unit, private val completed: (NativeAudioPlayer) -> Unit,
    private val failed: (NativeAudioPlayer, String) -> Unit, headers: Map<String,String> = emptyMap(),
    retryBudget: PlaybackRetryBudget = PlaybackRetryBudget(), audioSessionId: Int = C.AUDIO_SESSION_ID_UNSET,
    equalizerEnabled: Boolean = false) {
    private val retryPolicy = StreamingRetryPolicy(retryBudget)
    internal val streamRetriesExhausted get() = retryPolicy.exhausted
    internal var failedHttpStatus: Int? = null
        private set
    internal var contentError = false
        private set
    private val levels = PlaybackLevels()
    @Volatile var measureAudioLevels = false
    private val renderers = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink? =
            super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)?.let { sink ->
                LevelAudioSink(sink, levels) { measureAudioLevels }
            }
    }
    internal val engine = ExoPlayer.Builder(context, renderers).setMediaSourceFactory(if (cache == null) DefaultMediaSourceFactory(context)
        else DefaultMediaSourceFactory(cache.dataSource(headers)).setLoadErrorHandlingPolicy(retryPolicy)).build()
    var recorded = false
    init {
        engine.setWakeMode(C.WAKE_MODE_NETWORK)
        engine.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        if (audioSessionId > 0) engine.setAudioSessionId(audioSessionId)
        setEqualizerEnabled(equalizerEnabled)
        engine.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) completed(this@NativeAudioPlayer) else changed(this@NativeAudioPlayer) }
            override fun onIsPlayingChanged(isPlaying: Boolean) { changed(this@NativeAudioPlayer) }
            override fun onPlayerError(error: PlaybackException) {
                failedHttpStatus = generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                contentError = error.errorCode in 3000..4999
                failed(this@NativeAudioPlayer, error.errorCodeName)
            }
        })
    }
    fun setEqualizerEnabled(enabled: Boolean) {
        val mode = if (offload && !enabled) AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
            else AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        val preferences = AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(mode)
            .setIsSpeedChangeSupportRequired(true)
            .build()
        engine.trackSelectionParameters = engine.trackSelectionParameters.buildUpon().setAudioOffloadPreferences(preferences).build()
    }

    fun prepare(uri: Uri, key: String?, position: Int, rate: Float, volume: Float, play: Boolean) {
        engine.volume = volume; engine.playbackParameters = PlaybackParameters(rate, 1f)
        engine.setMediaItem(MediaItem.Builder().setUri(uri).setCustomCacheKey(key).build(), position.toLong())
        engine.prepare(); engine.playWhenReady = play
    }
    val preparing get() = engine.playbackState == Player.STATE_BUFFERING || engine.playbackState == Player.STATE_IDLE
    val isPlaying get() = engine.isPlaying
    val position get() = engine.currentPosition.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    val duration get() = engine.duration.takeIf { it != C.TIME_UNSET }?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0
    fun playbackLevel() = levels.at(engine.currentPosition)
    fun play() { if (engine.playbackState == Player.STATE_ENDED) engine.seekTo(0); engine.play() }
    fun pause() { engine.pause() }
    fun seekTo(value: Int) { engine.seekTo(value.toLong()) }
    fun rate(value: Float) { engine.playbackParameters = PlaybackParameters(value, 1f) }
    fun volume(value: Float) { engine.volume = value }
    fun release() { engine.release() }
}

/** Observe the decoded buffers without changing their contents, volume, or playback path. */
private class LevelAudioSink(sink: AudioSink, private val levels: PlaybackLevels, private val enabled: () -> Boolean) :
    ForwardingAudioSink(sink) {
    private var format: Format? = null
    private var pendingBuffer: ByteBuffer? = null
    private var streamOffsetUs = 0L

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        format = inputFormat.takeIf { it.sampleMimeType == MimeTypes.AUDIO_RAW && it.channelCount > 0 }
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
        streamOffsetUs = outputStreamOffsetUs
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (pendingBuffer !== buffer) {
            pendingBuffer = buffer
            if (enabled()) observe(buffer, presentationTimeUs - streamOffsetUs)
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount).also {
            if (it) pendingBuffer = null
        }
    }

    private fun observe(buffer: ByteBuffer, timeUs: Long) {
        val pcm = format ?: return
        val start = buffer.position()
        val channels = pcm.channelCount
        val encoding = pcm.pcmEncoding
        val sampleBytes = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 4
            C.ENCODING_PCM_DOUBLE -> 8
            else -> return
        }
        val bigEndian = encoding == C.ENCODING_PCM_16BIT_BIG_ENDIAN || encoding == C.ENCODING_PCM_24BIT_BIG_ENDIAN ||
            encoding == C.ENCODING_PCM_32BIT_BIG_ENDIAN
        levels.measure(timeUs, pcm.sampleRate, channels, buffer.remaining() / sampleBytes / channels) { sampleIndex ->
            val offset = start + sampleIndex * sampleBytes
            when (encoding) {
                C.ENCODING_PCM_8BIT -> ((buffer.get(offset).toInt() and 0xff) - 128) / 128f
                C.ENCODING_PCM_FLOAT -> unitSample(buffer.getFloat(offset))
                C.ENCODING_PCM_DOUBLE -> unitSample(buffer.getDouble(offset).toFloat())
                else -> {
                    // The most significant 16 bits suffice for the display level.
                    val high = buffer.get(if (bigEndian) offset else offset + sampleBytes - 1).toInt()
                    val low = buffer.get(if (bigEndian) offset + 1 else offset + sampleBytes - 2).toInt()
                    ((high shl 8) or (low and 0xff)) / 32768f
                }
            }
        }
    }

    private fun unitSample(sample: Float) = if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f

    override fun flush() {
        super.flush()
        pendingBuffer = null
        levels.clear()
    }

    override fun reset() {
        super.reset()
        pendingBuffer = null
        format = null
        levels.clear()
    }
}
