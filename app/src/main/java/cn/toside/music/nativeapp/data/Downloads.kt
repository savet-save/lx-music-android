package cn.toside.music.nativeapp.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import cn.toside.music.nativeapp.lyrics.Lyrics
import cn.toside.music.nativeapp.lyrics.LyricLine
import cn.toside.music.nativeapp.lyrics.LrcParser
import cn.toside.music.nativeapp.lyrics.exportLrc
import cn.toside.music.nativeapp.online.OnlineLyrics
import cn.toside.music.nativeapp.player.AudioStreamCache
import cn.toside.music.nativeapp.utils.readBounded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal enum class DownloadStatus { RESOLVING, QUEUED, RUNNING, PAUSED, LINK_FAILED, FAILED, INTERRUPTED, COMPLETE }
internal enum class DownloadLyricStatus { PENDING, SAVED, UNAVAILABLE, FAILED }

internal data class DownloadTask(
    val key: String, val song: Song, val quality: String?, val status: DownloadStatus,
    val systemId: Long? = null, val bytes: Long = 0, val total: Long = -1,
    val reason: Int = 0, val fileName: String? = null,
    val localUri: Uri? = null, val lyricStatus: DownloadLyricStatus? = null, val lyricUri: Uri? = null,
    val directory: String = DownloadFiles.RELATIVE_PATH,
) {
    val failed get() = status in setOf(DownloadStatus.LINK_FAILED, DownloadStatus.FAILED, DownloadStatus.INTERRUPTED)
    val percent get() = if (status == DownloadStatus.COMPLETE) 100 else if (total > 0)
        (100.0 * bytes / total).toInt().coerceIn(0, 100) else 0
}

class Downloads(private val context: Context, private val scheduleLyrics: (Context) -> Unit = DownloadLyricsJob::schedule) {
    private val manager = requireNotNull(context.getSystemService(DownloadManager::class.java))
    private val history = context.getSharedPreferences("music-downloads", Context.MODE_PRIVATE)
    private val files = DownloadFiles(context)

    private data class Record(
        val key: String, val song: Song, val quality: String, val created: Long,
        val systemId: Long? = null, val fileName: String? = null, val failure: DownloadStatus? = null,
        val localUri: Uri? = null, val localBytes: Long = 0,
        val lyricStatus: DownloadLyricStatus? = DownloadLyricStatus.PENDING, val lyricUri: Uri? = null, val lyricAttempts: Int = 0,
    )
    private data class Transfer(
        val id: Long, val title: String, val artist: String, val fileName: String?, val updated: Long,
        val status: DownloadStatus, val bytes: Long, val total: Long, val reason: Int,
        val directory: String,
    )

    fun enqueue(song: Song, uri: Uri): Long = enqueueFile(song, uri, fileName(song, uri))

    internal suspend fun start(song: Song, quality: String, retryKey: String? = null,
        resolve: suspend (Song, String) -> String): Boolean {
        val record = withContext(NonCancellable + Dispatchers.IO) {
            synchronized(lock) {
                val next = if (retryKey == null) Record(UUID.randomUUID().toString(), song, quality, System.currentTimeMillis()) else {
                    val old = read(retryKey) ?: return@synchronized null
                    if (old.key in resolving) return@synchronized null
                    if (old.localUri != null && old.localBytes > 0) return@synchronized null
                    val transfer = if (old.systemId != null || old.fileName != null) find(old, transfers(setOfNotNull(old.systemId))) else null
                    if (transfer != null && transfer.status != DownloadStatus.FAILED) return@synchronized null
                    transfer?.let { manager.remove(it.id) }
                    old.localUri?.let(files::delete); old.lyricUri?.let(files::delete)
                    old.copy(systemId = null, fileName = null, failure = null, localUri = null, localBytes = 0,
                        lyricStatus = DownloadLyricStatus.PENDING, lyricUri = null, lyricAttempts = 0)
                }
                write(next)
                resolving.add(next.key)
                next
            }
        } ?: return false
        var failure = DownloadStatus.FAILED
        try {
            currentCoroutineContext().ensureActive()
            if (exportCached(record)) {
                scheduleLyrics(context)
                return true
            }
            failure = DownloadStatus.LINK_FAILED
            val address = try { resolve(record.song, record.quality) }
            catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw IllegalStateException(e.message, e)
            }
            val uri = Uri.parse(address)
            validate(uri)
            failure = DownloadStatus.FAILED
            val started = withContext(Dispatchers.IO) {
                synchronized(lock) {
                    val current = read(record.key) ?: return@synchronized false
                    // Match the destination on restart if the process stops after enqueue but before saving the system ID.
                    val prepared = current.copy(fileName = fileName(current.song, uri, current.quality))
                    write(prepared)
                    val id = enqueueFile(prepared.song, uri, requireNotNull(prepared.fileName))
                    try { write(prepared.copy(systemId = id)) }
                    catch (e: Exception) { manager.remove(id); throw e }
                    true
                }
            }
            if (started) scheduleLyrics(context)
            return started
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            withContext(Dispatchers.IO) {
                synchronized(lock) { read(record.key)?.let { write(it.copy(failure = failure)) } }
            }
            throw e
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { synchronized(lock) { resolving.remove(record.key) } }
        }
    }

    private suspend fun exportCached(record: Record): Boolean = withContext(Dispatchers.IO) {
        val cache = AudioStreamCache.get(context)
        val audio = cache.completeAudio(record.song, record.quality) ?: return@withContext false
        val name = fileName(record.song, audio.uri, record.quality)
        val uri = files.create(name, mimeType(name))
        var complete = false
        try {
            val exists = synchronized(lock) {
                val current = read(record.key) ?: return@synchronized false
                write(current.copy(fileName = name, localUri = uri)); true
            }
            if (!exists) return@withContext false
            val copied = files.output(uri).use { cache.copyComplete(audio, it) }
            if (!copied) return@withContext false
            currentCoroutineContext().ensureActive()
            complete = synchronized(lock) {
                val current = read(record.key) ?: return@synchronized false
                files.publish(uri)
                write(current.copy(localBytes = audio.length)); true
            }
            complete
        } finally {
            if (!complete) withContext(NonCancellable) {
                files.delete(uri)
                synchronized(lock) { read(record.key)?.let { write(it.copy(fileName = null, localUri = null, localBytes = 0)) } }
            }
        }
    }

    internal suspend fun list(): List<DownloadTask> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val records = history.all.keys.mapNotNull(::read)
            val transfers = transfers(records.mapNotNull { it.systemId }.toSet())
            val byId = transfers.associateBy { it.id }
            val byFile = transfers.mapNotNull { transfer -> transfer.fileName?.let { it to transfer } }.toMap()
            val claimed = mutableSetOf<Long>()
            val tasks = records.map { record ->
                val transfer = byId[record.systemId]?.takeIf { it.fileName == null || it.fileName == record.fileName } ?: byFile[record.fileName]
                transfer?.let { claimed.add(it.id) }
                val status = when {
                    record.localUri != null && record.localBytes > 0 -> DownloadStatus.COMPLETE
                    transfer != null -> transfer.status
                    record.key in resolving -> DownloadStatus.RESOLVING
                    else -> record.failure ?: DownloadStatus.INTERRUPTED
                }
                record.created to DownloadTask(record.key, record.song, record.quality, status, transfer?.id,
                    transfer?.bytes ?: record.localBytes, transfer?.total ?: record.localBytes.takeIf { it > 0 } ?: -1,
                    transfer?.reason ?: 0, transfer?.fileName ?: record.fileName, record.localUri, record.lyricStatus,
                    record.lyricUri, transfer?.directory ?: DownloadFiles.RELATIVE_PATH)
            }
            val older = transfers.filter { it.id !in claimed && it.fileName != null }.map { transfer ->
                val song = Song(-transfer.id, Uri.EMPTY, transfer.title, transfer.artist, 0)
                transfer.updated to DownloadTask("system:${transfer.id}", song, null, transfer.status, transfer.id,
                    transfer.bytes, transfer.total, transfer.reason, transfer.fileName, directory = transfer.directory)
            }
            (tasks + older).sortedByDescending { it.first }.map { it.second }
        }
    }

    internal suspend fun remove(task: DownloadTask) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (task.key.startsWith("system:")) {
                transfers().firstOrNull { "system:${it.id}" == task.key }?.let { manager.remove(it.id) }
            } else read(task.key)?.let { record ->
                val systemId = if (record.systemId != null || record.fileName != null) find(record, transfers(setOfNotNull(record.systemId)))?.id else null
                systemId?.let { manager.remove(it) }
                record.localUri?.let(files::delete); record.lyricUri?.let(files::delete)
                check(history.edit().remove(record.key).commit()) { "无法移除下载任务" }
            }
        }
    }

    internal suspend fun downloadedSong(task: DownloadTask): Song? = withContext(Dispatchers.IO) {
        val uri = task.localUri ?: task.systemId?.let(manager::getUriForDownloadedFile) ?: return@withContext null
        context.contentResolver.openFileDescriptor(uri, "r")?.close() ?: return@withContext null
        val id = task.systemId?.let { -it } ?: -task.key.replace("-", "").take(15).toLong(16)
        val song = task.song.copy(id = id, uri = uri, source = "local", sourceInfo = null)
        if (task.song.source != "local") {
            try {
                val lyrics = Lyrics(context)
                val lines = lyrics.load(task.song.id, task.song.source)
                if (lines.isNotEmpty()) lyrics.cache(song, lines)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
        }
        song
    }

    internal fun ownsSystemId(id: Long): Boolean = synchronized(lock) { history.all.keys.any { read(it)?.systemId == id } }

    internal suspend fun pendingLyrics(): List<DownloadTask> = list().filter {
        it.status == DownloadStatus.COMPLETE && it.lyricStatus == DownloadLyricStatus.PENDING
    }

    /** MediaStore and the download page can identify the same file with different local IDs. */
    internal suspend fun localLyrics(song: Song): List<LyricLine> = withContext(Dispatchers.IO) {
        val name = ExternalAudio.name(context, song.uri)
        val record = synchronized(lock) { history.all.keys.mapNotNull(::read).firstOrNull { it.fileName == name } }
            ?: return@withContext emptyList()
        val cached = Lyrics(context).load(record.song.id, record.song.source)
        if (cached.isNotEmpty()) return@withContext cached
        val uri = record.lyricUri ?: return@withContext emptyList()
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBounded(2 * 1024 * 1024).toString(Charsets.UTF_8) }
            ?: return@withContext emptyList()
        LrcParser.parse(text).groupBy { it.timeMs }.values.map { lines ->
            lines.first().copy(translation = lines.getOrNull(1)?.text.orEmpty(), romanization = lines.getOrNull(2)?.text.orEmpty())
        }
    }

    internal suspend fun saveLyrics(key: String, load: suspend (Song) -> List<LyricLine> = { song ->
        Lyrics(context).load(song.id, song.source).ifEmpty { OnlineLyrics().forSong(song) }
    }): Boolean = lyricMutex.withLock { withContext(Dispatchers.IO) {
        val record = synchronized(lock) { read(key)?.takeIf { it.lyricStatus == DownloadLyricStatus.PENDING } } ?: return@withContext false
        var uri: Uri? = null
        var saved = false
        try {
            val lines = load(record.song).filter { it.text.isNotBlank() }
            if (lines.isEmpty()) {
                synchronized(lock) { read(key)?.let { write(it.copy(lyricStatus = DownloadLyricStatus.UNAVAILABLE)) } }
                return@withContext false
            }
            Lyrics(context).cache(record.song, lines)
            val name = requireNotNull(record.fileName).substringBeforeLast('.') + ".lrc"
            uri = files.create(name, "application/octet-stream")
            files.output(uri).use { it.write(exportLrc(lines).toByteArray(Charsets.UTF_8)) }
            currentCoroutineContext().ensureActive()
            saved = synchronized(lock) {
                val current = read(key) ?: return@synchronized false
                files.publish(uri)
                write(current.copy(lyricStatus = DownloadLyricStatus.SAVED, lyricUri = uri)); true
            }
            false
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            synchronized(lock) { read(key)?.let {
                val attempts = it.lyricAttempts + 1
                write(it.copy(lyricAttempts = attempts, lyricStatus = if (attempts >= 3) DownloadLyricStatus.FAILED else DownloadLyricStatus.PENDING))
            } }
            synchronized(lock) { read(key)?.lyricStatus == DownloadLyricStatus.PENDING }
        } finally {
            if (!saved) uri?.let(files::delete)
        }
    } }

    private fun find(record: Record, transfers: List<Transfer>) = transfers.firstOrNull {
        (it.id == record.systemId && it.fileName == null) || (record.fileName != null && it.fileName == record.fileName)
    }

    private fun transfers(tracked: Set<Long> = emptySet()): List<Transfer> = manager.query(DownloadManager.Query())?.use { cursor ->
        fun string(column: String) = cursor.getString(cursor.getColumnIndexOrThrow(column)).orEmpty()
        fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
        buildList {
            while (cursor.moveToNext()) {
                val id = number(DownloadManager.COLUMN_ID)
                val uri = Uri.parse(string(DownloadManager.COLUMN_LOCAL_URI))
                val file = uri.path?.takeIf { uri.scheme == "file" }?.let(::File)
                val parent = file?.parentFile
                val name = file?.name?.takeIf { parent?.name == DownloadFiles.DIRECTORY && parent.parentFile?.name in setOf(Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_DOWNLOADS) }
                if (name == null && (id !in tracked || uri.scheme == "file")) continue
                val status = when (number(DownloadManager.COLUMN_STATUS).toInt()) {
                    DownloadManager.STATUS_PENDING -> DownloadStatus.QUEUED
                    DownloadManager.STATUS_RUNNING -> DownloadStatus.RUNNING
                    DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                    DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETE
                    else -> DownloadStatus.FAILED
                }
                add(Transfer(id, string(DownloadManager.COLUMN_TITLE),
                    string(DownloadManager.COLUMN_DESCRIPTION), name, number(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP),
                    status, number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR), number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                    number(DownloadManager.COLUMN_REASON).toInt(), "${parent?.parentFile?.name ?: Environment.DIRECTORY_DOWNLOADS}/${DownloadFiles.DIRECTORY}/"))
            }
        }
    }.orEmpty()

    private fun write(record: Record) {
        val song = record.song
        val value = JSONObject().put("id", song.id).put("uri", song.uri.toString()).put("title", song.title)
            .put("artist", song.artist).put("duration", song.duration).put("source", song.source).put("sourceInfo", song.sourceInfo)
            .put("quality", record.quality).put("created", record.created).put("systemId", record.systemId)
            .put("fileName", record.fileName).put("failure", record.failure?.name)
            .put("localUri", record.localUri?.toString()).put("localBytes", record.localBytes)
            .put("lyricStatus", record.lyricStatus?.name).put("lyricUri", record.lyricUri?.toString()).put("lyricAttempts", record.lyricAttempts)
        check(history.edit().putString(record.key, value.toString()).commit()) { "无法保存下载任务" }
    }

    private fun read(key: String): Record? = history.getString(key, null)?.let { value ->
        runCatching {
            val json = JSONObject(value)
            fun optional(name: String) = if (json.isNull(name)) null else json.getString(name)
            val song = Song(json.getLong("id"), Uri.parse(json.getString("uri")), json.getString("title"),
                json.getString("artist"), json.getLong("duration"), json.getString("source"), optional("sourceInfo"))
            Record(key, song, json.getString("quality"), json.getLong("created"), json.optLong("systemId", -1).takeIf { it >= 0 },
                optional("fileName"), optional("failure")?.let(DownloadStatus::valueOf))
                .copy(localUri = optional("localUri")?.let(Uri::parse), localBytes = json.optLong("localBytes"),
                    lyricStatus = optional("lyricStatus")?.let(DownloadLyricStatus::valueOf), lyricUri = optional("lyricUri")?.let(Uri::parse),
                    lyricAttempts = json.optInt("lyricAttempts"))
        }.getOrNull()
    }

    private fun validate(uri: Uri) {
        require((uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()) { "音源返回了无效地址" }
    }

    private fun fileName(song: Song, uri: Uri, quality: String? = null): String {
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it in setOf("mp3", "m4a", "aac", "flac", "ogg", "wav") } ?: if (quality in setOf("flac", "flac24bit")) "flac" else "mp3"
        val display = when (AppPreferences(context).downloadPattern) {
            "歌手 - 歌名" -> "${song.artist} - ${song.title}"
            "歌名" -> song.title
            else -> "${song.title} - ${song.artist}"
        }
        val base = display.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim().take(120).ifBlank { "music" }
        return "$base-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.$ext"
    }

    private fun enqueueFile(song: Song, uri: Uri, fileName: String): Long {
        validate(uri)
        val request = DownloadManager.Request(uri).setTitle(song.title).setDescription(song.artist)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "${DownloadFiles.DIRECTORY}/$fileName")
        return manager.enqueue(request)
    }

    private companion object {
        val lock = Any()
        val resolving = mutableSetOf<String>()
        val lyricMutex = Mutex()
    }
    private fun mimeType(name: String) = when (name.substringAfterLast('.')) {
        "flac" -> "audio/flac"; "wav" -> "audio/wav"; "m4a" -> "audio/mp4"; "aac" -> "audio/aac"; "ogg" -> "audio/ogg"; else -> "audio/mpeg"
    }
}
