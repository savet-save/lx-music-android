package cn.toside.music.nativeapp.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/** Generate real AAC in an MPEG-4 container, without a network or copyrighted recording. */
internal object AacTestAudio {
    fun create(context: Context, bitrate: Int = 128000): File {
        val file = File.createTempFile("aac-fixture-", ".m4a", context.cacheDir)
        val encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val rate = 44100
            encoder.configure(MediaFormat.createAudioFormat("audio/mp4a-latm", rate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            var samples = 0
            var inputDone = false
            var outputDone = false
            var track = -1
            val info = MediaCodec.BufferInfo()
            val deadline = android.os.SystemClock.elapsedRealtime() + 20000
            while (!outputDone) {
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "AAC fixture encoder timed out" }
                if (!inputDone) {
                    val index = encoder.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val input = requireNotNull(encoder.getInputBuffer(index))
                        val count = minOf(1024, rate * 3 - samples)
                        input.clear(); repeat(count) { input.putShort(0) }
                        encoder.queueInputBuffer(index, 0, count * 2, samples * 1000000L / rate,
                            if (count == 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        samples += count; inputDone = count == 0
                    }
                }
                when (val index = encoder.dequeueOutputBuffer(info, 10000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(encoder.outputFormat); muxer.start(); started = true }
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(started)
                                val output = requireNotNull(encoder.getOutputBuffer(index))
                                output.position(info.offset); output.limit(info.offset + info.size)
                                muxer.writeSampleData(track, output, info)
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { encoder.releaseOutputBuffer(index, false) }
                    }
                }
            }
        } finally {
            runCatching { encoder.stop() }; encoder.release()
            if (started) muxer.stop()
            muxer.release()
        }
        return file
    }
}
