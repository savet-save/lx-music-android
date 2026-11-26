package cn.toside.music.nativeapp.data

import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.DownloadTestScheduler
import cn.toside.music.nativeapp.MultiSourceAudioServer
import cn.toside.music.nativeapp.source.SourceAudio
import cn.toside.music.nativeapp.source.SourceResolutionException
import cn.toside.music.nativeapp.source.SourceAudioResolver
import cn.toside.music.nativeapp.source.UserSources
import cn.toside.music.nativeapp.source.UserSourceLibrary
import cn.toside.music.nativeapp.utils.PermissionPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MultiSourceDownloadsTest {
    private suspend fun isolated(test: suspend (Context, Downloads, Song) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        PermissionPolicy.downloadWrite(Build.VERSION.SDK_INT)?.let {
            if (target.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED) instrumentation.uiAutomation.grantRuntimePermission(target.packageName, it)
        }
        val name = "multi-source-downloads-${System.nanoTime()}"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(preferences: String, mode: Int) = super.getSharedPreferences(if (preferences == "music-downloads") name else preferences, mode)
        }
        val downloads = Downloads(context, {}, {})
        val id = System.nanoTime()
        val song = Song(id, Uri.EMPTY, "Multi source download $id", "test", 12000, "kw", """{"songmid":$id}""")
        try { test(context, downloads, song) }
        finally { downloads.list().filter { it.song == song }.forEach { downloads.remove(it) }; target.deleteSharedPreferences(name) }
    }
    private suspend fun waitFor(downloads: Downloads, key: String, desired: DownloadStatus): DownloadTask = withTimeout(90_000) {
        var scheduler: DownloadTestScheduler? = null
        var systemId: Long? = null
        while (true) {
            val task = downloads.list().single { it.key == key }
            if (task.status == desired) return@withTimeout task
            if (task.systemId != systemId) { systemId = task.systemId; scheduler = systemId?.let(::DownloadTestScheduler) }
            if (task.status in setOf(DownloadStatus.QUEUED, DownloadStatus.PAUSED)) scheduler?.onStatus(DownloadManager.STATUS_PENDING)
            delay(200)
        }
        error("unreachable")
    }
    @Test fun interruptedTransferUsesNextSourceAfterHistoryReloadAndKeepsQuality() = runBlocking {
        isolated { context, downloads, song -> MultiSourceAudioServer().use { server ->
            val qualities = mutableListOf<String>()
            val resolve: suspend (Song, String, Set<String>) -> SourceAudio = { _, quality, excluded ->
                qualities.add(quality)
                if ("a" !in excluded) SourceAudio(server.url("broken"), quality, "a", listOf("a"), "First")
                else SourceAudio(server.url("good"), quality, "b", listOf("a", "b"), "Second")
            }
            assertTrue(downloads.startWithSources(song, "320k", resolve = resolve))
            val key = downloads.list().single { it.song == song }.key
            waitFor(downloads, key, DownloadStatus.FAILED)
            val restored = Downloads(context, {}, {})
            assertEquals(listOf(key), restored.pendingSourceRetries())
            assertTrue(restored.retryNextSource(key, resolve))
            val task = waitFor(restored, key, DownloadStatus.COMPLETE)
            assertEquals(listOf("320k", "320k"), qualities)
            assertEquals(2, task.sourceAttempts); assertEquals("Second", task.sourceName)
            val manager = context.getSystemService(DownloadManager::class.java)
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(manager.openDownloadedFile(task.systemId!!)).use { it.readBytes() }
            assertArrayEquals(server.bytes, bytes)
            assertTrue(restored.pendingSourceRetries().isEmpty())
        } }
    }
    @Test fun allFailedTransfersStopAfterEachSourceAndManualRetryStartsFresh() = runBlocking {
        isolated { _, downloads, song -> MultiSourceAudioServer().use { server ->
            val resolve: suspend (Song, String, Set<String>) -> SourceAudio = { _, quality, excluded ->
                val next = listOf("a", "b", "c").firstOrNull { it !in excluded }
                    ?: throw SourceResolutionException(excluded.toList(), "all sources exhausted")
                SourceAudio(server.url("bad-$next"), quality, next, excluded.toList() + next)
            }
            downloads.startWithSources(song, "320k", resolve = resolve)
            val key = downloads.list().single { it.song == song }.key
            repeat(3) { index ->
                val task = waitFor(downloads, key, DownloadStatus.FAILED)
                assertEquals(index + 1, task.sourceAttempts)
                if (index < 2) assertTrue(downloads.retryNextSource(key, resolve))
            }
            try { downloads.retryNextSource(key, resolve); fail("All sources exhausted") } catch (_: SourceResolutionException) { }
            assertTrue(downloads.pendingSourceRetries().isEmpty())
            assertEquals(DownloadStatus.LINK_FAILED, downloads.list().single { it.key == key }.status)
            var fresh = false
            try {
                downloads.startWithSources(song, "320k", key) { _, _, excluded ->
                    fresh = excluded.isEmpty()
                    throw IllegalStateException("manual retry fixture")
                }
                fail("fixture should throw")
            } catch (_: IllegalStateException) { }
            assertTrue(fresh)
        } }
    }
    @Test fun removingWhileSwitchingDoesNotEnqueueOrResurrectTheTask() = runBlocking {
        isolated { _, downloads, song -> MultiSourceAudioServer().use { server ->
            downloads.startWithSources(song, "320k") { _, quality, _ -> SourceAudio(server.url("bad"), quality, "a", listOf("a")) }
            val key = downloads.list().single { it.song == song }.key
            waitFor(downloads, key, DownloadStatus.FAILED)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val retry = async {
                downloads.retryNextSource(key) { _, quality, excluded ->
                    entered.complete(Unit); release.await()
                    SourceAudio(server.url("good"), quality, "b", excluded.toList() + "b")
                }
            }
            entered.await()
            downloads.remove(downloads.list().single { it.key == key })
            release.complete(Unit)
            assertFalse(retry.await())
            assertTrue(downloads.pendingSourceRetries().isEmpty())
            assertFalse(downloads.list().any { it.key == key })
        } }
    }
    @Test fun systemCompletionBroadcastRunsBackgroundFallbackWithoutManualRetry() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sources = UserSources(context)
        val before = sources.catalog()
        val added = mutableListOf<String>()
        val downloads = Downloads(context, {})
        val settings = context.getSharedPreferences("app-settings", 0)
        val oldSwitch = settings.all["player.autoSwitchSource"] as? Boolean
        val id = System.nanoTime()
        val song = Song(id, Uri.EMPTY, "Background source fallback $id", "test", 12000, "kw", """{"songmid":$id}""")
        try { MultiSourceAudioServer().use { server ->
            settings.edit().putBoolean("player.autoSwitchSource", false).commit()
            for (path in listOf("bad-background", "good-background")) {
                val script = "/**\n * @name $path $id\n */\n" +
                    "lx.on(lx.EVENT_NAMES.request,async ({info})=>{if(info.type!=='320k')throw Error('quality changed');return '${server.url(path)}';});" +
                    "lx.send(lx.EVENT_NAMES.inited,{sources:{kw:{type:'music',actions:['musicUrl'],qualitys:['320k']}}});"
                sources.import(script); added.add(UserSourceLibrary.metadata(script).id)
            }
            sources.setEnabledOrder(added)
            val resolver = SourceAudioResolver(sources, { _, _ -> emptyList() }, false)
            downloads.startWithSources(song, "320k", resolve = resolver::resolve)
            val key = downloads.list().single { it.song == song }.key
            val task = waitFor(downloads, key, DownloadStatus.COMPLETE)
            assertEquals(2, task.sourceAttempts)
            assertTrue(task.sourceName!!.startsWith("good-background"))
            assertTrue(server.requests.any { it.startsWith("/bad-background") })
            assertTrue(server.requests.any { it.startsWith("/good-background") })
            assertTrue(downloads.pendingSourceRetries().isEmpty())
        } } finally {
            downloads.list().filter { it.song == song }.forEach { downloads.remove(it) }
            added.forEach { sources.remove(it) }
            sources.setEnabledOrder(before.enabled)
            sources.close()
            settings.edit().apply { if (oldSwitch == null) remove("player.autoSwitchSource") else putBoolean("player.autoSwitchSource", oldSwitch) }.commit()
        }
    }
}
