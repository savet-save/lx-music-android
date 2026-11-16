package cn.toside.music.nativeapp.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.MultiSourceAudioServer
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.source.UserSourceLibrary
import cn.toside.music.nativeapp.ui.MainActivity
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

@RunWith(AndroidJUnit4::class)
class MultiSourcePlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(action: () -> Unit) { instrumentation.runOnMainSync(action); instrumentation.waitForIdleSync() }
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(45_000) { while (!condition()) delay(100) }
    private fun script(name: String, url: String) = """
        /**
         * @name $name
         */
        lx.on(lx.EVENT_NAMES.request,async ({info})=>{
            if(info.type!=='320k') throw Error('quality changed');
            return '$url';
        });
        lx.send(lx.EVENT_NAMES.inited,{sources:{kw:{type:'music',actions:['musicUrl'],qualitys:['320k']}}});
    """.trimIndent()

    @Test fun returnedButUnplayableUrlAdvancesToTheNextEnabledSourceAtTheSameQuality() = runBlocking {
        val queue = AtomicFile(File(context.filesDir, "playback-queue.json"))
        val savedQueue = if (queue.baseFile.exists()) queue.readFully() else null
        val quality = context.getSharedPreferences("player", Context.MODE_PRIVATE)
        val settings = context.getSharedPreferences("app-settings", Context.MODE_PRIVATE)
        val oldQuality = quality.getString("quality", null)
        val old = listOf("player.autoLowerQuality", "player.autoSwitchSource").associateWith { settings.all[it] as? Boolean }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        waitFor { field(activity, "service") != null }
        val player = field(activity, "service") as PlaybackService
        val busy = player.state.value.playing || player.state.value.preparing
        if (busy) main { activity.finish() }
        assumeFalse(busy)
        val catalog = player.sourceCatalog()
        val added = mutableListOf<String>()
        val id = System.nanoTime()
        val song = Song(id, Uri.EMPTY, "Multi source playback", "test", 12000, "kw", """{"songmid":$id,"_types":{"320k":{}}}""")
        try { MultiSourceAudioServer().use { server ->
            quality.edit().putString("quality", "320k").commit()
            settings.edit().putBoolean("player.autoLowerQuality", false).putBoolean("player.autoSwitchSource", false).commit()
            listOf(script("first playback fixture", server.url("bad")), script("second playback fixture", server.url("good"))).forEach {
                player.importSource(it); added.add(UserSourceLibrary.metadata(it).id)
            }
            player.setEnabledSources(added)
            main { player.play(listOf(song), 0) }
            waitFor { player.state.value.error != null || player.state.value.playing }
            assertNull(player.state.value.error)
            assertTrue(player.state.value.playing)
            delay(700)
            assertTrue(player.state.value.position >= 300)
            assertTrue(server.requests.any { it.startsWith("/bad") })
            assertTrue(server.requests.any { it.startsWith("/good") })
            assertTrue(server.requests.indexOfFirst { it.startsWith("/bad") } < server.requests.indexOfFirst { it.startsWith("/good") })
            assertFalse(player.state.value.preparing)
            main { player.pause() }
        } } finally {
            main { player.pause(); activity.finish() }
            added.forEach { player.removeSource(it) }
            player.setEnabledSources(catalog.enabled)
            context.stopService(Intent(context, PlaybackService::class.java))
            waitFor { (field(player, "scope") as CoroutineScope).coroutineContext[Job]!!.isCompleted }
            quality.edit().apply { if (oldQuality == null) remove("quality") else putString("quality", oldQuality) }.commit()
            settings.edit().apply { old.forEach { (key, value) -> if (value == null) remove(key) else putBoolean(key, value) } }.commit()
            PLAYBACK_QUALITIES_FOR_TEST.forEach { AudioStreamCache.existing()?.remove(AudioStreamCache.key(song, it)) }
            queue.delete()
            savedQueue?.let { val stream = queue.startWrite(); stream.write(it); queue.finishWrite(stream) }
        }
    }
    private val PLAYBACK_QUALITIES_FOR_TEST = listOf("flac24bit", "flac", "320k", "128k")
}
