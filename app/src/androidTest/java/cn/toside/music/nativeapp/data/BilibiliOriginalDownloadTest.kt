@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package cn.toside.music.nativeapp.data

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.player.AudioStreamCache
import cn.toside.music.nativeapp.MultiSourceAudioServer
import cn.toside.music.nativeapp.source.SourceAudio
import cn.toside.music.nativeapp.lyrics.Lyrics
import cn.toside.music.nativeapp.lyrics.LyricLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BilibiliOriginalDownloadTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun downloads() = Downloads(context, scheduleLyrics = {}, scheduleFallback = {}, scheduleConversion = {})
    private fun song() = Song(System.nanoTime(), Uri.EMPTY, "AAC original fixture", "UP · Fixture", 3000, "bili",
        """{"songmid":"fixture-${System.nanoTime()}","bvid":"invalid-offline-id"}""")
    private fun cache(song: Song, quality: String, payload: ByteArray) {
        val cache = AudioStreamCache.get(context)
        cache.updateLimit(16L * 1024 * 1024)
        val key = AudioStreamCache.key(song, quality)
        val hole = cache.cache.startReadWrite(key, 0, payload.size.toLong())
        try {
            val file = cache.cache.startFile(key, 0, payload.size.toLong())
            file.writeBytes(payload); cache.cache.commitFile(file, payload.size.toLong())
        } finally { cache.cache.releaseHoleSpan(hole) }
        cache.cache.applyContentMetadataMutations(key, ContentMetadataMutations().also {
            ContentMetadataMutations.setContentLength(it, payload.size.toLong())
        })
        cache.remember(key, Uri.parse("https://offline.invalid/audio.m4a"))
    }
    private fun bytes(uri: Uri) = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
    private fun mime(uri: Uri): String {
        val extractor = MediaExtractor()
        return try { extractor.setDataSource(context, uri, null); requireNotNull(extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME)) }
        finally { extractor.release() }
    }
    private suspend fun cleanup(song: Song, downloads: Downloads) {
        downloads.list().filter { it.song.id == song.id }.forEach { downloads.remove(it) }
        AudioStreamCache.get(context).removeSong(AudioStreamCache.key(song, ""))
    }

    @Test fun completeAacCacheExportsOfflinePlaysAndManualConversionKeepsTheOriginal() = runBlocking {
        val file = AacTestAudio.create(context)
        val song = song(); val downloads = downloads(); val payload = file.readBytes()
        try {
            cache(song, "original", payload)
            assertTrue(downloads.startWithSources(song, "original") { _, _, _ -> error("No metadata or audio request is allowed") })
            val original = downloads.list().single { it.song.id == song.id }
            assertEquals(DownloadStatus.COMPLETE, original.status); assertNull(original.systemId)
            assertTrue(original.fileName!!.endsWith(".m4a")); assertArrayEquals(payload, bytes(original.localUri!!))
            assertEquals("audio/mp4a-latm", mime(original.localUri!!)); assertTrue(downloads.pendingConversions().isEmpty())
            val player = MediaPlayer()
            try {
                player.setDataSource(context, original.localUri!!); player.prepare(); player.start()
                android.os.SystemClock.sleep(200); assertTrue(player.isPlaying); assertTrue(player.currentPosition > 0)
            } finally { player.release() }
            assertTrue(downloads.startMp3Conversion(song)); assertFalse(downloads.startMp3Conversion(song))
            // Reopen the queue to exercise the persisted input URI, rather than an in-memory task.
            val reopened = downloads()
            val conversion = reopened.list().single { it.song.id == song.id && it.isConversion }
            assertEquals(DownloadStatus.CONVERTING, conversion.status)
            assertTrue(reopened.convertMp3(conversion.key))
            val result = reopened.list().single { it.key == conversion.key }
            assertEquals(DownloadStatus.COMPLETE, result.status); assertEquals("audio/mpeg", mime(result.localUri!!))
            assertArrayEquals(payload, bytes(original.localUri!!))
            reopened.remove(result)
            assertArrayEquals(payload, bytes(original.localUri!!))
            assertNotNull(AudioStreamCache.get(context).completeAudio(song, "original"))
        } finally { cleanup(song, downloads); file.delete() }
    }

    @Test fun theSelectedLowerBitrateUsesItsOwnCacheWithoutResolvingOrTranscoding() = runBlocking {
        val file = AacTestAudio.create(context, 64000)
        val song = song(); val downloads = downloads(); val payload = file.readBytes()
        try {
            cache(song, "original", ByteArray(100))
            cache(song, "aac-30216", payload)
            assertTrue(downloads.startWithSources(song, "aac-30216") { _, _, _ -> error("Do not resolve a cached bitrate") })
            val task = downloads.list().single { it.song.id == song.id }
            assertEquals("aac-30216", task.quality); assertEquals(DownloadStatus.COMPLETE, task.status)
            assertArrayEquals(payload, bytes(task.localUri!!)); assertEquals("audio/mp4a-latm", mime(task.localUri!!))
            assertTrue(downloads.pendingConversions().isEmpty())
        } finally { cleanup(song, downloads); file.delete() }
    }

    @Test fun uncachedAacDownloadsAsTheOriginalFileWithoutAnAutomaticMp3Task() = runBlocking {
        val file = AacTestAudio.create(context)
        val song = song().copy(sourceInfo = """{"songmid":"network-${System.nanoTime()}","bvid":"BV1xx411c7mD","cid":1}""")
        val downloads = downloads()
        try {
            Lyrics(context).cache(song, listOf(LyricLine(0, "Fixture")))
            MultiSourceAudioServer(file.readBytes()).use { server ->
                assertTrue(downloads.startWithSources(song, "aac-30216") { _, quality, _ -> SourceAudio(server.url("good"), quality) })
                val key = downloads.list().single { it.song.id == song.id }.key
                val task = BilibiliMp3Test().waitFor(downloads, key)
                assertEquals(DownloadStatus.COMPLETE, task.status); assertNotNull(task.systemId)
                assertTrue(task.fileName!!.endsWith(".m4a")); assertFalse(task.isConversion)
                val local = requireNotNull(downloads.downloadedSong(task))
                assertArrayEquals(file.readBytes(), bytes(local.uri)); assertEquals("audio/mp4a-latm", mime(local.uri))
                assertTrue(downloads.pendingConversions().isEmpty())
                assertTrue(server.requestHeaders.all { "cookie" !in it })
            }
        } finally { cleanup(song, downloads); Lyrics(context).clearOnlineCache(song); file.delete() }
    }

    @Test fun explicitAudioTrackReusesTheSameOriginalPlaybackCacheButNeverAnotherTrack() = runBlocking {
        val file = AacTestAudio.create(context)
        val song = song(); val downloads = downloads()
        val cache = AudioStreamCache.get(context)
        try {
            cache(song, "original", file.readBytes())
            cache.remember(AudioStreamCache.key(song, "original"), Uri.parse("https://offline.invalid/audio.m4a"), 192000, 3000, 30280)
            assertNotNull(cache.completeAudio(song, "aac-30280"))
            assertNull(cache.completeAudio(song, "aac-30216"))
            assertTrue(downloads.startWithSources(song, "aac-30280") { _, _, _ -> error("The matching track is already cached") })
            val task = downloads.list().single { it.song.id == song.id }
            assertEquals(DownloadStatus.COMPLETE, task.status); assertArrayEquals(file.readBytes(), bytes(task.localUri!!))
        } finally { cleanup(song, downloads); file.delete() }
    }

    @Test fun failedManualConversionCanRetryWithoutAnAudioSourceAndNeverDeletesItsInput() = runBlocking {
        val valid = AacTestAudio.create(context)
        val original = File.createTempFile("broken-conversion-", ".m4a", context.cacheDir).apply { writeText("invalid audio") }
        val song = Song(System.nanoTime(), Uri.fromFile(original), "retry fixture", "", 600000)
        val downloads = downloads()
        try {
            assertTrue(downloads.startMp3Conversion(song))
            val task = downloads.list().single { it.song.id == song.id }
            assertFalse(downloads.convertMp3(task.key)); assertTrue(original.isFile)
            original.writeBytes(valid.readBytes())
            assertTrue(downloads.retryMp3Conversion(task.key)); assertTrue(downloads.convertMp3(task.key))
            val result = downloads.list().single { it.key == task.key }
            assertEquals("audio/mpeg", mime(result.localUri!!)); downloads.remove(result)
            assertArrayEquals(valid.readBytes(), original.readBytes())
        } finally { cleanup(song, downloads); original.delete(); valid.delete() }
    }
}
