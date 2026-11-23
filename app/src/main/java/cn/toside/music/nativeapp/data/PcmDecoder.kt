package cn.toside.music.nativeapp.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal data class PcmFormat(val rate: Int, val channels: Int)

/** Decodes the first audio track into bounded batches of interleaved PCM16. */
internal object PcmDecoder {
    suspend fun decode(
        context: Context,
        uri: Uri,
        onDurationUs: ((Long) -> Unit)? = null,
        consume: (PcmFormat, ShortArray) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (onDurationUs != null && format.containsKey(MediaFormat.KEY_DURATION)) {
                onDurationUs(format.getLong(MediaFormat.KEY_DURATION))
            }
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                val pcm = format.pcmFormat()
                val encoding = format.encoding()
                val input = java.nio.ByteBuffer.allocate(256 * 1024).order(ByteOrder.LITTLE_ENDIAN)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    input.clear()
                    val size = extractor.readSampleData(input, 0)
                    if (size < 0) break
                    input.limit(size)
                    consume(pcm, input.samples(encoding))
                    extractor.advance()
                }
                return@withContext
            }
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val codec = MediaCodec.createDecoderByType(mime).also { decoder = it }
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var decodedFormat: PcmFormat? = null
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var lastProgress = SystemClock.elapsedRealtime()
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                check(SystemClock.elapsedRealtime() - lastProgress < 30_000) { "Audio decoder stalled" }
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val length = extractor.readSampleData(requireNotNull(codec.getInputBuffer(index)), 0)
                        inputDone = length < 0
                        codec.queueInputBuffer(index, 0, length.coerceAtLeast(0), if (inputDone) 0 else extractor.sampleTime,
                            if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (!inputDone) extractor.advance()
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    decodedFormat = codec.outputFormat.pcmFormat()
                    encoding = codec.outputFormat.encoding()
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val pcm = requireNotNull(decodedFormat)
                            val buffer = requireNotNull(codec.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            consume(pcm, buffer.samples(encoding))
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        lastProgress = SystemClock.elapsedRealtime()
                    } finally { codec.releaseOutputBuffer(index, false) }
                }
            }
        } finally {
            try {
                decoder?.let { runCatching { it.stop() }; it.release() }
            } finally { extractor.release() }
        }
    }

    private fun MediaFormat.pcmFormat() = PcmFormat(getInteger(MediaFormat.KEY_SAMPLE_RATE), getInteger(MediaFormat.KEY_CHANNEL_COUNT))

    private fun MediaFormat.encoding(): Int = if (containsKey(MediaFormat.KEY_PCM_ENCODING)) getInteger(MediaFormat.KEY_PCM_ENCODING)
        else AudioFormat.ENCODING_PCM_16BIT

    private fun java.nio.ByteBuffer.samples(encoding: Int): ShortArray = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> ShortArray(remaining() / 2).also { asShortBuffer().get(it) }
        AudioFormat.ENCODING_PCM_FLOAT -> ShortArray(remaining() / 4) { (float.coerceIn(-1f, 1f) * 32767).roundToInt().toShort() }
        AudioFormat.ENCODING_PCM_8BIT -> ShortArray(remaining()) { (((get().toInt() and 0xff) - 128) shl 8).toShort() }
        else -> {
            // PCM24 and PCM32 can also be exposed directly by an extractor on newer Android versions.
            val bytes = when (encoding) {
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                AudioFormat.ENCODING_PCM_32BIT -> 4
                else -> error("Unsupported PCM encoding: $encoding")
            }
            ShortArray(remaining() / bytes) {
                repeat(bytes - 2) { get() }
                short
            }
        }
    }
}
