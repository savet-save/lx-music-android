package cn.toside.music.nativeapp.online

import cn.toside.music.nativeapp.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException

internal data class SearchPage<T>(val items: List<T>, val nextPages: Map<String, Int>, val failedSources: Set<String>) {
    val hasMore get() = nextPages.isNotEmpty()
}

internal class SearchUnavailableException(val sources: Set<String>, cause: Throwable) : Exception("Search providers unavailable", cause)

/** Each provider keeps its own cursor; a failed page is retried instead of skipped. */
internal class SearchCatalog(
    private val songSearch: suspend (String, String, Int) -> List<Song> = { source, query, page -> OnlineSearch().search(query, page, source) },
    private val playlistSearch: suspend (String, String, Int) -> List<OnlinePlaylist> = { source, query, page -> PlaylistSearch().search(source, query, page) }
) {
    suspend fun songs(query: String, source: String = ALL, cursor: Map<String, Int>? = null): SearchPage<Song> = page(
        query, source, cursor, { if (it in setOf("wy", "mg")) 20 else 30 }, { it.source to it.id }, fetch = songSearch
    )

    suspend fun playlists(query: String, source: String = ALL, cursor: Map<String, Int>? = null): SearchPage<OnlinePlaylist> = page(
        query, source, cursor, { 20 }, { Triple(it.source, it.id, it.digest) }, fetch = playlistSearch
    )

    companion object {
        const val ALL = "all"
        val sources = listOf("kg", "kw", "tx", "wy", "mg")
        val choices = listOf(ALL) + sources

        internal suspend fun <T> page(
            query: String,
            source: String = ALL,
            cursor: Map<String, Int>? = null,
            pageSize: (String) -> Int,
            key: (T) -> Any,
            timeoutMillis: Long = 18_000,
            fetch: suspend (String, String, Int) -> List<T>
        ): SearchPage<T> = coroutineScope {
            require(query.isNotBlank() && source in choices)
            val selected = if (source == ALL) sources else listOf(source)
            val pages = cursor ?: selected.associateWith { 1 }
            require(pages.all { (provider, number) -> provider in selected && number > 0 })
            val responses = pages.map { (provider, number) ->
                async {
                    val result = try {
                        val items = withTimeoutOrNull(timeoutMillis) { fetch(provider, query, number) }
                        if (items == null) Result.failure(TimeoutException("$provider search timed out")) else Result.success(items)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Result.failure(e) }
                    provider to result
                }
            }.awaitAll()
            val failures = responses.filter { it.second.isFailure }.map { it.first }.toSet()
            if (responses.isNotEmpty() && failures.size == responses.size) {
                throw SearchUnavailableException(failures, responses.first().second.exceptionOrNull()!!)
            }
            val next = linkedMapOf<String, Int>()
            responses.forEach { (provider, result) ->
                if (result.isFailure) next[provider] = pages.getValue(provider)
                else if (result.getOrThrow().size >= pageSize(provider)) next[provider] = pages.getValue(provider) + 1
            }
            val lists = responses.mapNotNull { it.second.getOrNull() }
            val seen = hashSetOf<Any>()
            val items = buildList {
                repeat(lists.maxOfOrNull { it.size } ?: 0) { index ->
                    lists.forEach { list -> list.getOrNull(index)?.let { item -> if (seen.add(key(item))) add(item) } }
                }
            }
            SearchPage(items, next, failures)
        }
    }
}
