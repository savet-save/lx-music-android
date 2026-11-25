package cn.toside.music.nativeapp.karaoke

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.player.PlaybackService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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

    suspend fun input(context: Context, song: Song, quality: String, target: File, progress: (Int) -> Unit): Uri {
        if (song.source == "local") return song.uri
        return withContext(Dispatchers.Main.immediate) {
            val service = CompletableDeferred<PlaybackService>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    service.complete((binder as PlaybackService.LocalBinder).service)
                }
                override fun onServiceDisconnected(name: ComponentName) = Unit
                override fun onBindingDied(name: ComponentName) { onNullBinding(name) }
                override fun onNullBinding(name: ComponentName) {
                    service.completeExceptionally(IOException("Cannot connect to the playback service"))
                }
            }
            check(context.bindService(Intent(context, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE))
            try { service.await().karaokeInput(song, quality, target, progress) }
            finally { context.unbindService(connection) }
        }
    }

    suspend fun copy(
        url: String,
        headers: Map<String, String>,
        target: File,
        progress: (Int) -> Unit,
        limitBytes: Long = MAX_BYTES,
    ): Uri {
        val request = Request.Builder().url(url).apply { headers.forEach { (key, value) -> header(key, value) } }.build()
        val call = client.newCall(request)
        val closed = CompletableDeferred<Unit>()
        try {
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                val callback = object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        closed.complete(Unit)
                        continuation.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val downloaded = runCatching {
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
                        }
                        closed.complete(Unit)
                        continuation.resumeWith(downloaded)
                    }
                }
                try { call.enqueue(callback) } catch (e: Exception) {
                    closed.complete(Unit)
                    continuation.resumeWith(Result.failure(e))
                }
            }
        } finally {
            // A cancelled coroutine can resume before OkHttp closes the output file.
            if (!closed.isCompleted) withContext(NonCancellable) { closed.await() }
        }
    }
}
