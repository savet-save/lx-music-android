package cn.toside.music.nativeapp.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Read only a small prefix, without creating a download task or using a disk cache. */
internal class DownloadProbe(private val client: OkHttpClient = probeClient) {
    suspend fun check(url: String, progress: (Int) -> Unit) {
        val request = Request.Builder().url(url)
            .header("Range", "bytes=0-${MAX_BYTES - 1}")
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-store")
            .build()
        val call = client.newCall(request)
        suspendCancellableCoroutine<Unit> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWith(Result.failure(e)) }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(runCatching {
                        response.use {
                            try {
                                check(it.isSuccessful) { "HTTP ${it.code}" }
                                val body = checkNotNull(it.body)
                                val type = body.contentType()
                                check(type?.subtype !in setOf("html", "json", "xml", "problem+json")) { "Not an audio response" }
                                val bytes = ByteArray(MAX_BYTES)
                                var count = 0
                                val stream = body.byteStream()
                                while (count < bytes.size && continuation.isActive) {
                                    val read = stream.read(bytes, count, bytes.size - count)
                                    if (read < 0) break
                                    count += read
                                    progress(count)
                                }
                                check(count > 0) { "Empty response" }
                            } finally {
                                // Do not drain the remaining response, including errors or ignored ranges.
                                call.cancel()
                            }
                        }
                    })
                }
            })
        }
    }

    companion object {
        const val MAX_BYTES = 10 * 1024
        private val probeClient = OkHttpClient.Builder().cache(null)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    }
}
