package cn.toside.music.nativeapp.player

import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.data.DownloadStatus
import cn.toside.music.nativeapp.data.Downloads
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Explicit opt-in: uses the real network, speaker, queue and Downloads; keeps the successful download. */
@RunWith(AndroidJUnit4::class)
class BuiltinKuwoPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(action: () -> Unit) { instrumentation.runOnMainSync(action); instrumentation.waitForIdleSync() }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private suspend fun waitFor(timeout: Long, condition: () -> Boolean) = withTimeout(timeout) {
        while (!condition()) delay(100)
    }

    @Test fun streamsAndDownloads320kWithoutLowerQualityFallback() = runBlocking {
        assumeTrue("Pass liveKuwoPlayback=true to play and download the real example song",
            InstrumentationRegistry.getArguments().getString("liveKuwoPlayback") == "true")
        val quality = context.getSharedPreferences("player", Context.MODE_PRIVATE)
        val settings = context.getSharedPreferences("app-settings", Context.MODE_PRIVATE)
        val oldQuality = quality.getString("quality", null)
        val oldLower = settings.all["player.autoLowerQuality"] as? Boolean
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        waitFor(15_000) { field(activity, "service") != null }
        val player = field(activity, "service") as PlaybackService
        val oldSource = player.sourceCatalog().selected
        var complete = false
        try {
            quality.edit().putString("quality", "320k").commit()
            settings.edit().putBoolean("player.autoLowerQuality", false).commit()
            val source = player.sourceCatalog().entries.single { it.name.startsWith("OIAPI 酷我") }
            player.selectSource(source.id)
            val info = JSONObject().put("songmid", "442087398").put("name", "茶花开了").put("singer", "王睿卓")
                .put("albumName", "茶花开了").put("_types", JSONObject().put("320k", JSONObject()).put("128k", JSONObject()))
            val song = Song(442087398, Uri.EMPTY, "茶花开了", "王睿卓", 270_000, "kw", info.toString())
            main {
                val index = player.queueState.value?.let { it.index + 1 } ?: 0
                player.enqueue(listOf(song), next = true)
                player.playQueued(index)
            }
            waitFor(35_000) { player.state.value.error != null || player.state.value.song?.id == song.id && player.state.value.playing }
            assertNull(player.state.value.error)
            var bitrate = -1
            var sampleRate = -1
            var start = 0
            main {
                val engine = (field(player, "nativePlayer") as NativeAudioPlayer).engine
                bitrate = engine.audioFormat?.averageBitrate ?: -1
                sampleRate = engine.audioFormat?.sampleRate ?: -1
                start = engine.currentPosition.toInt()
            }
            assertEquals("Decoder must receive 320 kbps, not a declared quality label", 320_000, bitrate)
            delay(6_000)
            assertNull(player.state.value.error)
            assertTrue(player.state.value.playing)
            var end = 0
            main { end = (field(player, "nativePlayer") as NativeAudioPlayer).engine.currentPosition.toInt(); player.pause() }
            assertTrue("Playback position must advance", end - start >= 4_000)

            val downloads = Downloads(context)
            val previous = downloads.list().map { it.key }.toSet()
            assertTrue(downloads.start(song, "320k", resolve = player::resolveDownloadUrl))
            var task = downloads.list().single { it.key !in previous }
            withTimeout(180_000) {
                while (task.status != DownloadStatus.COMPLETE && !task.failed) {
                    delay(500)
                    task = downloads.list().single { it.key == task.key }
                }
            }
            assertEquals("Full download must complete", DownloadStatus.COMPLETE, task.status)
            assertEquals("320k", task.quality)
            val downloaded = requireNotNull(downloads.downloadedSong(task))
            val extractor = MediaExtractor()
            val retriever = MediaMetadataRetriever()
            var fileBitrate = -1
            var duration = 0L
            try {
                context.contentResolver.openFileDescriptor(downloaded.uri, "r")!!.use { descriptor ->
                    extractor.setDataSource(descriptor.fileDescriptor)
                    val audio = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                        .single { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    fileBitrate = audio.getInteger(MediaFormat.KEY_BIT_RATE)
                }
                retriever.setDataSource(context, downloaded.uri)
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            } finally { extractor.release(); retriever.release() }
            assertEquals(320_000, fileBitrate)
            assertTrue("Downloaded file must contain the full 4:30 song", duration in 269_000..271_000)
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            context.contentResolver.openInputStream(downloaded.uri)!!.use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count); bytes += count
                }
            }
            assertEquals(10_802_282L, bytes)
            val report = JSONObject().put("device", Build.MODEL).put("version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                .put("song", song.title).put("artist", song.artist).put("source", source.name).put("quality", "320k")
                .put("autoLowerQuality", false).put("playbackBitrate", bitrate).put("sampleRate", sampleRate)
                .put("playbackStartMs", start).put("playbackEndMs", end).put("downloadStatus", task.status.name)
                .put("downloadBitrate", fileBitrate).put("downloadBytes", bytes).put("durationMs", duration)
                .put("downloadUri", downloaded.uri.toString()).put("fileName", task.fileName)
                .put("sha256", digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
            File(context.getExternalFilesDir(null), "verification-320.json").writeText(report.toString(2))
            complete = true
        } finally {
            main { player.pause() }
            if (!complete) {
                quality.edit().apply { if (oldQuality == null) remove("quality") else putString("quality", oldQuality) }.commit()
                settings.edit().apply { if (oldLower == null) remove("player.autoLowerQuality") else putBoolean("player.autoLowerQuality", oldLower) }.commit()
                player.selectSource(oldSource)
            }
        }
    }
}
