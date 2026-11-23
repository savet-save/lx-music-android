package cn.toside.music.nativeapp.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

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
        return audioDurationMs(context, uri, "audio/mpeg")
    }
    fun sourceDurationMs(context: Context, uri: Uri): Long = audioDurationMs(context, uri, null)
    private fun audioDurationMs(context: Context, uri: Uri, mime: String?): Long {
        val extractor=MediaExtractor()
        return try {
            extractor.setDataSource(context,uri,null)
            val format=(0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.first {
                if (mime == null) it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true else it.getString(MediaFormat.KEY_MIME) == mime
            }
            format.getLong(MediaFormat.KEY_DURATION)/1000
        } finally { extractor.release() }
    }
    suspend fun convert(context: Context, input: Uri, output: OutputStream) = withContext(Dispatchers.IO) {
        var encoder = 0L
        var format: PcmFormat? = null
        try {
            PcmDecoder.decode(context, input) { pcm, samples ->
                if (format == null) {
                    require(pcm.channels in 1..2) { "仅支持单声道或立体声音频" }
                    encoder = Mp3Encoder.open(pcm.rate, pcm.channels)
                    check(encoder != 0L) { "无法初始化 MP3 编码器" }
                    format = pcm
                }
                check(format == pcm) { "音频中途改变格式，无法转码" }
                output.write(Mp3Encoder.encode(encoder, samples, pcm.channels) ?: error("MP3 编码失败"))
            }
            check(encoder != 0L) { "音频内容为空" }
            output.write(Mp3Encoder.flush(encoder) ?: error("无法完成 MP3 文件")); output.flush()
        } finally {
            if (encoder != 0L) Mp3Encoder.close(encoder)
        }
    }
}
