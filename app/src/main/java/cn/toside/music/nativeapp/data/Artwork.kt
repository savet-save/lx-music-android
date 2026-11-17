package cn.toside.music.nativeapp.data

import cn.toside.music.nativeapp.online.OnlineArtwork
import cn.toside.music.nativeapp.utils.readBounded

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.security.MessageDigest
import android.util.AtomicFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

internal class ArtworkSizeException(val limit: Int, val actual: Long? = null) : IOException("Image exceeds $limit bytes")
internal class ArtworkHttpException(val status: Int) : IOException("Image HTTP $status")
internal class ArtworkDecodeException : IOException("Image decoding failed")

class Artwork(private val context: Context) {
    suspend fun load(song: Song, strict: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        if (song.source != "local") {
            val info = JSONObject(song.sourceInfo ?: return@withContext null)
            val stored = info.optString("img")
            var failure: Exception? = null
            val bitmap = try { loadUrl(stored, strict) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e; null }
            if (bitmap != null) return@withContext bitmap
            val resolved = OnlineArtwork.url(song, info)
            val alternative = if (resolved != stored) loadUrl(resolved, strict) else null
            if (alternative != null) return@withContext alternative
            if (strict && failure != null) throw failure
            return@withContext null
        }
        val bytes = runCatching {
            val retriever = MediaMetadataRetriever()
            try { retriever.setDataSource(context, song.uri); retriever.embeddedPicture }
            finally { retriever.release() }
        }.getOrNull() ?: runCatching { LocalTags.read(context, song).artwork }.getOrNull()
            ?: Downloads(context).localCover(song) ?: return@withContext null
        decode(bytes)
    }
    private fun decode(bytes: ByteArray, maxDimension: Int = 512, maxBytes: Int = cacheLimit(maxDimension)): Bitmap? {
        if (bytes.size > maxBytes) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension ||
            bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > 8_000_000) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    internal suspend fun loadUrl(value: String, strict: Boolean = false, maxDimension: Int = 512,
        maxDownloadBytes: Int = 8 * 1024 * 1024): Bitmap? = withContext(Dispatchers.IO) {
        require(maxDimension in 128..4096)
        require(maxDownloadBytes in 1..MAX_DOWNLOAD_BYTES)
        val url = value.trim().let { if (it.startsWith("//")) "https:$it" else it }
        if (!url.startsWith("https://") && !url.startsWith("http://")) return@withContext null
        locks[(url.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val file = AtomicFile(cacheFile(url, maxDimension))
            if (file.baseFile.isFile) {
                val cached = runCatching { file.openRead().use { decode(it.readBounded(cacheLimit(maxDimension)), maxDimension) } }.getOrNull()
                if (cached != null) return@withLock cached
                file.delete()
            }
            val parsed = Uri.parse(url)
            val host = parsed.host.orEmpty()
            val bilibiliImage = host.endsWith(".hdslb.com") || host.endsWith(".biliimg.com")
            val target = if (bilibiliImage && parsed.scheme == "http") parsed.buildUpon().scheme("https").build().toString() else url
            val connection = URL(target).openConnection() as HttpURLConnection
            val bytes = try {
                connection.connectTimeout = 10_000; connection.readTimeout = 10_000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (bilibiliImage) cn.toside.music.nativeapp.online.BilibiliApi.audioHeaders.forEach(connection::setRequestProperty)
                if (connection.responseCode != 200) {
                    if (strict) throw ArtworkHttpException(connection.responseCode)
                    null
                } else {
                    val length = connection.contentLengthLong
                    if (length > maxDownloadBytes) throw ArtworkSizeException(maxDownloadBytes, length)
                    try { connection.inputStream.use { it.readBounded(maxDownloadBytes) } }
                    catch (_: IllegalArgumentException) { throw ArtworkSizeException(maxDownloadBytes) }
                }
            } finally { connection.disconnect() }
            val bitmap = bytes?.let { decode(it, maxDimension, maxDownloadBytes) }
            if (bitmap == null) {
                if (strict && bytes != null) throw ArtworkDecodeException()
                return@withLock null
            }
            runCatching {
                file.baseFile.parentFile?.mkdirs()
                val output = file.startWrite()
                try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); file.finishWrite(output) }
                catch (e: Exception) { file.failWrite(output); throw e }
            }
            bitmap
        }
    }
    internal suspend fun forget(url: String) = withContext(Dispatchers.IO) {
        locks[(url.hashCode() and Int.MAX_VALUE) % locks.size].withLock { AtomicFile(cacheFile(url)).delete() }
    }
    private fun cacheLimit(maxDimension: Int) = if (maxDimension > 512) 32 * 1024 * 1024 else 8 * 1024 * 1024
    private fun cacheFile(url: String, maxDimension: Int = 512): File {
        val cacheKey = if (maxDimension == 512) url else "$maxDimension:$url"
        val key = MessageDigest.getInstance("SHA-256").digest(cacheKey.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return context.cacheDir.resolve("artwork/$key.png")
    }
    companion object {
        internal const val MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024
        private val locks = Array(16) { Mutex() }
    }
}
