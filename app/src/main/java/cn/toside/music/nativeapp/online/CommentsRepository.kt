package cn.toside.music.nativeapp.online

import android.content.Context
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.player.SongMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

internal typealias ReadComments = suspend (Song, Boolean, Int, String?) -> CommentPage
internal data class ResolvedComments(val song: Song, val page: CommentPage)

/** Resolve once per list, then keep pagination and replies on that recording/provider. */
internal class CommentsRepository(context: Context, private val requested: Song,
    private val readerFactory: () -> ReadComments = { OnlineComments(context)::load },
    private val matches: suspend (Song) -> List<Song> = { song -> coroutineScope { SongMatcher(context, this).find(song) } }) {
    private var reader = readerFactory()
    private var selected: Song? = null
    private var generation = 0
    fun restart(original: Boolean) {
        generation++; reader = readerFactory()
        if (original) selected = null
    }
    suspend fun load(hot: Boolean, page: Int, reply: String? = null): ResolvedComments {
        require(page > 0)
        val version = generation
        val read = reader
        val first = selected ?: requested
        suspend fun attempt(song: Song): ResolvedComments {
            val result = withTimeoutOrNull(15_000) { read(song, hot, page, reply) } ?: throw IOException("获取评论超时")
            if (generation == version) selected = song
            return ResolvedComments(song, result)
        }
        // Bilibili comments always belong to the requested video, including empty/closed sections.
        if (reply != null || page > 1 || requested.source !in SearchCatalog.sources) return attempt(first)
        var empty: ResolvedComments? = null
        var failure: Exception? = null
        suspend fun available(song: Song): ResolvedComments? = try {
            attempt(song).also { if (it.page.comments.isEmpty()) empty = empty ?: it }.takeIf { it.page.comments.isNotEmpty() }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = e; null }
        available(first)?.let { return it }
        val alternatives = try { matches(requested) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = e; emptyList() }
        for (candidate in alternatives.filter { it.source in SearchCatalog.sources && it.source != first.source }.distinctBy { it.source }) {
            available(candidate)?.let { return it }
        }
        if (empty != null) {
            if (generation == version) selected = empty!!.song
            return empty!!
        }
        throw failure ?: IOException("暂无可用评论")
    }
}
