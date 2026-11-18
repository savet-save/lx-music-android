@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package cn.toside.music.nativeapp.data

import android.app.job.JobScheduler
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.datasource.cache.ContentMetadataMutations
import cn.toside.music.nativeapp.player.AudioStreamCache
import cn.toside.music.nativeapp.MultiSourceAudioServer
import cn.toside.music.nativeapp.source.SourceAudio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class DownloadCoverTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun downloads() = Downloads(context, scheduleLyrics = {}, scheduleFallback = {}, scheduleConversion = {})
    private fun bytes(uri: Uri) = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
    private fun bitmap() = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

    private suspend fun fixture(url: String = "", block: suspend (Downloads, DownloadTask, ByteArray) -> Unit) {
        val file = AacTestAudio.create(context)
        val song = Song(System.nanoTime(), Uri.EMPTY, "Cover fixture ${System.nanoTime()}", "Fixture", 3000, "bili",
            JSONObject().put("songmid", "cover-${System.nanoTime()}").put("img", url).toString())
        val downloads = downloads()
        val cache = AudioStreamCache.get(context)
        val key = AudioStreamCache.key(song, "original")
        try {
            val payload = file.readBytes()
            cache.updateLimit(16L * 1024 * 1024)
            val hole = cache.cache.startReadWrite(key, 0, payload.size.toLong())
            try {
                val target = cache.cache.startFile(key, 0, payload.size.toLong())
                target.writeBytes(payload); cache.cache.commitFile(target, payload.size.toLong())
            } finally { cache.cache.releaseHoleSpan(hole) }
            cache.cache.applyContentMetadataMutations(key, ContentMetadataMutations().also {
                ContentMetadataMutations.setContentLength(it, payload.size.toLong())
            })
            cache.remember(key, Uri.parse("https://offline.invalid/audio.m4a"))
            assertTrue(downloads.startWithSources(song, "original") { _, _, _ -> error("Audio is already cached") })
            block(downloads, downloads.list().single { it.song.id == song.id }, payload)
        } finally {
            downloads.list().filter { it.song.id == song.id || it.song.title == song.title }.forEach { downloads.remove(it) }
            cache.removeSong(AudioStreamCache.key(song, "")); Artwork(context).forget(url); file.delete()
        }
    }

    @Test fun oldDownloadsReuseCachedCoverOfflineAndLocalPlaybackKeepsItAfterCacheRemoval() = runBlocking {
        CoverServer().use { server ->
            assertNotNull(Artwork(context).loadUrl(server.url))
            assertEquals(1, server.requests.get())
            server.close()
            fixture(server.url) { _, original, payload ->
                val history = context.getSharedPreferences("music-downloads", 0)
                val legacy = JSONObject(requireNotNull(history.getString(original.key, null)))
                legacy.remove("coverStatus"); legacy.remove("coverUri"); legacy.remove("coverAttempts")
                history.edit().putString(original.key, legacy.toString()).commit()
                val reopened = downloads()
                assertTrue(reopened.pendingCovers().any { it.key == original.key })
                assertFalse(reopened.saveCover(original.key))
                val saved = reopened.list().single { it.key == original.key }
                assertEquals(DownloadCoverStatus.SAVED, saved.coverStatus)
                assertEquals(original.fileName!!.substringBeforeLast('.') + ".jpg", ExternalAudio.name(context, saved.coverUri!!))
                val cover = bytes(saved.coverUri!!)
                assertEquals(0xff, cover[0].toInt() and 255); assertEquals(0xd8, cover[1].toInt() and 255)
                assertNotNull(BitmapFactory.decodeByteArray(cover, 0, cover.size))
                Artwork(context).forget(server.url)
                val local = requireNotNull(reopened.downloadedSong(saved)).copy(id = System.nanoTime())
                assertNotNull(Artwork(context).load(local))
                assertArrayEquals(payload, bytes(saved.localUri!!))
                assertEquals(1, server.requests.get())
                reopened.remove(saved)
                assertTrue(runCatching { bytes(saved.coverUri!!) }.isFailure)
                assertTrue(runCatching { bytes(saved.localUri!!) }.isFailure)
            }
        }
    }

    @Test fun transientCoverHttpFailureRetriesWithoutChangingCompletedAudio() = runBlocking {
        CoverServer(failures = 1).use { server -> fixture(server.url) { downloads, original, payload ->
            assertTrue(downloads.saveCover(original.key))
            assertEquals(DownloadCoverStatus.PENDING, downloads.list().single { it.key == original.key }.coverStatus)
            assertFalse(downloads.saveCover(original.key))
            val saved = downloads.list().single { it.key == original.key }
            assertEquals(DownloadCoverStatus.SAVED, saved.coverStatus)
            assertEquals(DownloadStatus.COMPLETE, saved.status)
            assertArrayEquals(payload, bytes(saved.localUri!!)); assertEquals(2, server.requests.get())
        } }
    }

    @Test fun coverFailureStopsAfterThreeAttemptsAndNeverFailsOrDeletesAudio() = runBlocking {
        fixture { downloads, original, payload ->
            repeat(3) { attempt ->
                assertEquals(attempt < 2, downloads.saveCover(original.key) { throw IOException("Cover unavailable") })
            }
            val failed = downloads().list().single { it.key == original.key }
            assertEquals(DownloadCoverStatus.FAILED, failed.coverStatus)
            assertEquals(DownloadStatus.COMPLETE, failed.status)
            assertFalse(downloads.pendingCovers().any { it.key == original.key })
            assertFalse(downloads.saveCover(original.key) { error("Exhausted covers must not be requested again") })
            assertArrayEquals(payload, bytes(failed.localUri!!))
        }
    }

    @Test fun removingTaskDuringCoverFetchDoesNotRestoreTheTaskOrLeaveACoverFile() = runBlocking {
        fixture { downloads, original, _ ->
            val started = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
            val work = async { downloads.saveCover(original.key) { started.complete(Unit); resume.await(); bitmap() } }
            started.await(); downloads.remove(original); resume.complete(Unit); work.await()
            assertFalse(downloads.list().any { it.key == original.key })
            val name = original.fileName!!.substringBeforeLast('.') + ".jpg"
            context.contentResolver.query(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf("_id"), "_display_name=?", arrayOf(name), null)!!.use { assertEquals(0, it.count) }
        }
    }

    @Test fun backgroundJobSavesCoverAndManualMp3ConversionRetainsBothOriginalFiles() = runBlocking {
        CoverServer().use { server -> fixture(server.url) { downloads, original, payload ->
            DownloadCoverJob.schedule(context)
            val job = context.getSystemService(JobScheduler::class.java).allPendingJobs.firstOrNull {
                it.service.className.endsWith("DownloadCoverJob")
            }
            if (job != null) ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "cmd jobscheduler run -f ${context.packageName} ${job.id}")).use { it.readBytes() }
            withTimeout(20_000) {
                while (downloads().list().single { it.key == original.key }.coverStatus != DownloadCoverStatus.SAVED) delay(80)
            }
            val saved = downloads.list().single { it.key == original.key }
            val originalCover = bytes(saved.coverUri!!)
            val local = requireNotNull(downloads.downloadedSong(saved))
            assertTrue(downloads.startMp3Conversion(local))
            val conversion = downloads.list().single { it.isConversion && it.song.title == original.song.title }
            assertTrue(downloads.convertMp3(conversion.key)); assertFalse(downloads.saveCover(conversion.key))
            val converted = downloads.list().single { it.key == conversion.key }
            assertEquals(DownloadCoverStatus.SAVED, converted.coverStatus)
            assertNotEquals(saved.coverUri, converted.coverUri)
            downloads.remove(converted)
            assertArrayEquals(payload, bytes(saved.localUri!!)); assertArrayEquals(originalCover, bytes(saved.coverUri!!))
        } }
    }

    @Test fun systemDownloadCoverIsFoundByUriWhenProviderDisplayNameIsTheSongTitle() = runBlocking {
        val file = AacTestAudio.create(context)
        val downloads = downloads()
        val coverServer = CoverServer()
        val song = Song(System.nanoTime(), Uri.EMPTY, "系统下载封面 ${System.nanoTime()}", "UP · Fixture", 3000, "bili",
            JSONObject().put("songmid", "system-cover-${System.nanoTime()}").put("bvid", "fixture").put("cid", 1)
                .put("img", coverServer.url).toString())
        try {
            MultiSourceAudioServer(file.readBytes()).use { server ->
                assertTrue(downloads.startWithSources(song, "original") { _, quality, _ -> SourceAudio(server.url("good"), quality) })
                val key = downloads.list().single { it.song.id == song.id }.key
                val complete = BilibiliMp3Test().waitFor(downloads, key)
                assertFalse(downloads.saveCover(key))
                assertEquals(DownloadCoverStatus.SAVED, downloads.list().single { it.key == key }.coverStatus)
                val local = requireNotNull(downloads.downloadedSong(complete))
                assertNotNull("System URI ${local.uri}, display ${ExternalAudio.name(context, local.uri)}", Artwork(context).load(local))
                assertArrayEquals(file.readBytes(), bytes(local.uri))
            }
        } finally {
            downloads.list().filter { it.song.id == song.id }.forEach { downloads.remove(it) }
            Artwork(context).forget(coverServer.url); coverServer.close(); file.delete()
        }
    }

    private class CoverServer(private val failures: Int = 0) : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/cover.png"
        val requests = AtomicInteger()
        private val png = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE); compress(Bitmap.CompressFormat.PNG, 100, out); recycle()
            }
        }.toByteArray()
        private val worker = thread(isDaemon = true) {
            try { while (!server.isClosed) server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                val failed = requests.incrementAndGet() <= failures
                socket.getOutputStream().apply {
                    write("HTTP/1.1 ${if (failed) "503 Unavailable" else "200 OK"}\r\nContent-Type: image/png\r\nContent-Length: ${if (failed) 0 else png.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    if (!failed) write(png)
                }
            } } catch (_: IOException) { }
        }
        override fun close() { server.close(); worker.join(1000) }
    }
}
