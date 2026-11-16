package cn.toside.music.nativeapp.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.nio.ByteOrder

@androidx.annotation.Keep
internal object Mp3Encoder {
    init { System.loadLibrary("lxmp3") }
    external fun open(rate: Int, channels: Int): Long
    external fun encode(handle: Long, pcm: ShortArray, channels: Int): ByteArray?
    external fun flush(handle: Long): ByteArray?
    external fun close(handle: Long)
}

/** Decode with Android's codec, then encode actual MPEG Layer III frames through LAME. */
internal object Mp3Transcoder {
    fun durationMs(context:Context,uri:Uri):Long {
        val extractor=MediaExtractor()
        return try {
            extractor.setDataSource(context,uri,null)
            val format=(0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.first { it.getString(MediaFormat.KEY_MIME)=="audio/mpeg" }
            format.getLong(MediaFormat.KEY_DURATION)/1000
        } finally { extractor.release() }
    }
    suspend fun convert(context: Context, input: Uri, output: OutputStream) = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder = 0L
        try {
            extractor.setDataSource(context, input, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: error("下载内容中没有音频轨道")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            decoder = codec; codec.configure(format, null, null, 0); codec.start()
            var inputDone = false; var outputDone = false; var channels = 0; var encoding = AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()
            var lastProgress = android.os.SystemClock.elapsedRealtime()
            var samples = 0L
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                check(android.os.SystemClock.elapsedRealtime() - lastProgress < 30_000) { "音频解码超时" }
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index))
                        val length = extractor.readSampleData(buffer, 0)
                        if (length < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(index, 0, length, extractor.sampleTime, 0); extractor.advance() }
                        lastProgress = android.os.SystemClock.elapsedRealtime()
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(encoder == 0L) { "音频中途改变格式，无法转码" }
                        val decoded = codec.outputFormat
                        channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(channels in 1..2) { "仅支持单声道或立体声音频" }
                        encoding = if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)) decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        require(encoding in setOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT))
                        encoder = Mp3Encoder.open(decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels)
                        check(encoder != 0L) { "无法初始化 MP3 编码器" }
                    }
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0) {
                                check(encoder != 0L)
                                val buffer = requireNotNull(codec.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                val pcm = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) ShortArray(info.size / 4) { (buffer.float.coerceIn(-1f, 1f) * 32767).toInt().toShort() }
                                    else ShortArray(info.size / 2).also { buffer.asShortBuffer().get(it) }
                                output.write(Mp3Encoder.encode(encoder, pcm, channels) ?: error("MP3 编码失败")); samples += pcm.size
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            lastProgress = android.os.SystemClock.elapsedRealtime()
                        } finally { codec.releaseOutputBuffer(index, false) }
                    }
                }
            }
            check(samples > 0) { "音频内容为空" }
            output.write(Mp3Encoder.flush(encoder) ?: error("无法完成 MP3 文件")); output.flush()
        } finally {
            if (encoder != 0L) Mp3Encoder.close(encoder)
            decoder?.let { runCatching { it.stop() }; it.release() }; extractor.release()
        }
    }
}
