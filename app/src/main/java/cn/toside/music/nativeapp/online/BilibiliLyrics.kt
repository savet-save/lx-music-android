package cn.toside.music.nativeapp.online

import android.content.Context
import cn.toside.music.nativeapp.data.AppPreferences
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.lyrics.LyricLine
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Use the preferred video lyric source first, retaining the other as a fallback. */
internal class BilibiliLyrics(private val context: Context,
    private val subtitles: suspend (Song) -> List<LyricLine> = { BilibiliApi(context).subtitles(JSONObject(requireNotNull(it.sourceInfo))) },
    private val matched: suspend (Song) -> List<LyricLine> = { MatchedLyrics(context).find(it) }) {
    suspend fun find(song: Song, preferSubtitles: Boolean = AppPreferences(context).preferBilibiliSubtitles): List<LyricLine> {
        val loaders = if (preferSubtitles) listOf(subtitles, matched) else listOf(matched, subtitles)
        var failure: Exception? = null
        for (load in loaders) {
            try {
                val lines = load(song)
                if (lines.any { it.text.isNotBlank() }) return lines
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e }
        }
        if (failure != null) throw failure
        return emptyList()
    }
}
