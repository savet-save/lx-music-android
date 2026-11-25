package cn.toside.music.nativeapp.source

import android.net.Uri
import android.os.SystemClock
import cn.toside.music.nativeapp.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

internal data class SourceAudio(val url: String, val quality: String, val sourceId: String? = null,
    val attemptedSources: List<String> = emptyList(), val sourceName: String? = null, val bitrate: Long? = null, val trackId: Long? = null,
    val backupUrls: List<String> = emptyList())

internal class SourceResolutionException(val attemptedSources: List<String>, message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal class SourceAudioResolver(private val sources: UserSources,
    private val match: suspend (Song, Set<String>?) -> List<Song>, private val autoSwitchPlatform: Boolean) {
    private suspend fun address(entry: UserSourceEntry, song: Song, quality: String): SourceAudio {
        val info = JSONObject(song.sourceInfo ?: error("歌曲缺少音源信息"))
        val audio = sources.audioFrom(entry.id, song.source, info, quality)
        val uri = Uri.parse(audio.url)
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) { "音源返回了无效的音频地址" }
        return audio.copy(sourceId = entry.id, sourceName = entry.name)
    }

    /** Resolve one exact bitrate for download callers, including the configured source exclusions. */
    suspend fun resolve(song: Song, quality: String, excluded: Set<String> = emptySet(),
        validate: suspend (String) -> Unit = {}): SourceAudio {
        val enabled = sources.catalog().enabledEntries
        val requestedQuality = if (song.source == "bili" && !quality.startsWith("aac-")) "original" else quality
        check(song.source != "bili" || sources.bilibiliEnabled()) { "请在设置 → 自定义音源中启用 B站音源" }
        check(enabled.isNotEmpty()) { "请至少启用一个音源" }
        val attempted = excluded.toMutableSet()
        val errors = mutableListOf<String>()
        var original: Exception? = null
        var matches: List<Song>? = null
        for (entry in enabled) {
            if (!attempted.add(entry.id)) continue
            var last: Exception? = null
            var runtimeReady = false
            suspend fun attempt(candidate: Song): SourceAudio? {
                try {
                    if (sources.audioPlatformsFrom(entry.id, requestedQuality).isEmpty()) return null
                    val supported = sources.supportsFrom(entry.id, candidate.source, "musicUrl")
                    runtimeReady = true
                    if (!supported) return null
                    val audio = address(entry, candidate, requestedQuality)
                    validate(audio.url)
                    return audio.copy(attemptedSources = attempted.toList())
                } catch (e: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive(); last = e
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { last = e }
                return null
            }
            attempt(song)?.let { return it }
            if (runtimeReady && autoSwitchPlatform && song.source != "bili" && last?.message?.contains("429") != true) {
                val alternatives = matches ?: try { match(song, null).also { matches = it } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { emptyList() }
                for (candidate in alternatives.filter { it.source != song.source }) {
                    attempt(candidate)?.let { return it }
                    if (last?.message?.contains("429") == true) break
                }
            }
            if (original == null) original = last
            errors.add("${entry.name}：${last?.message?.take(160) ?: "不支持该平台或音质"}")
        }
        throw SourceResolutionException(attempted.toList(),
            "所有启用音源均未提供可用的 $quality 音频" + errors.takeIf { it.isNotEmpty() }?.joinToString("；", "：").orEmpty(), original)
    }

    private data class Candidate(val provider: String, val platform: String, val songId: Long, val quality: String)
    private data class Request(val entry: UserSourceEntry, val song: Song, val quality: String) {
        val key = Candidate(entry.id, song.source, song.id, quality)
    }

    /** A single playback request keeps its place when the player rejects a returned address. */
    inner class Playback(private val song: Song, private val qualities: List<String>, parent: CoroutineScope,
        private val trace: (String) -> Unit = {}) : AutoCloseable {
        private val job = SupervisorJob(parent.coroutineContext[Job])
        private val scope = CoroutineScope(parent.coroutineContext + job)
        private val checked = ConcurrentHashMap.newKeySet<Candidate>()
        private val platforms = ConcurrentHashMap<Pair<String, String>, Set<String>>()
        private val rateLimited = ConcurrentHashMap.newKeySet<Pair<String, String>>()
        private val errors = ConcurrentHashMap<String, Exception>()
        private val pending = mutableMapOf<Candidate, Deferred<SourceAudio?>>()
        private val consumed = mutableSetOf<Candidate>()
        private var alternatives: List<Song>? = null

        override fun close() = job.cancel()

        suspend fun next(): SourceAudio {
            val enabled = sources.catalog().enabledEntries
            check(enabled.isNotEmpty()) { "请至少启用一个音源" }
            tryCandidates(enabled, listOf(song), direct = true)?.let { return it }
            val searchable = if (autoSwitchPlatform) buildSet {
                for (entry in enabled) for (quality in qualities) {
                    val key = entry.id to quality
                    if (key !in rateLimited) addAll(platforms[key].orEmpty())
                }
                remove(song.source)
            } else emptySet()
            if (searchable.isNotEmpty()) {
                val matches = alternatives ?: findAlternatives(searchable)
                alternatives = matches
                tryCandidates(enabled, matches)?.let { return it }
            }
            val failures = enabled.mapNotNull { entry -> errors[entry.id]?.let { entry.name to it } }
            val detail = failures.joinToString("；") { (name, error) -> "$name：${error.message.orEmpty().take(160)}" }
            throw SourceResolutionException(enabled.map { it.id }.filter { id -> checked.any { it.provider == id } },
                "所有启用音源均未提供可用音频" + detail.takeIf { it.isNotEmpty() }?.let { "：$it" }.orEmpty(),
                failures.firstOrNull()?.second)
        }

        private suspend fun tryCandidates(enabled: List<UserSourceEntry>, songs: List<Song>, direct: Boolean = false): SourceAudio? {
            for (quality in qualities) {
                val running = linkedMapOf<Candidate, Deferred<SourceAudio?>>()
                val waiting = ArrayDeque<Request>()
                for (entry in enabled) for (candidate in songs) {
                    val providerQuality = entry.id to quality
                    if (providerQuality in rateLimited || !direct && candidate.source !in platforms[providerQuality].orEmpty()) continue
                    val request = Request(entry, candidate, quality)
                    if (request.key in consumed) continue
                    val inFlight = pending[request.key]
                    if (inFlight == null) waiting.addLast(request) else running[request.key] = inFlight
                }
                var hedgeAt = SystemClock.elapsedRealtime() + HEDGE_DELAY_MS
                while (waiting.isNotEmpty() || running.isNotEmpty()) {
                    val available = waiting.indexOfFirst { request -> running.keys.none { it.provider == request.entry.id } }
                    if (running.isEmpty() || available >= 0 && SystemClock.elapsedRealtime() >= hedgeAt) {
                        val request = waiting.removeAt(available)
                        val attempt = scope.async { attempt(request.entry, request.song, quality) }
                        pending[request.key] = attempt
                        running[request.key] = attempt
                        hedgeAt = SystemClock.elapsedRealtime() + HEDGE_DELAY_MS
                    }
                    val canHedge = waiting.any { request -> running.keys.none { it.provider == request.entry.id } }
                    val result = if (!canHedge) awaitFirst(running) else {
                        withTimeoutOrNull((hedgeAt - SystemClock.elapsedRealtime()).coerceAtLeast(1)) { awaitFirst(running) }
                    } ?: continue
                    val (key, audio) = result
                    running.remove(key)
                    pending.remove(key)
                    consumed.add(key)
                    if (audio != null) return audio
                    hedgeAt = SystemClock.elapsedRealtime()
                }
            }
            return null
        }

        private suspend fun awaitFirst(running: Map<Candidate, Deferred<SourceAudio?>>) = select {
            running.forEach { (key, attempt) -> attempt.onAwait { key to it } }
        }

        private suspend fun findAlternatives(searchable: Set<String>): List<Song> {
            val started = SystemClock.elapsedRealtime()
            val found = try { match(song, searchable).filter { it.source in searchable } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { emptyList() }
            trace("match platforms=${searchable.sorted().joinToString(",")} count=${found.size} elapsedMs=${SystemClock.elapsedRealtime() - started}")
            return found
        }

        private suspend fun attempt(entry: UserSourceEntry, candidate: Song, quality: String): SourceAudio? {
            val key = entry.id to quality
            if (key in rateLimited) return null
            val started = SystemClock.elapsedRealtime()
            var outcome = "unsupported"
            try {
                return withTimeout(ATTEMPT_TIMEOUT_MS) {
                    val supported = platforms.getOrPut(key) { sources.audioPlatformsFrom(entry.id, quality) }
                    if (candidate.source !in supported || !checked.add(Candidate(entry.id, candidate.source, candidate.id, quality))) return@withTimeout null
                    address(entry, candidate, quality).also { outcome = "resolved" }
                }
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                outcome = "timeout"
                errors.putIfAbsent(entry.id, e)
            } catch (e: CancellationException) { outcome = "canceled"; throw e }
            catch (e: Exception) {
                outcome = "failed"
                errors.putIfAbsent(entry.id, e)
                if (e.message?.contains("429") == true) rateLimited.add(key)
            } finally {
                trace("lookup source=${entry.name} platform=${candidate.source} quality=$quality outcome=$outcome elapsedMs=${SystemClock.elapsedRealtime() - started}")
            }
            return null
        }
    }

    companion object {
        private const val HEDGE_DELAY_MS = 750L
        private const val ATTEMPT_TIMEOUT_MS = 4_000L
    }
}
