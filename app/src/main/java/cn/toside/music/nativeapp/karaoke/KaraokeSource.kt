package cn.toside.music.nativeapp.karaoke

import android.net.Uri
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object KaraokeSource {
    const val MAX_BYTES = 256L * 1024 * 1024
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.MINUTES).build()

    suspend fun copy(
        url: String,
        headers: Map<String, String>,
        target: File,
        progress: (Int) -> Unit,
        limitBytes: Long = MAX_BYTES,
    ): Uri = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).apply { headers.forEach { (key, value) -> header(key, value) } }.build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWith(Result.failure(e)) }

            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(runCatching {
                    response.use { result ->
                        check(result.isSuccessful) { "HTTP ${result.code}" }
                        val body = requireNotNull(result.body)
                        val length = body.contentLength()
                        require(length <= limitBytes)
                        check(continuation.isActive)
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var bytes = 0L
                            val input = body.byteStream()
                            while (continuation.isActive) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytes += count
                                require(bytes <= limitBytes)
                                output.write(buffer, 0, count)
                                if (length > 0) progress((bytes * 100 / length).coerceAtMost(99).toInt())
                            }
                            check(bytes > 0 && continuation.isActive)
                        }
                    }
                    Uri.fromFile(target)
                })
            }
        })
    }
}
