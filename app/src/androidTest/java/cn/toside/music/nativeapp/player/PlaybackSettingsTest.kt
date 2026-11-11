@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package cn.toside.music.nativeapp.player

import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.AppPreferences
import cn.toside.music.nativeapp.data.Artwork
import cn.toside.music.nativeapp.data.ExternalAudio
import cn.toside.music.nativeapp.data.LegacyBackup
import cn.toside.music.nativeapp.data.Playlist
import cn.toside.music.nativeapp.data.Playlists
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.lyrics.LyricLine
import cn.toside.music.nativeapp.lyrics.Lyrics
import cn.toside.music.nativeapp.ui.DesktopLyricSettings
import cn.toside.music.nativeapp.ui.MainActivity
import cn.toside.music.nativeapp.ui.PlayerLyricScroll
import cn.toside.music.nativeapp.ui.UiPalette
import cn.toside.music.nativeapp.ui.SettingsFragment
import cn.toside.music.nativeapp.online.PlaylistBrowserRepository
import cn.toside.music.nativeapp.online.PlaylistBrowseQuery
import cn.toside.music.nativeapp.online.OnlinePlaylist
import cn.toside.music.nativeapp.online.OnlineTagGroup
import cn.toside.music.nativeapp.online.Board
import cn.toside.music.nativeapp.online.ChartsRepository
import cn.toside.music.nativeapp.online.NativeChartsRepository
import cn.toside.music.nativeapp.utils.PermissionPolicy

import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.AtomicFile
import android.util.TypedValue
import android.view.Gravity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ListView
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlaybackSettingsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(action: () -> Unit) { instrumentation.runOnMainSync(action); instrumentation.waitForIdleSync() }
    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(12_000) { while (!condition()) delay(40) }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun invoke(activity: MainActivity, name: String) = main {
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    }
    private fun find(view: View, description: String): View? {
        if (view.contentDescription == description) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index), description)?.let { return it }
        return null
    }
    private fun progress(root: View, description: String, value: Int) = main {
        val view = find(root, description) as SeekBar
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value.toFloat()) }))
    }
    private fun restore(store: SharedPreferences, snapshot: Map<String, *>) {
        store.edit().clear().apply { snapshot.forEach { (key, value) -> when (value) {
            is String -> putString(key, value); is Float -> putFloat(key, value)
            is Int -> putInt(key, value); is Long -> putLong(key, value); is Boolean -> putBoolean(key, value)
        } } }.commit()
    }
    private inner class Fixture(val file: File, val id: Long) {
        var activity = open()
        lateinit var player: PlaybackService
        val song = Song(id, Uri.fromFile(file), "settings test", "test artist", 30_000)
        private fun open() = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        suspend fun bind() {
            waitFor { !activity.isFinishing && activity.hasWindowFocus() && field(activity, "service") != null }
            player = field(activity, "service") as PlaybackService
        }
        fun media() = field(player, "player") as MediaPlayer
        suspend fun play() {
            main { player.play(listOf(song), 0) }
            waitFor { player.state.value.song?.id == song.id && !player.state.value.preparing }
            assertNull(player.state.value.error)
            assertTrue(player.state.value.playing)
        }
        suspend fun restart() {
            val previous = player
            main { player.pause(); activity.finish() }
            context.stopService(Intent(context, PlaybackService::class.java))
            waitFor { activity.isDestroyed && (field(previous, "scope") as CoroutineScope).coroutineContext[Job]!!.isCompleted }
            activity = open(); bind()
            if (File(context.filesDir, "playback-queue.json").isFile) waitFor { player.queueState.value != null }
        }
        suspend fun close() {
            main { player.pause(); activity.finish() }
            context.stopService(Intent(context, PlaybackService::class.java))
            waitFor { (field(player, "scope") as CoroutineScope).coroutineContext[Job]!!.isCompleted }
        }
    }
    private suspend fun withPlayback(test: suspend (Fixture) -> Unit) {
        val preferences = context.getSharedPreferences("player", Context.MODE_PRIVATE)
        val saved = preferences.all.toMap()
        val offsetPreferences = context.getSharedPreferences("lyric-offsets", Context.MODE_PRIVATE)
        val offsetsSaved = offsetPreferences.all.toMap()
        val historyPreferences = context.getSharedPreferences("play-history", Context.MODE_PRIVATE)
        val historySaved = historyPreferences.all.toMap()
        val generalPreferences = context.getSharedPreferences("app-settings", Context.MODE_PRIVATE)
        val generalSaved = generalPreferences.all.toMap()
        generalPreferences.edit().clear().putBoolean("common.isAgreePact", true).putBoolean("version.autoCheck", false).putString("common.langId", "zh-CN").commit()
        val queue = AtomicFile(File(context.filesDir, "playback-queue.json"))
        val savedQueue = if (queue.baseFile.isFile) queue.openRead().use { it.readBytes() } else null
        val id = System.nanoTime()
        val file = File(context.cacheDir, "playback-settings-$id.wav")
        val buffer = ByteBuffer.allocate(44 + 16000 * 30 * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(buffer.capacity() - 8).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(buffer.capacity() - 44)
        file.writeBytes(buffer.array()) // A silent PCM track exercises the real decoder without producing sound.
        val fixture = Fixture(file, id)
        fixture.bind()
        assumeFalse(fixture.player.state.value.playing || fixture.player.state.value.preparing)
        try {
            main { fixture.player.setPlaybackRate(1f); fixture.player.setVolume(1f) }
            test(fixture)
        } finally {
            fixture.close()
            restore(preferences, saved); restore(offsetPreferences, offsetsSaved)
            restore(historyPreferences, historySaved)
            restore(generalPreferences, generalSaved)
            queue.delete()
            if (savedQueue != null) { val stream = queue.startWrite(); stream.write(savedQueue); queue.finishWrite(stream) }
            File(context.filesDir, "lyrics/$id.lrc").delete(); file.delete()
        }
    }

    @Test fun queueEditsPreserveCurrentPlaybackAndPersistClearWithoutStaleWrites() = runBlocking {
        withPlayback { fixture ->
            fixture.play()
            val b = fixture.song.copy(id = fixture.id + 1, title = "B")
            val c = fixture.song.copy(id = fixture.id + 2, title = "C")
            main {
                fixture.player.enqueue(listOf(b)); fixture.player.enqueue(listOf(c), next = true)
                assertEquals(listOf(fixture.song, c, b), fixture.player.queueState.value!!.songs)
                fixture.player.moveQueued(0, 2)
                assertEquals(2, fixture.player.queueState.value!!.index)
                assertEquals(fixture.song, fixture.player.state.value.song)
                assertTrue(fixture.media().isPlaying)
                fixture.player.removeQueued(0)
                assertEquals(1, fixture.player.queueState.value!!.index)
                fixture.player.pause(); fixture.player.removeQueued(1)
                assertEquals(b, fixture.player.state.value.song)
                assertFalse(fixture.player.state.value.playing)
            }
            fixture.restart()
            waitFor { fixture.player.queueState.value?.songs == listOf(b) }
            main { fixture.player.enqueue(listOf(c)); fixture.player.clearQueue() }
            waitFor { !File(context.filesDir, "playback-queue.json").exists() }
            fixture.restart(); delay(250)
            assertNull(fixture.player.state.value.song)
            assertNull(fixture.player.queueState.value)
        }
    }
    @Test fun sleepAtEndStopsInsteadOfAdvancingAndCanBeCancelled() = runBlocking {
        withPlayback { fixture ->
            fixture.play()
            main {
                fixture.player.enqueue(listOf(fixture.song.copy(id = fixture.id + 1)))
                fixture.player.setSleepTimer(0, true)
                fixture.player.seekTo(29_600)
            }
            waitFor { !fixture.player.state.value.playing }
            main {
                assertFalse(fixture.player.state.value.playing)
                assertFalse(fixture.media().isPlaying)
                assertFalse(fixture.player.sleepAfterCurrent)
                assertEquals(fixture.song, fixture.player.state.value.song)
                fixture.player.setSleepTimer(0, true); fixture.player.setSleepTimer(0)
                assertFalse(fixture.player.sleepAfterCurrent)
            }
        }
    }
    @Test fun openedAudioIsCopiedAndDeepLinkControlsAnExistingActivity() = runBlocking {
        withPlayback { fixture ->
            val privateDirectory = File(context.cacheDir, "owned-audio-test-${System.nanoTime()}").apply { mkdirs() }
            val privateContext = object : ContextWrapper(context) { override fun getFilesDir(): File = privateDirectory }
            val opened = ExternalAudio.import(privateContext, Uri.fromFile(fixture.file))
            try {
                assertTrue(File(opened.uri.path!!).isFile)
                assertEquals(30_000L, opened.duration)
                val paths = ExternalAudio.existing(privateContext)
                assertEquals(opened, paths[opened.uri.toString()])
                val backup = LegacyBackup.encode(listOf(Playlist(1, "private") to listOf(opened)), emptyMap())
                assertFalse(LegacyBackup.containsLocalSongs(backup, paths.keys))
                val restored = LegacyBackup.parse(backup, paths)
                assertEquals(0, restored.skipped); assertEquals(opened, restored.lists.single().songs.single())
                val copy = File(privateDirectory, "same.wav").apply { writeBytes(fixture.file.readBytes()) }
                assertEquals(opened.id, ExternalAudio.import(privateContext, Uri.fromFile(copy)).id)
                main { fixture.player.play(listOf(opened), 0) }
                waitFor { fixture.player.state.value.playing || fixture.player.state.value.error != null }
                assertTrue("opened audio: ${fixture.player.state.value}", fixture.player.state.value.playing)
                main { instrumentation.callActivityOnNewIntent(fixture.activity, Intent(Intent.ACTION_VIEW, Uri.parse("lxmusic://player/pause"))) }
                waitFor { !fixture.player.state.value.playing }
                main { instrumentation.callActivityOnNewIntent(fixture.activity, Intent(Intent.ACTION_VIEW, Uri.parse("lxmusic://player/play"))) }
                waitFor { fixture.player.state.value.playing }
                main { fixture.player.clearQueue() }
            } finally { privateDirectory.deleteRecursively() }
        }
    }
    @Test fun generalPlaybackPoliciesPersistWithoutSavingPositionWhenDisabled() = runBlocking {
        withPlayback { fixture ->
            val preferences = AppPreferences(context)
            preferences.set("player.isHandleAudioFocus", false)
            preferences.set("player.isSavePlayTime", false)
            fixture.play()
            main { fixture.player.seekTo(5000); fixture.player.pause() }
            assertEquals(0, fixture.player.queueState.value!!.position)
            fixture.restart()
            assertEquals(0, fixture.player.state.value.position)
            assertFalse(fixture.player.state.value.playing)
            preferences.set("player.startupAutoPlay", true)
            fixture.restart()
            waitFor { fixture.player.state.value.playing }
            assertEquals(fixture.song, fixture.player.state.value.song)
        }
    }
    @Test fun audioOffloadPreferenceFallsBackForPcmAndKeepsPauseSpeedAndSeek() = runBlocking {
        withPlayback { fixture ->
            AppPreferences(context).set("player.isEnableAudioOffload", true)
            main { fixture.player.play(listOf(fixture.song), 0) }
            waitFor { fixture.player.state.value.playing }
            val current = field(fixture.player, "nativePlayer") as NativeAudioPlayer
            main {
                assertEquals(androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED,
                    current.engine.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode)
                fixture.player.pause(); fixture.player.setPlaybackRate(1.5f); fixture.player.setVolume(0.25f); fixture.player.seekTo(3000)
                assertFalse(current.isPlaying)
                assertEquals(1.5f, current.engine.playbackParameters.speed, 0.01f)
                assertEquals(0.25f, current.engine.volume, 0.01f)
                assertEquals(3000, fixture.player.state.value.position)
                fixture.player.resume()
            }
            waitFor { fixture.player.state.value.playing }
        }
    }
    @Test fun failedSourceUsesMatchingRecordingFromSupportedPlatformAndCanBeDisabled() = runBlocking {
        val catalog = AtomicFile(File(context.filesDir, "user-sources.json"))
        val saved = if (catalog.baseFile.isFile) catalog.readFully() else null
        try {
            withPlayback { fixture ->
                fixture.player.importSource("""/** @name source fallback test */
                    lx.on(lx.EVENT_NAMES.request, ({source}) => source === 'kw' ? Promise.reject(new Error('unavailable')) : Promise.resolve('http://127.0.0.1/matched.mp3'));
                    lx.send(lx.EVENT_NAMES.inited, {sources: {
                      kw: {type: 'music', actions: ['musicUrl'], qualitys: ['128k']},
                      tx: {type: 'music', actions: ['musicUrl'], qualitys: ['128k']}
                    }});
                """.trimIndent())
                val song = fixture.song.copy(source = "kw", sourceInfo = """{"songmid":"42"}""")
                val matcher = SongMatcher(context, field(fixture.player, "scope") as CoroutineScope) { _, source -> listOf(song.copy(source = source)) }
                main { fixture.player.javaClass.getDeclaredField("matcher").apply { isAccessible = true }.set(fixture.player, matcher) }
                assertEquals("http://127.0.0.1/matched.mp3", fixture.player.resolveUrl(song))
                AppPreferences(context).set("player.autoSwitchSource", false)
                try { fixture.player.resolveUrl(song); fail("disabled fallback must retain the original source error") }
                catch (_: IllegalStateException) { }
            }
        } finally {
            catalog.delete(); if (saved != null) { val stream = catalog.startWrite(); stream.write(saved); catalog.finishWrite(stream) }
        }
    }
    private suspend fun withQualityPlayback(test: suspend (Fixture, Song) -> Unit) {
        val catalog = AtomicFile(File(context.filesDir, "user-sources.json"))
        val saved = if (catalog.baseFile.isFile) catalog.readFully() else null
        try {
            withPlayback { fixture ->
                context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putString("quality", "flac24bit").commit()
                AppPreferences(context).set("player.autoSwitchSource", false)
                val song = fixture.song.copy(source = "kw", uri = Uri.EMPTY, sourceInfo = org.json.JSONObject()
                    .put("songmid", fixture.id).put("_types", org.json.JSONObject()
                        .put("flac24bit", org.json.JSONObject()).put("flac", org.json.JSONObject()).put("320k", org.json.JSONObject())).toString())
                try { test(fixture, song) }
                finally {
                    main { fixture.player.clearQueue() }
                    cn.toside.music.nativeapp.source.PLAYBACK_QUALITIES.forEach { AudioStreamCache.existing()?.remove(AudioStreamCache.key(song, it)) }
                }
            }
        } finally {
            catalog.delete(); if (saved != null) { val stream = catalog.startWrite(); stream.write(saved); catalog.finishWrite(stream) }
        }
    }
    private fun qualityScript(body: String) = """/** @name bitrate fallback fixture */
        let attempts = [];
        lx.on(lx.EVENT_NAMES.request, ({info}) => { attempts.push(info.type); $body });
        lx.send(lx.EVENT_NAMES.inited, {sources: {kw: {type: 'music', actions: ['musicUrl'], qualitys: ['128k','320k','flac','flac24bit']}}});
    """.trimIndent()

    @Test fun failedMusicUrlLowersQualityByDefaultButDisabledAndDownloadsKeepTheirQuality() = runBlocking {
        withQualityPlayback { fixture, song ->
            assertTrue(AppPreferences(context).autoLowerQuality)
            fixture.player.importSource(qualityScript("if (info.type !== '128k') throw new Error('unavailable ' + info.type); return Promise.resolve('http://127.0.0.1/' + attempts.join(','));"))
            assertEquals("http://127.0.0.1/flac24bit,flac,320k,128k", fixture.player.resolveUrl(song))
            assertEquals("flac24bit", context.getSharedPreferences("player", 0).getString("quality", null))
            AppPreferences(context).set("player.autoLowerQuality", false)
            try { fixture.player.resolveUrl(song); fail("disabled fallback must fail at the selected bitrate") }
            catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("unavailable flac24bit")) }
            try { fixture.player.resolveUrl(song.copy(sourceInfo = """{"songmid":42}""")); fail("missing metadata must not lower bitrate when disabled") }
            catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("unavailable flac24bit")) }
            AppPreferences(context).set("player.autoLowerQuality", true)
            try { fixture.player.resolveDownloadUrl(song, "320k"); fail("downloads must keep the exact bitrate") }
            catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("unavailable 320k")) }
        }
    }

    private class QualityAudioServer(private val audio: ByteArray) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val address = "http://127.0.0.1:${server.localPort}"
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var failAll = false
        @Volatile var playableQuality = "320k"
        @Volatile var holdAudio = false
        val audioStarted = CountDownLatch(1)
        val releaseAudio = CountDownLatch(1)
        private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<java.net.Socket>()
        private val worker = Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                sockets.add(socket)
                Thread {
                    try { socket.use { client ->
                        client.soTimeout = 5000
                        val reader = client.getInputStream().bufferedReader()
                        val path = reader.readLine().split(' ')[1].removePrefix("/")
                        while (!reader.readLine().isNullOrEmpty()) { }
                        requests.add(path)
                        if (path == "320k" && holdAudio) { audioStarted.countDown(); releaseAudio.await(8, TimeUnit.SECONDS) }
                        val success = path == playableQuality && !failAll
                        val invalid = path == "flac"
                        val bytes = if (success) audio else if (invalid) "invalid audio".toByteArray() else byteArrayOf()
                        val status = if (success || invalid) "200 OK" else "404 Not Found"
                        client.getOutputStream().apply {
                            write("HTTP/1.1 $status\r\nContent-Type: audio/wav\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    } } catch (_: java.io.IOException) { }
                    finally { sockets.remove(socket) }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
        override fun close() { releaseAudio.countDown(); server.close(); sockets.forEach { it.close() }; worker.join(1000) }
    }

    @Test fun httpAndDecoderFailuresLowerBitrateUseSeparateCachesAndStopWhenExhaustedOrDisabled() = runBlocking {
        withQualityPlayback { fixture, song ->
            QualityAudioServer(fixture.file.readBytes()).use { server ->
                fixture.player.importSource(qualityScript("return Promise.resolve('${server.address}/' + info.type);"))
                main { fixture.player.play(listOf(song), 0) }
                waitFor { fixture.player.state.value.playing }
                assertEquals(listOf("flac24bit", "flac", "320k"), server.requests.distinct())
                assertNull(fixture.player.state.value.error)
                val cache = AudioStreamCache.existing()!!
                waitFor { cache.cachedUri(AudioStreamCache.key(song, "320k")) != null }
                assertNull(cache.cachedUri(AudioStreamCache.key(song, "flac24bit")))
                assertNull(cache.cachedUri(AudioStreamCache.key(song, "flac")))
                assertEquals("flac24bit", context.getSharedPreferences("player", 0).getString("quality", null))
                main { fixture.player.clearQueue() }
                AppPreferences(context).set("player.autoLowerQuality", false)
                server.requests.clear()
                main { fixture.player.play(listOf(song), 0) }
                waitFor { fixture.player.state.value.error != null }
                assertFalse(fixture.player.state.value.preparing)
                assertEquals(listOf("flac24bit"), server.requests.distinct())
                main { fixture.player.clearQueue() }
                AppPreferences(context).set("player.autoLowerQuality", true)
                cache.remove(AudioStreamCache.key(song, "320k"))
                server.failAll = true; server.requests.clear()
                main { fixture.player.play(listOf(song), 0) }
                waitFor { fixture.player.state.value.error != null }
                assertEquals(listOf("flac24bit", "flac", "320k", "128k"), server.requests.distinct())
                assertFalse(fixture.player.state.value.preparing)
                val count = server.requests.size
                delay(400)
                assertEquals(count, server.requests.size)
            }
        }
    }

    @Test fun bitrateRetryKeepsPauseSpeedAndVolumeAndCannotReplaceANewerTrack() = runBlocking {
        withQualityPlayback { fixture, song ->
            QualityAudioServer(fixture.file.readBytes()).use { server ->
                server.holdAudio = true
                fixture.player.importSource(qualityScript("return Promise.resolve('${server.address}/' + info.type);"))
                main { fixture.player.play(listOf(song), 0) }
                assertTrue(server.audioStarted.await(10, TimeUnit.SECONDS))
                main { fixture.player.pause(); fixture.player.setPlaybackRate(1.25f); fixture.player.setVolume(.3f) }
                server.releaseAudio.countDown()
                waitFor { !fixture.player.state.value.preparing }
                assertFalse(fixture.player.state.value.playing)
                assertNull(fixture.player.state.value.error)
                main {
                    val engine = (field(fixture.player, "nativePlayer") as NativeAudioPlayer).engine
                    assertEquals(1.25f, engine.playbackParameters.speed, .01f)
                    assertEquals(.3f, engine.volume, .01f)
                    fixture.player.resume()
                }
                waitFor { fixture.player.state.value.playing }
                server.playableQuality = "128k"
                main {
                    fixture.player.pause(); fixture.player.seekTo(7000)
                    val current = field(fixture.player, "nativePlayer") as NativeAudioPlayer
                    @Suppress("UNCHECKED_CAST")
                    val failed = field(current, "failed") as (NativeAudioPlayer, String) -> Unit
                    failed(current, "position recovery fixture")
                }
                waitFor { !fixture.player.state.value.preparing }
                assertFalse(fixture.player.state.value.playing)
                assertNull(fixture.player.state.value.error)
                assertEquals(7000, fixture.player.state.value.position)
            }
            QualityAudioServer(fixture.file.readBytes()).use { server ->
                main { fixture.player.clearQueue() }
                AudioStreamCache.existing()?.remove(AudioStreamCache.key(song, "320k"))
                server.holdAudio = true
                fixture.player.importSource(qualityScript("return Promise.resolve('${server.address}/' + info.type);"))
                main { fixture.player.play(listOf(song), 0) }
                assertTrue(server.audioStarted.await(10, TimeUnit.SECONDS))
                main { fixture.player.play(listOf(fixture.song), 0) }
                server.releaseAudio.countDown()
                waitFor { fixture.player.state.value.playing }
                assertEquals(fixture.song, fixture.player.state.value.song)
                assertNotNull(field(fixture.player, "player"))
                assertNull(field(fixture.player, "nativePlayer"))
            }
        }
    }
    @Test fun sourceTimeoutLowersBitrateWhileCallerCancellationStopsTheRequest() = runBlocking {
        withQualityPlayback { fixture, song ->
            fixture.player.importSource(qualityScript("if (info.type === 'flac24bit') return new Promise(() => {}); return Promise.resolve('http://127.0.0.1/' + attempts.join(','));"))
            assertEquals("http://127.0.0.1/flac24bit,flac", withTimeout(26_000) { fixture.player.resolveUrl(song) })
            val cancelled = kotlinx.coroutines.withTimeoutOrNull(150) { fixture.player.resolveUrl(song) }
            assertNull(cancelled)
            // The cancelled request must not try the next quality in the background.
            AppPreferences(context).set("player.autoLowerQuality", false)
            assertNull(kotlinx.coroutines.withTimeoutOrNull(150) { fixture.player.resolveUrl(song) })
        }
    }
    @Test fun darkThemeRecreatesActivityWithoutInterruptingPlayback() = runBlocking {
        withPlayback { fixture ->
            fixture.play()
            val original = fixture.player
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            try {
                AppPreferences(context).set("theme.id", "black")
                AppPreferences(context).set("theme.hideBgDark", true)
                main { fixture.activity.recreate() }
                fixture.activity = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as MainActivity
                fixture.bind()
                main {
                    assertSame(original, fixture.player); assertTrue(fixture.media().isPlaying)
                    val palette = field(fixture.activity, "palette") as UiPalette
                    assertTrue(palette.theme.dark)
                    val root = (fixture.activity.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
                    assertEquals(Color.rgb(19, 19, 19), (root.background as android.graphics.drawable.ColorDrawable).color)
                    assertEquals(palette.foreground, (field(fixture.activity, "title") as TextView).currentTextColor)
                }
            } finally { instrumentation.removeMonitor(monitor) }
        }
    }
    @Test fun coverLoadsAfterActivityStopsAndBluetoothLyricsUseServiceMetadata() = runBlocking {
        // This test enters the local library after restarting playback. Prepare its permission so
        // finishing the Activity cannot leave the system permission request over the next test.
        val audioPermission = PermissionPolicy.audio(Build.VERSION.SDK_INT)
        if (context.checkSelfPermission(audioPermission) != PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, audioPermission)
        }
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(audioPermission))
        val catalog = AtomicFile(File(context.filesDir, "user-sources.json"))
        val previous = if (catalog.baseFile.isFile) catalog.readFully() else null
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val allowCover = CountDownLatch(1)
        val imageBytes = java.io.ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }.compress(Bitmap.CompressFormat.PNG, 100, output)
        }.toByteArray()
        val audioBytes = java.util.concurrent.atomic.AtomicReference(byteArrayOf())
        val worker = Thread {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    Thread {
                        runCatching {
                        socket.use { client ->
                            client.soTimeout = 5000
                            val reader = client.getInputStream().bufferedReader()
                            val request = reader.readLine().orEmpty()
                            while (!reader.readLine().isNullOrEmpty()) { }
                            val cover = request.contains("/cover")
                            if (cover) allowCover.await(8, TimeUnit.SECONDS)
                            val data = if (cover) imageBytes else audioBytes.get()
                            client.getOutputStream().use { output ->
                                output.write("HTTP/1.1 200 OK\r\nContent-Length: ${data.size}\r\nContent-Type: ${if (cover) "image/png" else "audio/wav"}\r\nConnection: close\r\n\r\n".toByteArray())
                                output.write(data); output.flush()
                            }
                        }
                        }
                    }.apply { isDaemon = true; start() }
                }
            } catch (_: java.io.IOException) { }
        }.apply { isDaemon = true; start() }
        var lyricSong: Song? = null
        var cacheKey: String? = null
        try {
            withPlayback { fixture ->
                audioBytes.set(fixture.file.readBytes())
                val base = "http://127.0.0.1:${server.localPort}"
                fixture.player.importSource("""/** @name cover test */
                    lx.on(lx.EVENT_NAMES.request, () => Promise.resolve('$base/audio'));
                    lx.send(lx.EVENT_NAMES.inited, {sources: {kw: {type: 'music', actions: ['musicUrl'], qualitys: ['128k']}}});
                """.trimIndent())
                val song = fixture.song.copy(source = "kw", uri = Uri.EMPTY, sourceInfo = org.json.JSONObject().put("songmid", fixture.id).put("img", "$base/cover").toString())
                lyricSong = song
                cacheKey = AudioStreamCache.key(song, context.getSharedPreferences("player", Context.MODE_PRIVATE).getString("quality", "128k") ?: "128k")
                Lyrics(context).cache(song, listOf(LyricLine(0, "第一句")))
                val preferences = AppPreferences(context)
                preferences.set("player.isShowBluetoothLyric", true); preferences.set("player.isShowBluetoothFullLyric", true)
                main { fixture.player.play(listOf(song), 0) }
                waitFor { fixture.player.state.value.playing }
                main { fixture.activity.moveTaskToBack(true) }
                waitFor { field(fixture.activity, "service") == null }
                allowCover.countDown()
                val session = field(fixture.player, "session") as MediaSession
                waitFor { session.controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null }
                assertTrue(session.controller.metadata!!.getString(MediaMetadata.METADATA_KEY_TITLE).startsWith("第一句 · "))
                assertEquals(8, session.controller.metadata!!.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)!!.width)
                preferences.set("player.isShowNotificationImage", false); preferences.set("player.isShowBluetoothLyric", false)
                main { fixture.player.refreshPreferences() }
                assertNull(session.controller.metadata!!.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
                assertEquals(song.title, session.controller.metadata!!.getString(MediaMetadata.METADATA_KEY_TITLE))
                waitFor { AudioStreamCache.existing()?.cachedUri(cacheKey!!) != null }
                main { fixture.player.pause() }
                fixture.player.selectSource(null)
                server.close()
                fixture.restart()
                main { fixture.player.resume() }
                waitFor { fixture.player.state.value.playing }
                assertNull(fixture.player.state.value.error)
                waitFor { fixture.player.artworkState.value.bitmap != null }

                val activity = fixture.activity
                val heading = field(activity, "heading") as TextView
                fun selectPage(tag: String) = main { activity.window.decorView.findViewWithTag<View>(tag).performClick() }
                selectPage("navigation-1")
                assertEquals(true, field(activity, "onlineMode"))
                selectPage("navigation-local")
                assertEquals(activity.getString(R.string.ui_33eeaa47c231), heading.text.toString())
                assertTrue((field(activity, "visibleSongs") as List<*>).all { (it as Song).source == "local" })
                val favorites = Playlists(context).use { it.loveList() }
                main { heading.performClick() }
                val favoriteTag = "playlist-choice-$favorites"
                waitFor { activity.window.decorView.findViewWithTag<View>(favoriteTag)?.isShown == true }
                assertNull(activity.window.decorView.findViewWithTag<View>("playlist-choice-local"))
                selectPage(favoriteTag)
                waitFor { (field(activity, "activePlaylist") as? Playlist)?.syncId == "love" }
                assertEquals(song, fixture.player.state.value.song)
                assertTrue(fixture.player.state.value.playing)
            }
        } finally {
            allowCover.countDown(); server.close(); worker.join(1000)
            catalog.delete(); if (previous != null) { val stream = catalog.startWrite(); stream.write(previous); catalog.finishWrite(stream) }
            lyricSong?.let { song ->
                Lyrics(context).clearOnlineCache(song)
                Artwork(context).forget(org.json.JSONObject(song.sourceInfo!!).getString("img"))
            }
            cacheKey?.let { AudioStreamCache.existing()?.remove(it) }
        }
    }
    @Test fun appliesSpeedToAudioAndMediaSessionWithoutResumingPausedPlayback() = runBlocking {
        withPlayback { fixture ->
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val systemVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            main { fixture.player.setPlaybackRate(1.5f); fixture.player.setVolume(0.3f) }
            fixture.play()
            assertEquals(1.5f, fixture.media().playbackParams.speed, 0.01f)
            assertEquals(1f, fixture.media().playbackParams.pitch, 0.01f)
            val session = field(fixture.player, "session") as MediaSession
            assertEquals(1.5f, session.controller.playbackState!!.playbackSpeed, 0.01f)
            main { fixture.player.setPlaybackRate(1.25f); fixture.player.setVolume(0.35f) }
            assertEquals(1.25f, fixture.media().playbackParams.speed, 0.01f)
            assertEquals(1.25f, session.controller.playbackState!!.playbackSpeed, 0.01f)
            main { fixture.player.pause(); fixture.player.setPlaybackRate(2f) }
            val position = fixture.media().currentPosition
            delay(300)
            assertFalse(fixture.media().isPlaying)
            assertEquals(position, fixture.media().currentPosition)
            assertEquals(0f, session.controller.playbackState!!.playbackSpeed, 0.01f)
            assertEquals(systemVolume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals(0.35f, fixture.player.state.value.volume, 0.01f)
            main { fixture.player.resume() }
            assertTrue(fixture.media().isPlaying)
            assertEquals(2f, fixture.media().playbackParams.speed, 0.01f)
            assertEquals(2f, session.controller.playbackState!!.playbackSpeed, 0.01f)
            main { fixture.player.play(listOf(fixture.song.copy(id = fixture.id + 1)), 0) }
            waitFor { !fixture.player.state.value.preparing }
            assertEquals(2f, fixture.media().playbackParams.speed, 0.01f)
            fixture.restart()
            assertEquals(2f, fixture.player.state.value.playbackRate, 0.01f)
            assertEquals(0.35f, fixture.player.state.value.volume, 0.01f)
            assertFalse(fixture.player.state.value.playing)
            main { fixture.player.play(listOf(fixture.song), 0) }
            waitFor { !fixture.player.state.value.preparing }
            assertEquals(2f, fixture.media().playbackParams.speed, 0.01f)
        }
    }

    @Test fun changingSettingsWhilePreparingKeepsAnExplicitPauseUntilResume() = runBlocking {
        withPlayback { fixture ->
            main {
                fixture.player.play(listOf(fixture.song), 0)
                fixture.player.pause()
                fixture.player.setPlaybackRate(1.75f); fixture.player.setVolume(0.25f)
            }
            waitFor { !fixture.player.state.value.preparing }
            assertNull(fixture.player.state.value.error)
            assertFalse(fixture.player.state.value.playing)
            assertFalse(fixture.media().isPlaying)
            main { fixture.player.resume() }
            assertTrue(fixture.media().isPlaying)
            assertEquals(1.75f, fixture.media().playbackParams.speed, 0.01f)
            assertEquals(0.25f, fixture.player.state.value.volume, 0.01f)
        }
    }

    @Test fun settingsPagePersistsAppearanceAndCalibratesHighlightAndLyricSeeking() = runBlocking {
        withPlayback { fixture ->
            val lrc = File(context.cacheDir, "settings-${fixture.id}.lrc")
            try {
                lrc.writeText("[00:00.00]zero\n[00:01.00]first\n[00:02.00]second")
                Lyrics(context).import(fixture.id, "local", Uri.fromFile(lrc))
                fixture.play()
                main { fixture.player.pause() }
                waitFor { (field(fixture.activity, "lyricLines") as List<*>).size == 3 }
                invoke(fixture.activity, "showPlayerDetails"); invoke(fixture.activity, "toggleDetailPage")
                invoke(fixture.activity, "showPlaybackSettings")
                waitFor { fixture.activity.settingsFragment()?.view != null }
                val root = fixture.activity.settingsFragment()!!.requireView()
                progress(root, "播放倍速", 125); progress(root, "应用内音量", 40)
                progress(root, "歌词字号", 25); progress(root, "歌词时间微调", 605)
                main { (find(root, "右对齐") as RadioButton).performClick() }
                assertEquals(1.25f, fixture.player.state.value.playbackRate, 0.01f)
                assertFalse(fixture.media().isPlaying)
                assertEquals(500, LyricOffsets(context).get(fixture.song))
                main { fixture.activity.closeSettings(); fixture.player.seekTo(1200) }
                waitFor { field(fixture.activity, "detailScroll") != null }
                val scroll = field(fixture.activity, "detailScroll") as PlayerLyricScroll
                val container = scroll.lines
                waitFor { container.childCount == 3 && scroll.height > 0 }
                val first = container.getChildAt(0) as TextView
                val second = container.getChildAt(1) as TextView
                assertEquals(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 25f, context.resources.displayMetrics), first.textSize, 0.1f)
                assertEquals(Gravity.RIGHT, first.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
                assertEquals(Color.rgb(77, 175, 124), first.currentTextColor)
                assertEquals(Color.GRAY, second.currentTextColor)
                main {
                    val down = SystemClock.uptimeMillis()
                    val y = scroll.height / 2f
                    val slop = ViewConfiguration.get(scroll.context).scaledTouchSlop + 1
                    fun touch(action: Int, at: Float) {
                        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, scroll.width / 2f, at, 0)
                        try { scroll.dispatchTouchEvent(event) } finally { event.recycle() }
                    }
                    scroll.scrollTo(0, first.top + first.height / 2 - scroll.height / 2)
                    touch(MotionEvent.ACTION_DOWN, y)
                    touch(MotionEvent.ACTION_MOVE, y - slop)
                    touch(MotionEvent.ACTION_MOVE, y - slop - first.height)
                    touch(MotionEvent.ACTION_UP, y - slop - first.height)
                }
                waitFor { fixture.player.state.value.position == 1500 }
                assertEquals(Color.GRAY, first.currentTextColor)
                assertEquals(Color.rgb(77, 175, 124), second.currentTextColor)
                fixture.restart()
                val settings = PlayerSettings(context)
                val landscape = fixture.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                assertEquals(LyricAppearance(25, LyricAlignment.RIGHT), settings.appearance(landscape))
                assertEquals(500, LyricOffsets(context).get(fixture.song))
                invoke(fixture.activity, "showPlaybackSettings")
                waitFor { fixture.activity.settingsFragment()?.view != null }
                val restored = fixture.activity.settingsFragment()!!.requireView()
                assertEquals(125, (find(restored, "播放倍速") as SeekBar).progress)
                main { restored.findViewWithTag<View>("playback-reset").performClick() }
                assertEquals(1f, fixture.player.state.value.playbackRate, 0.01f)
                assertEquals(1f, fixture.player.state.value.volume, 0.01f)
                assertEquals(LyricAlignment.LEFT, settings.appearance(landscape).alignment)
                assertEquals(0, LyricOffsets(context).get(fixture.song))
            } finally { lrc.delete() }
        }
    }

    @Test fun desktopLyricsSurviveBackgroundAndRespectLockDisableAndPermissionRevocation() = runBlocking {
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        val oldMode = Regex("SYSTEM_ALERT_WINDOW: ([a-z]+)").find(shell("appops get ${context.packageName} SYSTEM_ALERT_WINDOW"))
            ?.groupValues?.get(1) ?: "default"
        val preferences = context.getSharedPreferences("desktop-lyrics", Context.MODE_PRIVATE)
        val saved = preferences.all.toMap()
        try {
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            waitFor { Settings.canDrawOverlays(context) }
            withPlayback { fixture ->
                val settings = DesktopLyricSettings(context)
                settings.enabled = true
                fixture.play()
                val overlay = field(fixture.player, "desktopLyrics")!!
                waitFor { (field(overlay, "view") as? TextView)?.isAttachedToWindow == true }
                main { fixture.activity.moveTaskToBack(true) }
                assertTrue((field(overlay, "view") as TextView).isAttachedToWindow)
                main { settings.set("locked", true); fixture.player.refreshLyricPreferences() }
                val params = field(overlay, "params") as android.view.WindowManager.LayoutParams
                assertTrue(params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    val limit = requireNotNull(context.getSystemService(android.hardware.input.InputManager::class.java)).maximumObscuringOpacityForTouch
                    assertTrue(params.alpha <= limit)
                    main { settings.set("opacity", 30); fixture.player.refreshLyricPreferences() }
                    assertEquals(0.3f, params.alpha, 0.01f)
                }
                main { settings.enabled = false; fixture.player.refreshLyricPreferences() }
                assertNull(field(overlay, "view"))
                assertTrue(fixture.player.state.value.playing)
                shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW ignore")
                waitFor { !Settings.canDrawOverlays(context) }
                main { settings.enabled = true; fixture.player.refreshLyricPreferences() }
                assertNull(field(overlay, "view"))
            }
        } finally { restore(preferences, saved); shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW $oldMode") }
    }
    @Test fun explicitExitStopsPlaybackAndRestoresQueuePausedOnNextLaunch() = runBlocking {
        withPlayback { fixture ->
            AppPreferences(context).set("player.isSavePlayTime", true)
            fixture.play()
            val second = fixture.song.copy(id = fixture.id + 1, title = "queued after exit")
            main { fixture.player.enqueue(listOf(second)); fixture.player.seekTo(5000) }
            val previous = fixture.player
            main {
                context.startService(Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_EXIT))
                fixture.activity.finish()
            }
            waitFor { (field(previous, "scope") as CoroutineScope).coroutineContext[Job]!!.isCompleted }
            fixture.activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            fixture.bind()
            waitFor { fixture.player.queueState.value?.songs == listOf(fixture.song, second) }
            assertFalse(fixture.player.state.value.playing)
            assertTrue(fixture.player.queueState.value!!.position >= 4900)
            assertEquals(fixture.song, fixture.player.state.value.song)
            assertFalse((context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).activeNotifications.any { it.id == 1 })
        }
    }
    @Test fun switchingEnglishAndTraditionalRecreatesUiAndNotificationsWithoutInterruptingAudio() = runBlocking {
        fun screenshot(name: String) {
            if (InstrumentationRegistry.getArguments().getString("migrationScreenshots") != "true") return
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(500) // Window animations run independently of the UI message queue.
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            val directory = File(output?.let(::File) ?: context.getExternalFilesDir(null), "migration-validation").apply { mkdirs() }
            try { File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
        withPlayback { fixture ->
            fixture.play(); val original = fixture.player
            for ((language, label) in listOf("en" to "Local music", "zh-TW" to "本地音樂")) {
                // waitForMonitorWithTimeout removes the monitor after it receives one Activity.
                val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
                try {
                    AppPreferences(context).set("common.langId", language)
                    main { fixture.activity.recreate() }
                    fixture.activity = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as MainActivity
                    fixture.bind()
                    assertSame(original, fixture.player); assertTrue(fixture.player.state.value.playing)
                    val title = field(fixture.activity, "pageTitle") as TextView
                    assertEquals(label, title.text.toString())
                    val search = field(fixture.activity, "search") as TextView
                    assertEquals(if (language == "en") "Search songs or artists" else "搜索歌曲或歌手", search.hint.toString())
                    screenshot("home-$language")
                    val notification = PlaybackService::class.java.getDeclaredMethod("notification").apply { isAccessible = true }.invoke(fixture.player) as android.app.Notification
                    assertEquals(if (language == "en") "Next song" else "下一首", notification.actions[2].title.toString())
                    invoke(fixture.activity, "showPlayerDetails")
                    assertEquals(if (language == "en") "Repeat playlist" else "列表循環", (field(fixture.activity, "detailMode") as TextView).text.toString())
                    screenshot("player-$language")
                    main { (field(fixture.activity, "playerDialog") as Dialog).dismiss() }
                } finally { instrumentation.removeMonitor(monitor) }
            }
        }
    }
    @Test fun mediaPlayerFailureFallsBackOnceAndPreservesPausedPositionSpeedAndQueue() = runBlocking {
        withPlayback { fixture ->
            fixture.play(); val original = fixture.media()
            main {
                fixture.player.seekTo(3000); fixture.player.pause(); fixture.player.setPlaybackRate(1.25f); fixture.player.setVolume(0.3f)
                fixture.player.recoverMediaPlayer(original, fixture.song, fixture.song.uri, 3000, "MEDIA_ERROR_SERVER_DIED")
            }
            waitFor { !fixture.player.state.value.preparing }
            val replacement = field(fixture.player, "nativePlayer") as NativeAudioPlayer
            assertNull(field(fixture.player, "player")); assertNull(fixture.player.state.value.error)
            assertFalse(fixture.player.state.value.playing)
            assertEquals(3000, fixture.player.state.value.position)
            main {
                assertEquals(1.25f, replacement.engine.playbackParameters.speed, 0.01f)
                assertEquals(0.3f, replacement.engine.volume, 0.01f)
            }
            assertEquals(listOf(fixture.song), fixture.player.queueState.value!!.songs)
            main { fixture.player.recoverMediaPlayer(original, fixture.song, fixture.song.uri, 0, "late callback"); fixture.player.resume() }
            waitFor { fixture.player.state.value.playing }
            assertSame(replacement, field(fixture.player, "nativePlayer"))
            assertTrue(fixture.player.state.value.position >= 3000)
        }
    }
    @Test fun playlistAndSettingsNavigationKeepAudioAndTheGlobalMiniPlayer() = runBlocking {
        withPlayback { fixture ->
            fixture.play()
            val activity = fixture.activity; val media = fixture.media()
            main {
                activity.playlistBrowserRepository = object : PlaylistBrowserRepository {
                    override suspend fun page(query: PlaylistBrowseQuery, page: Int) = emptyList<OnlinePlaylist>()
                    override suspend fun categories(source: String) = emptyList<OnlineTagGroup>()
                }
            }
            invoke(activity, "showOnlinePlaylists")
            waitFor { activity.navigationController().currentDestination?.id == R.id.playlist_browser }
            assertTrue((field(activity, "miniBar") as View).isShown); assertTrue(media.isPlaying)
            invoke(activity, "showSettings")
            waitFor { activity.settingsFragment()?.view != null }
            assertEquals(View.GONE, (field(activity, "miniBar") as View).visibility)
            main { activity.closeSettings() }
            waitFor { activity.navigationController().currentDestination?.id == R.id.playlist_browser }
            assertTrue((field(activity, "miniBar") as View).isShown); assertTrue(media.isPlaying)
            main { activity.navigationController().popBackStack() }
            waitFor { activity.navigationController().currentDestination?.id == R.id.music_page }
            assertSame(media, fixture.media()); assertTrue(fixture.player.state.value.playing)
            assertEquals(fixture.song, fixture.player.state.value.song)
        }
    }
    @Test fun chartRowClickPlaysTheDisplayedChartQueue() = runBlocking {
        withPlayback { fixture ->
            val activity = fixture.activity
            val songs = (0..2).map { fixture.song.copy(id = fixture.song.id + it, title = "Chart song $it") }
            main {
                activity.chartsRepository = object : ChartsRepository {
                    override fun boards(source: String) = NativeChartsRepository().boards(source)
                    override suspend fun page(board: Board, page: Int) = songs
                }
            }
            invoke(activity, "showBoards")
            waitFor { activity.chartsFragment()?.model?.state?.value?.songs?.size == 3 }
            lateinit var list: ListView
            main { list = activity.chartsFragment()!!.requireView().findViewWithTag("charts-songs") }
            waitFor { list.childCount > 1 }
            main { list.performItemClick(list.getChildAt(1), 1, 1) }
            waitFor { fixture.player.state.value.song?.id == songs[1].id && fixture.player.state.value.playing }
            assertEquals(songs, fixture.player.queueState.value!!.songs); assertEquals(1, fixture.player.queueState.value!!.index)
            assertTrue(fixture.media().isPlaying)
            waitFor { list.getChildAt(1)?.isActivated == true }
            main { activity.chartsFragment()!!.requireView().findViewWithTag<View>("charts-board").performClick() }
            waitFor { activity.chartsFragment()?.isBoardDrawerOpen == true }
            main { (field(activity, "toggle") as View).performClick() }
            waitFor { !fixture.player.state.value.playing }
            assertFalse(fixture.media().isPlaying); assertTrue(activity.chartsFragment()!!.isBoardDrawerOpen)
            main { (field(activity, "toggle") as View).performClick() }
            waitFor { fixture.player.state.value.playing }
            main { activity.onBackPressedDispatcher.onBackPressed() }
            waitFor { activity.chartsFragment()?.isBoardDrawerOpen == false }
            assertEquals(R.id.charts_page, activity.navigationController().currentDestination?.id)
            main { activity.navigationController().popBackStack() }
            waitFor { activity.navigationController().currentDestination?.id == R.id.music_page }
            assertTrue(fixture.player.state.value.playing)
        }
    }
    @Test fun settingsFragmentThemeChangesKeepTheSameActivityAndAudioPlaying() = runBlocking {
        withPlayback { fixture ->
            fixture.play()
            val activity = fixture.activity; val player = fixture.player
            invoke(activity, "showSettings")
            waitFor { (activity.settingsFragment() as? SettingsFragment)?.view != null }
            val fragment = activity.settingsFragment() as SettingsFragment
            val target = if ((field(activity, "palette") as UiPalette).theme.id == "blue") "green" else "blue"
            main {
                fragment.requireView().findViewWithTag<View>("settings-category-theme").performClick()
                fragment.requireView().findViewWithTag<View>("settings-theme-$target").performClick()
            }
            waitFor { (field(activity, "palette") as UiPalette).theme.id == target }
            assertFalse(activity.isDestroyed); assertSame(player, field(activity, "service"))
            assertSame(fragment, activity.settingsFragment())
            assertTrue(player.state.value.playing); assertTrue(fixture.media().isPlaying)
            val position = player.state.value.position
            waitFor { player.state.value.position > position + 200 }
            assertEquals(fixture.song, player.state.value.song)
            main { activity.closeSettings() }
            waitFor { activity.settingsFragment() == null }
            assertTrue(player.state.value.playing)
        }
    }
    @Test fun languageAndFontChangesKeepPlaylistSearchAndScrollWhileAudioContinues() = runBlocking {
        withPlayback { fixture ->
            fixture.play(); val player = fixture.player
            val lists = Playlists(context); val id = lists.create("page-state-${fixture.id}")
            try {
                val songs = (0 until 200).map { value -> Song(value.toLong(), Uri.EMPTY, "Song $value", "Artist", 1000, "kw", """{"songmid":"$value"}""") }
                lists.batchSongs(songs, null, id, false)
                val playlist = lists.list().single { it.id == id }
                main { MainActivity::class.java.getDeclaredMethod("loadPlaylist", Playlist::class.java).apply { isAccessible = true }.invoke(fixture.activity, playlist) }
                waitFor { (field(fixture.activity, "visibleSongs") as List<*>).size == 200 }
                main {
                    (field(fixture.activity, "search") as TextView).apply { text = "Song"; visibility = View.VISIBLE }
                    (field(fixture.activity, "list") as ListView).setSelectionFromTop(80, -12)
                }
                waitFor { (field(fixture.activity, "list") as ListView).firstVisiblePosition == 80 }
                val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
                AppPreferences(context).set("common.langId", "en"); AppPreferences(context).set("common.fontScale", "1.3")
                main { fixture.activity.recreate() }
                fixture.activity = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as MainActivity
                fixture.bind()
                waitFor { (field(fixture.activity, "list") as ListView).firstVisiblePosition == 80 }
                assertEquals(id, (field(fixture.activity, "activePlaylist") as Playlist).id)
                assertEquals("Song", (field(fixture.activity, "search") as TextView).text.toString())
                assertEquals(200, (field(fixture.activity, "visibleSongs") as List<*>).size)
                assertSame(player, fixture.player); assertTrue(fixture.player.state.value.playing)
            } finally { lists.delete(id); lists.close() }
        }
    }
}
