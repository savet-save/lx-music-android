@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package cn.toside.music.nativeapp.data

import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.os.ParcelFileDescriptor
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.lyrics.LyricLine
import cn.toside.music.nativeapp.lyrics.Lyrics
import cn.toside.music.nativeapp.lyrics.exportLrc
import cn.toside.music.nativeapp.player.AudioStreamCache
import cn.toside.music.nativeapp.utils.PermissionPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class CachedDownloadsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun song() = Song(System.nanoTime(), Uri.EMPTY, "cache export", "test artist", 1000, "kw", "{}")
    private fun permissions() {
        PermissionPolicy.downloadWrite(Build.VERSION.SDK_INT)?.let { permission ->
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
    }
    private fun store(song: Song, quality: String, bytes: ByteArray, complete: Boolean = true): AudioStreamCache {
        val cache = AudioStreamCache.get(context)
        val key = AudioStreamCache.key(song, quality)
        cache.updateLimit(16L * 1024 * 1024)
        val end = if (complete) bytes.size else bytes.size / 2
        var position = 0
        while (position < end) {
            val size = minOf(4096, end - position)
            val hole = cache.cache.startReadWrite(key, position.toLong(), size.toLong())
            try {
                val file = cache.cache.startFile(key, position.toLong(), size.toLong())
                file.outputStream().use { it.write(bytes, position, size) }
                cache.cache.commitFile(file, size.toLong())
            } finally { cache.cache.releaseHoleSpan(hole) }
            position += size
        }
        val metadata = ContentMetadataMutations()
        ContentMetadataMutations.setContentLength(metadata, bytes.size.toLong())
        cache.cache.applyContentMetadataMutations(key, metadata)
        cache.remember(key, Uri.parse("http://127.0.0.1:1/audio.wav"))
        return cache
    }
    private fun bytes(uri: Uri) = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    private fun exists(uri: Uri): Boolean = runCatching { context.contentResolver.openFileDescriptor(uri, "r")!!.use { true } }.getOrDefault(false)
    private suspend fun cleanup(song: Song, downloads: Downloads) {
        downloads.list().filter { it.song == song }.forEach { downloads.remove(it) }
        listOf("128k", "320k").forEach { AudioStreamCache.existing()?.remove(AudioStreamCache.key(song, it)) }
        Lyrics(context).clearOnlineCache(song)
    }
    @Test fun fullFragmentedCacheExportsOfflineAndBackgroundJobSavesMatchingLrc() = runBlocking {
        permissions()
        val song = song()
        val payload = ByteArray(80_000) { (it % 251).toByte() }
        val cache = store(song, "128k", payload)
        val lines = listOf(LyricLine(1234, "第一句", translation = "first"), LyricLine(3200, "第二句"))
        val downloads = Downloads(context)
        Lyrics(context).cache(song, lines)
        var local: Song? = null
        try {
            assertTrue(downloads.start(song, "128k") { _, _ -> error("The cache export must not request a URL") })
            var task = downloads.list().single { it.song == song }
            assertEquals(DownloadStatus.COMPLETE, task.status); assertEquals(100, task.percent)
            assertNull(task.systemId); assertNotNull(task.localUri)
            assertEquals(payload.size.toLong(), task.bytes)
            assertArrayEquals(payload, bytes(task.localUri!!))
            DownloadLyricsJob.schedule(context)
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cmd jobscheduler run -f ${context.packageName} ${0x4c584c52}")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            withTimeout(20_000) {
                while (true) {
                    task = Downloads(context).list().single { it.song == song }
                    if (task.lyricStatus == DownloadLyricStatus.SAVED) break
                    delay(80)
                }
            }
            assertEquals(exportLrc(lines), bytes(task.lyricUri!!).toString(Charsets.UTF_8))
            context.contentResolver.query(task.lyricUri!!, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(task.fileName!!.substringBeforeLast('.') + ".lrc", it.getString(0))
            }
            if (Build.VERSION.SDK_INT >= 29) context.contentResolver.query(task.lyricUri!!,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(DownloadFiles.RELATIVE_PATH, it.getString(0))
            }
            local = downloads.downloadedSong(task)!!
            assertEquals(lines, Lyrics(context).load(local!!.id, "local"))
            assertEquals(lines, downloads.localLyrics(local!!.copy(id = 123456)))
            Lyrics(context).clearOnlineCache(song)
            assertEquals(lines, downloads.localLyrics(local!!.copy(id = 654321)))
            downloads.remove(task)
            assertFalse(exists(task.localUri!!)); assertFalse(exists(task.lyricUri!!))
            assertNotNull(cache.completeAudio(song, "128k"))
        } finally { cleanup(song, downloads); local?.let { Lyrics(context).clearOnlineCache(it) } }
    }

    @Test fun partialOrDifferentQualityCacheUsesNormalDownloadAndEvictionNeverFetchesMissingBytes() = runBlocking {
        val song = song()
        val downloads = Downloads(context, scheduleLyrics = {})
        val cache = store(song, "128k", ByteArray(20_000), complete = false)
        try {
            assertNull(cache.completeAudio(song, "128k"))
            var resolutions = 0
            try { downloads.start(song, "128k") { _, _ -> resolutions++; error("network fallback") }; fail("resolver must run") }
            catch (e: IllegalStateException) { assertEquals("network fallback", e.message) }
            assertEquals(1, resolutions)
            val failed = downloads.list().single { it.song == song }
            assertEquals(DownloadStatus.LINK_FAILED, failed.status); assertNull(failed.localUri)
            downloads.remove(failed)
            cache.remove(AudioStreamCache.key(song, "128k"))
            store(song, "320k", ByteArray(20_000))
            try { downloads.start(song, "128k") { _, _ -> resolutions++; error("different quality") }; fail("quality must match") }
            catch (e: IllegalStateException) { assertEquals("different quality", e.message) }
            assertEquals(2, resolutions)
            val snapshot = cache.completeAudio(song, "320k")!!
            cache.remove(snapshot.key)
            assertFalse(cache.copyComplete(snapshot, ByteArrayOutputStream()))
        } finally { cleanup(song, downloads) }
    }

    @Test fun uncachedLyricsAreFetchedAndSavedAutomaticallyBesideTheAudio() = runBlocking {
        permissions()
        val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val address = "http://127.0.0.1:${server.localPort}"
        val song = song().copy(source = "mg", sourceInfo = """{"lrcUrl":"$address/original.lrc","trcUrl":"$address/translation.lrc"}""")
        store(song, "128k", ByteArray(8192))
        val downloads = Downloads(context)
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val worker = kotlin.concurrent.thread(isDaemon = true) {
            try { repeat(2) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val path = reader.readLine().split(' ')[1]
                    while (!reader.readLine().isNullOrEmpty()) { }
                    requests.add(path)
                    val text = if (path == "/original.lrc") "[00:01.234]original" else "[00:01.234]translation"
                    val body = text.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                }
            } } catch (_: java.io.IOException) { }
        }
        try {
            assertTrue(Lyrics(context).load(song.id, song.source).isEmpty())
            downloads.start(song, "128k") { _, _ -> error("audio must use the cache") }
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "cmd jobscheduler run -f ${context.packageName} ${0x4c584c52}"
            )).use { it.readBytes() }
            var task: DownloadTask
            withTimeout(20_000) {
                do { delay(80); task = downloads.list().single { it.song == song } }
                while (task.lyricStatus != DownloadLyricStatus.SAVED)
            }
            task = downloads.list().single { it.song == song }
            assertEquals(DownloadStatus.COMPLETE, task.status)
            assertEquals(listOf("/original.lrc", "/translation.lrc"), requests.toList())
            assertEquals("[00:01.234]original\n[00:01.234]translation\n", bytes(task.lyricUri!!).toString(Charsets.UTF_8))
            assertEquals("translation", Lyrics(context).load(song.id, song.source).single().translation)
        } finally { cleanup(song, downloads); server.close(); worker.join(1000) }
    }

    @Test fun lyricFailuresAndRemovalDuringLyricFetchKeepAudioIndependentAndPreventLateFiles() = runBlocking {
        permissions()
        val song = song()
        store(song, "128k", ByteArray(8192))
        val downloads = Downloads(context, scheduleLyrics = {})
        try {
            downloads.start(song, "128k") { _, _ -> error("unexpected network") }
            val task = downloads.list().single { it.song == song }
            repeat(3) { downloads.saveLyrics(task.key) { error("lyric server unavailable") } }
            val failed = downloads.list().single { it.key == task.key }
            assertEquals(DownloadStatus.COMPLETE, failed.status)
            assertEquals(DownloadLyricStatus.FAILED, failed.lyricStatus)
            assertTrue(exists(failed.localUri!!))
            downloads.remove(failed)
            downloads.start(song, "128k") { _, _ -> error("unexpected network") }
            val unavailable = downloads.list().single { it.song == song }
            downloads.saveLyrics(unavailable.key) { emptyList() }
            val noLyrics = downloads.list().single { it.song == song }
            assertEquals(DownloadStatus.COMPLETE, noLyrics.status)
            assertEquals(DownloadLyricStatus.UNAVAILABLE, noLyrics.lyricStatus)
            assertNull(noLyrics.lyricUri)
            downloads.remove(noLyrics)
            downloads.start(song, "128k") { _, _ -> error("unexpected network") }
            val next = downloads.list().single { it.song == song }
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val work = async { downloads.saveLyrics(next.key) { entered.complete(Unit); release.await(); listOf(LyricLine(0, "test")) } }
            entered.await(); downloads.remove(next); release.complete(Unit); work.await()
            assertFalse(downloads.list().any { it.song == song })
            assertFalse(exists(next.localUri!!))
        } finally { cleanup(song, downloads) }
    }
}
