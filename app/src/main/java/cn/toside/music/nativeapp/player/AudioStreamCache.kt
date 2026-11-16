@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package cn.toside.music.nativeapp.player

import cn.toside.music.nativeapp.data.AppPreferences
import cn.toside.music.nativeapp.data.Song

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.util.TreeSet
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One process-wide cache avoids overlapping SimpleCache directory locks across service restarts. */
internal class AudioStreamCache(private val context: Context, directory: File = context.cacheDir.resolve("audio-stream")) : AutoCloseable {
    private val evictor = AdjustableLruEvictor(AppPreferences(context).audioCacheBytes)
    private val database = StandaloneDatabaseProvider(context)
    internal val cache = SimpleCache(directory, evictor, database)
    private val upstream = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(OkHttpClient.Builder()
        .connectTimeout(13, java.util.concurrent.TimeUnit.SECONDS).readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build()))
    fun dataSource(headers: Map<String, String> = emptyMap()): CacheDataSource.Factory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(
        if(headers.isEmpty()) upstream else DefaultDataSource.Factory(context, OkHttpDataSource.Factory(OkHttpClient()).setDefaultRequestProperties(headers)))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    fun updateLimit(bytes: Long) = synchronized(cache) { evictor.update(cache, bytes) }
    fun clear() { cache.keys.toList().forEach { cache.removeResource(it) } }
    val bytes: Long get() = cache.cacheSpace
    internal fun remove(key: String) {
        cache.removeResource(key)
        if (key in cache.keys) cache.applyContentMetadataMutations(key, ContentMetadataMutations().remove("native_uri").also {
            ContentMetadataMutations.setContentLength(it, C.LENGTH_UNSET.toLong())
            ContentMetadataMutations.setRedirectedUri(it, null)
        })
    }
    internal fun removeSong(prefix: String) { cache.keys.filter { it.startsWith(prefix) }.forEach { cache.removeResource(it) } }
    fun cachedUri(key: String): Uri? {
        val metadata = cache.getContentMetadata(key)
        val length = ContentMetadata.getContentLength(metadata)
        val url = metadata.get("native_uri", "") ?: ""
        return if (length > 0 && url.isNotBlank() && cache.isCached(key, 0, length)) Uri.parse(url) else null
    }
    internal fun playbackUri(key: String): Uri? {
        if (cache.getCachedSpans(key).isEmpty()) return null
        return cache.getContentMetadata(key).get("native_uri", "")?.takeIf { it.isNotBlank() }?.let(Uri::parse)
    }
    fun remember(key: String, uri: Uri, bitrate: Long? = null, durationMs: Long? = null, trackId: Long? = null) {
        val metadata = ContentMetadataMutations().set("native_uri", uri.toString())
        bitrate?.takeIf { it > 0 }?.let { metadata.set("native_bitrate", it) }
        durationMs?.takeIf { it > 0 }?.let { metadata.set("native_duration", it) }
        trackId?.takeIf { it > 0 }?.let { metadata.set("native_audio_track", it) }
        cache.applyContentMetadataMutations(key, metadata)
    }
    internal data class CompleteAudio(val key: String, val uri: Uri, val length: Long, val bitrate: Long? = null, val durationMs: Long? = null)
    internal fun completeAudio(song: Song, quality: String): CompleteAudio? {
        val requested = key(song, quality)
        // Playback uses "original"; explicit downloads use the stable Bilibili audio track ID.
        val original = key(song, "original")
        val trackId = quality.removePrefix("aac-").toLongOrNull()
        val key = if (song.source == "bili" && quality.startsWith("aac-") && trackId != null && cachedUri(requested) == null &&
            cache.getContentMetadata(original).get("native_audio_track", 0L) == trackId) original else requested
        val uri = cachedUri(key) ?: return null
        val metadata = cache.getContentMetadata(key)
        return CompleteAudio(key, uri, ContentMetadata.getContentLength(metadata),
            metadata.get("native_bitrate", 0L).takeIf { it > 0 }, metadata.get("native_duration", 0L).takeIf { it > 0 })
    }
    internal fun completeAudios(song: Song): List<Pair<String, CompleteAudio>> {
        val prefix = key(song, "")
        return cache.keys.filter { it.startsWith(prefix) }.sorted().mapNotNull { key ->
            val quality = key.removePrefix(prefix)
            completeAudio(song, quality)?.let { quality to it }
        }
    }
    /** Cache spans are fragments, not standalone audio files. Never fetch missing bytes here. */
    internal suspend fun copyComplete(audio: CompleteAudio, output: OutputStream): Boolean = withContext(Dispatchers.IO) {
        val reader = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(null)
            .setCacheWriteDataSinkFactory(null).createDataSource()
        try {
            reader.open(DataSpec.Builder().setUri(audio.uri).setKey(audio.key).setLength(audio.length).build())
            val buffer = ByteArray(64 * 1024)
            var copied = 0L
            while (copied < audio.length) {
                currentCoroutineContext().ensureActive()
                val count = reader.read(buffer, 0, minOf(buffer.size.toLong(), audio.length - copied).toInt())
                if (count < 0) break
                output.write(buffer, 0, count); copied += count
            }
            copied == audio.length
        } catch (e: CancellationException) { throw e }
        catch (_: java.io.IOException) { false }
        finally { reader.close() }
    }
    override fun close() { cache.release(); database.close() }
    companion object {
        @Volatile private var instance: AudioStreamCache? = null
        fun get(context: Context): AudioStreamCache = instance ?: synchronized(this) {
            instance ?: AudioStreamCache(context.applicationContext).also { instance = it }
        }
        fun existing(): AudioStreamCache? = instance
        fun key(song: Song, quality: String): String {
            val info = song.sourceInfo?.let(::JSONObject)
            return "${song.source}:${info?.optString("songmid") ?: song.id}:${info?.optString("hash").orEmpty()}:$quality"
        }
    }
}

/** Cache callbacks run under SimpleCache's monitor. Limits can change without closing active readers. */
private class AdjustableLruEvictor(private var maxBytes: Long) : CacheEvictor {
    private val spans = TreeSet<CacheSpan>(compareBy<CacheSpan> { it.lastTouchTimestamp }.thenBy { it.key }.thenBy { it.position })
    private var bytes = 0L
    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() = Unit
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) { evict(cache, if (length == C.LENGTH_UNSET.toLong()) 0 else length) }
    override fun onSpanAdded(cache: Cache, span: CacheSpan) { if (spans.add(span)) bytes += span.length; evict(cache, 0) }
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) { if (spans.remove(span)) bytes -= span.length }
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) { onSpanRemoved(cache, oldSpan); onSpanAdded(cache, newSpan) }
    fun update(cache: Cache, value: Long) { maxBytes = value.coerceAtLeast(0); evict(cache, 0) }
    private fun evict(cache: Cache, required: Long) {
        while (bytes + required > maxBytes && spans.isNotEmpty()) cache.removeSpan(spans.first())
    }
}
