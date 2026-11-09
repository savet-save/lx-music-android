package cn.toside.music.nativeapp.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal enum class DownloadStatus { RESOLVING, QUEUED, RUNNING, PAUSED, LINK_FAILED, FAILED, INTERRUPTED, COMPLETE }

internal data class DownloadTask(
    val key: String, val song: Song, val quality: String?, val status: DownloadStatus,
    val systemId: Long? = null, val bytes: Long = 0, val total: Long = -1,
    val reason: Int = 0, val fileName: String? = null,
) {
    val failed get() = status in setOf(DownloadStatus.LINK_FAILED, DownloadStatus.FAILED, DownloadStatus.INTERRUPTED)
    val percent get() = if (status == DownloadStatus.COMPLETE) 100 else if (total > 0)
        (100.0 * bytes / total).toInt().coerceIn(0, 100) else 0
}

class Downloads(private val context: Context) {
    private val manager = requireNotNull(context.getSystemService(DownloadManager::class.java))
    private val history = context.getSharedPreferences("music-downloads", Context.MODE_PRIVATE)

    private data class Record(
        val key: String, val song: Song, val quality: String, val created: Long,
        val systemId: Long? = null, val fileName: String? = null, val failure: DownloadStatus? = null,
    )
    private data class Transfer(
        val id: Long, val title: String, val artist: String, val fileName: String?, val updated: Long,
        val status: DownloadStatus, val bytes: Long, val total: Long, val reason: Int,
    )

    fun enqueue(song: Song, uri: Uri): Long = enqueueFile(song, uri, fileName(song, uri))

    internal suspend fun start(song: Song, quality: String, retryKey: String? = null,
        resolve: suspend (Song, String) -> String): Boolean {
        val record = withContext(NonCancellable + Dispatchers.IO) {
            synchronized(lock) {
                val next = if (retryKey == null) Record(UUID.randomUUID().toString(), song, quality, System.currentTimeMillis()) else {
                    val old = read(retryKey) ?: return@synchronized null
                    if (old.key in resolving) return@synchronized null
                    val transfer = if (old.systemId != null || old.fileName != null) find(old, transfers(setOfNotNull(old.systemId))) else null
                    if (transfer != null && transfer.status != DownloadStatus.FAILED) return@synchronized null
                    transfer?.let { manager.remove(it.id) }
                    old.copy(systemId = null, fileName = null, failure = null)
                }
                write(next)
                resolving.add(next.key)
                next
            }
        } ?: return false
        var failure = DownloadStatus.LINK_FAILED
        try {
            currentCoroutineContext().ensureActive()
            val address = try { resolve(record.song, record.quality) }
            catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw IllegalStateException(e.message, e)
            }
            val uri = Uri.parse(address)
            validate(uri)
            failure = DownloadStatus.FAILED
            return withContext(Dispatchers.IO) {
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
                val status = transfer?.status ?: when {
                    record.key in resolving -> DownloadStatus.RESOLVING
                    else -> record.failure ?: DownloadStatus.INTERRUPTED
                }
                record.created to DownloadTask(record.key, record.song, record.quality, status, transfer?.id,
                    transfer?.bytes ?: 0, transfer?.total ?: -1, transfer?.reason ?: 0, transfer?.fileName ?: record.fileName)
            }
            val older = transfers.filter { it.id !in claimed && it.fileName != null }.map { transfer ->
                val song = Song(-transfer.id, Uri.EMPTY, transfer.title, transfer.artist, 0)
                transfer.updated to DownloadTask("system:${transfer.id}", song, null, transfer.status, transfer.id,
                    transfer.bytes, transfer.total, transfer.reason, transfer.fileName)
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
                check(history.edit().remove(record.key).commit()) { "无法移除下载任务" }
            }
        }
    }

    internal suspend fun downloadedSong(task: DownloadTask): Song? = withContext(Dispatchers.IO) {
        val id = task.systemId ?: return@withContext null
        val uri = manager.getUriForDownloadedFile(id) ?: return@withContext null
        manager.openDownloadedFile(id).close()
        task.song.copy(id = -id, uri = uri, source = "local", sourceInfo = null)
    }

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
                val name = file?.name?.takeIf { parent?.name == DIRECTORY && parent.parentFile?.name == Environment.DIRECTORY_MUSIC }
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
                    number(DownloadManager.COLUMN_REASON).toInt()))
            }
        }
    }.orEmpty()

    private fun write(record: Record) {
        val song = record.song
        val value = JSONObject().put("id", song.id).put("uri", song.uri.toString()).put("title", song.title)
            .put("artist", song.artist).put("duration", song.duration).put("source", song.source).put("sourceInfo", song.sourceInfo)
            .put("quality", record.quality).put("created", record.created).put("systemId", record.systemId)
            .put("fileName", record.fileName).put("failure", record.failure?.name)
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
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "$DIRECTORY/$fileName")
        return manager.enqueue(request)
    }

    private companion object {
        const val DIRECTORY = "LX Music"
        val lock = Any()
        val resolving = mutableSetOf<String>()
    }
}
