package cn.toside.music.nativeapp.source

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** At most three independently initialized LX runtimes, in the persisted user order. */
internal class UserSources(private val context: Context,
    private val bilibiliAudio: cn.toside.music.nativeapp.online.BilibiliAudioProvider = cn.toside.music.nativeapp.online.BilibiliApi(context)) {
    private val library = UserSourceLibrary(context)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private data class Loaded(val runtime: UserSource, val updates: Job)
    private val loaded = ConcurrentHashMap<String, Loaded>()
    private val loading = ConcurrentHashMap<String, Mutex>()
    @Volatile private var closed = false
    private val mutableUpdate = MutableStateFlow<UserSourceUpdate?>(null)
    val updates = mutableUpdate.asStateFlow()

    suspend fun catalog() = withContext(Dispatchers.IO) { library.read() }
    suspend fun bilibiliEnabled() = withContext(Dispatchers.IO) { library.bilibiliEnabled() }
    suspend fun loadSaved(): Boolean {
        return catalog().enabled.isNotEmpty()
    }
    private fun observe(id: String, runtime: UserSource) = Loaded(runtime, scope.launch {
        runtime.updates.collect { update ->
            if (update != null && id in catalog().enabled) mutableUpdate.value = update
        }
    })
    private suspend fun runtime(id: String): UserSource = loading.getOrPut(id) { Mutex() }.withLock {
        check(!closed) { "音源已关闭" }
        val data = catalog()
        check(id in data.enabled) { "音源未启用" }
        loaded[id]?.runtime?.let { return@withLock it }
        val entry = data.entries.first { it.id == id }
        val runtime = UserSource(context)
        try {
            runtime.load(entry.script)
            mutex.withLock {
                check(id in catalog().enabled) { "音源未启用" }
                check(!closed) { "音源已关闭" }
                loaded[id]?.runtime?.also { runtime.close() } ?: runtime.also { loaded[id] = observe(id, runtime) }
            }
        } catch (e: Exception) { runtime.close(); throw e }
    }
    suspend fun import(script: String): JSONObject = mutex.withLock {
        check(!closed) { "音源已关闭" }
        require(script.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "音源文件超过 2 MB" }
        val runtime = UserSource(context)
        try {
            val result = runtime.load(script)
            check(!closed) { "音源已关闭" }
            val entry = withContext(Dispatchers.IO) { library.addValidated(script, enableWithOthers = true) }
            val active = entry.id in catalog().enabled
            if (active) {
                loaded.remove(entry.id)?.let { it.updates.cancel(); it.runtime.close() }
                loaded[entry.id] = observe(entry.id, runtime)
            } else runtime.close()
            result.put("enabled", active)
        } catch (e: Exception) { runtime.close(); throw e }
    }
    private suspend fun update(block: () -> Unit) = mutex.withLock {
        withContext(Dispatchers.IO) { block() }
        val enabled = catalog().enabled
        loaded.keys.filter { it !in enabled }.toList().forEach { id ->
            loaded.remove(id)?.let { it.updates.cancel(); it.runtime.close() }
        }
        if (mutableUpdate.value?.id !in enabled) mutableUpdate.value = null
    }
    suspend fun select(id: String?) = update { library.select(id) }
    suspend fun setEnabled(id: String, enabled: Boolean) = update { library.setEnabled(id, enabled) }
    suspend fun setEnabledOrder(ids: List<String>) = update { library.setEnabledOrder(ids) }
    suspend fun move(id: String, delta: Int) = update { library.move(id, delta) }
    suspend fun remove(id: String) = update { library.remove(id) }
    suspend fun allowUpdates(id: String, allowed: Boolean) {
        withContext(Dispatchers.IO) { library.allowUpdates(id, allowed) }
        if (!allowed && mutableUpdate.value?.id == id) mutableUpdate.value = null
    }
    fun dismissUpdate() { mutableUpdate.value = null }
    private suspend fun nativeBilibili(id: String): Boolean {
        check(!closed) { "音源已关闭" }
        check(id in catalog().enabled) { "音源未启用" }
        return withContext(Dispatchers.IO) { library.isBilibili(id) }
    }
    suspend fun audioFrom(id: String, source: String, info: JSONObject, quality: String): SourceAudio =
        if (nativeBilibili(id)) bilibiliAudio.audioStream(info, quality).let {
            SourceAudio(it.url, quality, bitrate = it.bitrate, trackId = it.trackId, backupUrls = it.backupUrls)
        } else SourceAudio(runtime(id).musicUrl(source, info, quality, exactQuality = true), quality)
    suspend fun supportsFrom(id: String, source: String, action: String) = if (nativeBilibili(id)) source == "bili" && action == "musicUrl" else runtime(id).supports(source, action)
    suspend fun audioPlatformsFrom(id: String, quality: String): Set<String> = if (nativeBilibili(id)) {
        if (quality == "original" || quality.matches(Regex("aac-[0-9]+"))) setOf("bili") else emptySet()
    } else runtime(id).audioPlatforms(quality)
    suspend fun bilibiliAudioOptions(info: JSONObject): List<SourceAudio> {
        val entry = catalog().enabledEntries.firstOrNull { library.isBilibili(it.id) }
            ?: error("请在设置 → 自定义音源中启用 B站音源")
        return bilibiliAudio.audioStreams(info).map {
            val quality = it.trackId?.let { id -> "aac-$id" } ?: it.quality
            SourceAudio(it.url, quality, entry.id, listOf(entry.id), entry.name, it.bitrate, it.trackId, it.backupUrls)
        }
    }
    suspend fun supports(source: String, action: String): Boolean {
        for (entry in catalog().enabledEntries) {
            try { if (supportsFrom(entry.id, source, action)) return true }
            catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
        }
        return false
    }
    suspend fun sourceRequest(source: String, action: String, info: JSONObject): JSONObject {
        var original: Exception? = null
        for (entry in catalog().enabledEntries) {
            try {
                val runtime = runtime(entry.id)
                if (runtime.supports(source, action)) return runtime.sourceRequest(source, action, info)
            } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); original = e }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { original = e }
        }
        throw original ?: IllegalStateException("没有启用支持此请求的音源")
    }
    fun close() {
        closed = true
        scope.cancel()
        loaded.values.forEach { it.updates.cancel(); it.runtime.close() }
        loaded.clear()
    }
}
