package cn.toside.music.nativeapp.karaoke

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.utils.readBounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

internal data class KaraokeRecording(
    val id: String,
    val title: String,
    val artist: String,
    val file: File,
    val durationMs: Int,
    val sizeBytes: Long,
    val recordedAt: Long?,
    val savedAt: Long,
) {
    val exportName: String get() = title.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_").take(100).ifBlank { id } + ".wav"
}

/** App-private playlist. Recordings are shared only through the explicit export and share actions. */
internal class KaraokeLibrary(context: Context) {
    private val directory = context.filesDir.resolve("karaoke")
    private val resolver = context.contentResolver

    suspend fun list(): List<KaraokeRecording> = withContext(Dispatchers.IO) {
        directory.listFiles { file -> file.extension == "json" && ID.matches(file.nameWithoutExtension) }.orEmpty()
            .mapNotNull { metadata -> read(metadata.nameWithoutExtension) }
            .sortedByDescending { it.savedAt }
    }

    fun read(id: String): KaraokeRecording? {
        if (!ID.matches(id)) return null
        val wave = directory.resolve("$id.wav")
        val size = wave.length()
        val frames = (size - KaraokeAudio.HEADER_BYTES) / KaraokeAudio.BYTES_PER_FRAME
        if (!wave.isFile || frames <= 0) return null
        return runCatching {
            val text = AtomicFile(directory.resolve("$id.json")).openRead().use { it.readBounded(16 * 1024).toString(Charsets.UTF_8) }
            val data = JSONObject(text)
            KaraokeRecording(
                id, data.getString("title"), data.getString("artist"), wave, KaraokeAudio.millis(frames), size,
                data.optLong("recordedAt").takeIf { it > 0 }, wave.lastModified(),
            )
        }.getOrNull()
    }

    suspend fun save(id: String, song: Song, mixed: File, recordedAt: Long): KaraokeRecording = withContext(Dispatchers.IO) {
        require(ID.matches(id) && mixed.length() > KaraokeAudio.HEADER_BYTES)
        check(directory.mkdirs() || directory.isDirectory)
        val metadata = AtomicFile(directory.resolve("$id.json"))
        val wave = AtomicFile(directory.resolve("$id.wav"))
        val previous = wave.baseFile.isFile
        try {
            val data = JSONObject().put("title", song.title.take(500)).put("artist", song.artist.take(500))
                .put("recordedAt", recordedAt)
            metadata.write { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
            wave.write { output -> mixed.inputStream().use { copy(it, output) } }
            checkNotNull(read(id))
        } catch (e: Exception) {
            if (!previous) { metadata.delete(); wave.delete() }
            throw e
        }
    }

    suspend fun export(recording: KaraokeRecording, output: Uri) = withContext(Dispatchers.IO) {
        requireNotNull(resolver.openOutputStream(output, "wt")).use { target ->
            recording.file.inputStream().use { source -> copy(source, target) }
        }
    }

    private suspend fun AtomicFile.write(block: suspend (OutputStream) -> Unit) {
        val stream = startWrite()
        try {
            block(stream)
            finishWrite(stream)
        } catch (e: Exception) { failWrite(stream); throw e }
    }

    private suspend fun copy(source: InputStream, target: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = source.read(buffer)
            if (count < 0) break
            target.write(buffer, 0, count)
        }
    }

    private companion object { val ID = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}") }
}
