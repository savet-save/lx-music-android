package cn.toside.music.nativeapp.source

import android.net.Uri
import cn.toside.music.nativeapp.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

internal data class SourceAudio(val url: String, val quality: String, val sourceId: String? = null,
    val attemptedSources: List<String> = emptyList(), val sourceName: String? = null, val bitrate: Long? = null, val trackId: Long? = null,
    val backupUrls: List<String> = emptyList())

internal class SourceResolutionException(val attemptedSources: List<String>, message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Try every enabled script at this exact quality before the caller considers lower qualities. */
internal class SourceAudioResolver(private val sources: UserSources,
    private val match: suspend (Song) -> List<Song>, private val autoSwitchPlatform: Boolean) {
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
                    if (!sources.hasQualityFrom(entry.id, requestedQuality)) return null
                    val supported = sources.supportsFrom(entry.id, candidate.source, "musicUrl")
                    runtimeReady = true
                    if (!supported) return null
                    val info = JSONObject(candidate.sourceInfo ?: error("歌曲缺少音源信息"))
                    val audio = sources.audioFrom(entry.id, candidate.source, info, requestedQuality)
                    val url = audio.url
                    val uri = Uri.parse(url)
                    require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) { "音源返回了无效的音频地址" }
                    validate(url)
                    return audio.copy(sourceId = entry.id, attemptedSources = attempted.toList(), sourceName = entry.name)
                } catch (e: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive(); last = e
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { last = e }
                return null
            }
            attempt(song)?.let { return it }
            if (runtimeReady && autoSwitchPlatform && song.source != "bili" && last?.message?.contains("429") != true) {
                val alternatives = matches ?: try { match(song).also { matches = it } }
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
}
