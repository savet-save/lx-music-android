package cn.toside.music.nativeapp.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun playlistCover(vararg candidates: String?): String = candidates.asSequence().filterNotNull()
    .map { it.trim().let { value -> if (value.startsWith("//")) "https:$value" else value }.replace("{size}", "256") }
    .firstOrNull { it.startsWith("https://") || it.startsWith("http://") }.orEmpty()

internal data class PlaylistBrowseQuery(val source: String = "kw", val category: String = "全部", val categoryName: String = "", val order: String = "new") {
    fun orders(): List<String> = when {
        source == "wy" -> listOf("new", "hot")
        category != "全部" -> listOf("recommend")
        source == "kw" -> listOf("new", "hot")
        source == "tx" -> listOf("new", "hot", "recommend")
        else -> listOf("recommend")
    }
    fun normalized() = copy(order = order.takeIf { it in orders() } ?: orders().first())
}

internal interface PlaylistBrowserRepository {
    suspend fun page(query: PlaylistBrowseQuery, page: Int): List<OnlinePlaylist>
    suspend fun categories(source: String): List<OnlineTagGroup>
}

internal class NativePlaylistBrowserRepository : PlaylistBrowserRepository {
    override suspend fun page(query: PlaylistBrowseQuery, page: Int) =
        OnlinePlaylists().recommended(page, query.source, query.category, query.order)

    override suspend fun categories(source: String): List<OnlineTagGroup> = withContext(Dispatchers.IO) {
        when (source) {
            "kw" -> OnlinePlaylists().kuwoCategories().map { OnlineTagGroup(it.name, it.tags.map { tag -> OnlineTag(tag.id, tag.name) }) }
            "kg" -> KugouPlaylists().categories().map { OnlineTagGroup(it.name, it.tags.map { tag -> OnlineTag(tag.id, tag.name) }) }
            "wy" -> NeteaseApi().categories().map { OnlineTagGroup(it.name, it.tags.map { tag -> OnlineTag(tag, tag) }) }
            "tx" -> OnlinePlaylists().qqCategories()
            "mg" -> OnlinePlaylists().miguCategories()
            else -> emptyList()
        }
    }
}
