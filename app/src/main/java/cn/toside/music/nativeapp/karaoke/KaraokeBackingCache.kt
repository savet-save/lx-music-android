package cn.toside.music.nativeapp.karaoke

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import cn.toside.music.nativeapp.data.AppPreferences
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.player.AudioStreamCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

internal class KaraokeBackingCache(private val context: Context) {
    private val preferences = AppPreferences(context)

    suspend fun reuseOrPrepare(
        song: Song,
        target: File,
        original: File? = null,
        separation: MdxParameters? = null,
        quality: String = preferredQuality(),
        prepare: suspend () -> KaraokeBacking,
    ): KaraokeBacking {
        val key = withContext(Dispatchers.IO) {
            check(target.delete() || !target.exists())
            original?.let { check(it.delete() || !it.exists()) }
            key(song, quality)
        }
        if (key != null) {
            val reused = withContext(Dispatchers.IO) { cacheAttempt { restore(key, target, separation) } }
            if (reused != null) {
                val monitor = if (original != null) withContext(Dispatchers.IO) {
                    cacheAttempt {
                        if (restoreFrames(originalKey(key), original) == reused.frames) original else null
                    }
                } else null
                return reused.copy(original = monitor, cacheKey = key)
            }
            withContext(Dispatchers.IO) { check(target.delete() || !target.exists()) }
        }
        val backing = prepare().copy(cacheKey = key)
        remember(song, backing)
        return backing
    }

    suspend fun remember(song: Song, backing: KaraokeBacking) = withContext(Dispatchers.IO) {
        val key = backing.cacheKey ?: return@withContext
        cacheAttempt {
            if (key(song) == key) {
                backing.original?.let { storeOriginal(key, backing.file, it) }
                AudioStreamCache.get(context).storeFile(channelKey(key, backing.stereo, backing.separation), backing.file)
            }
        }
    }

    suspend fun rememberOriginal(song: Song, backing: KaraokeBacking, original: File) = withContext(Dispatchers.IO) {
        val key = backing.cacheKey ?: return@withContext
        cacheAttempt {
            if (key(song) == key) storeOriginal(key, backing.file, original)
        }
    }

    private suspend fun storeOriginal(key: String, backing: File, original: File) {
        if (backing.length() + original.length() <= preferences.audioCacheBytes) {
            AudioStreamCache.get(context).storeFile(originalKey(key), original)
        }
    }

    private suspend fun restore(key: String, target: File, separation: MdxParameters?): KaraokeBacking? {
        for (stereo in listOf(true, false)) {
            val frames = restoreFrames(channelKey(key, stereo, separation), target)
            if (frames != null) return KaraokeBacking(target, frames, stereo, separation = separation)
        }
        return null
    }

    private suspend fun restoreFrames(key: String, target: File): Long? {
        val cache = AudioStreamCache.get(context)
        if (cache.restoreFile(key, target)) {
            val bytes = target.length() - KaraokeAudio.HEADER_BYTES
            val frames = bytes / KaraokeAudio.BYTES_PER_FRAME
            if (frames in 1..KaraokeAudio.MAX_FRAMES && bytes % KaraokeAudio.BYTES_PER_FRAME == 0L) {
                val declaredBytes = RandomAccessFile(target, "r").use {
                    it.seek(KaraokeAudio.HEADER_BYTES - Int.SIZE_BYTES)
                    Integer.reverseBytes(it.readInt()).toLong()
                }
                if (declaredBytes == bytes) return frames
            }
            cache.remove(key)
        }
        check(target.delete() || !target.exists())
        return null
    }

    internal fun preferredQuality() = context.getSharedPreferences("player", Context.MODE_PRIVATE)
        .getString("quality", "128k") ?: "128k"

    private fun key(song: Song, quality: String = preferredQuality()): String? =
        if (preferences.audioCacheBytes == 0L) null else sourceKey(song, quality)

    internal fun sourceKey(song: Song, quality: String): String? = cacheAttempt {
        val identity = JSONArray().put(song.uri.toString())
        if (song.source == "local") identity.put(localRevision(song.uri) ?: return@cacheAttempt null)
        else identity.put(song.sourceInfo).put(song.duration).put(quality)
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toString().toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        "${AudioStreamCache.KARAOKE_PREFIX}${AudioStreamCache.key(song, "")}$FORMAT_VERSION:$digest"
    }

    private fun localRevision(uri: Uri): String? {
        val stat = when (uri.scheme) {
            "file" -> Os.stat(uri.path ?: return null)
            "content" -> context.contentResolver.openFileDescriptor(uri, "r")?.use { Os.fstat(it.fileDescriptor) } ?: return null
            else -> return null
        }
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size <= 0) return null
        return listOf(stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime, stat.st_mtim.tv_nsec,
            stat.st_ctime, stat.st_ctim.tv_nsec).joinToString(":")
    }

    private fun channelKey(key: String, stereo: Boolean, separation: MdxParameters?): String {
        val mode = separation?.cacheMode ?: "center"
        return "$key:$mode:${if (stereo) 2 else 1}"
    }
    private fun originalKey(key: String) = "$key:original"

    private inline fun <T> cacheAttempt(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    private companion object {
        // Increment when the backing format or vocal reduction changes.
        const val FORMAT_VERSION = 2
    }
}
