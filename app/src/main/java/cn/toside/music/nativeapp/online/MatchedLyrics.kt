package cn.toside.music.nativeapp.online

import android.content.Context
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.lyrics.LyricLine
import cn.toside.music.nativeapp.player.SongMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext

/** Match the recording, including its duration/version; never stretch another recording's timestamps. */
internal class MatchedLyrics(private val context: Context,
    private val search: suspend (String, String) -> List<Song> = { query, source -> OnlineSearch().search(query, source = source) },
    private val load: suspend (Song) -> List<LyricLine> = { OnlineLyrics().forSong(it) }) {
    suspend fun find(song: Song): List<LyricLine> {
        val reference = reference(song)
        val references=if(song.source=="bili" && reference.artist.isNotBlank()) listOf(reference,reference.copy(title=reference.artist,artist=reference.title)) else listOf(reference)
        val snapshots=java.util.concurrent.ConcurrentHashMap<String,List<Song>>()
        val matcher = SongMatcher(context, CoroutineScope(currentCoroutineContext())) { query,source ->
            snapshots[source] ?: search(query,source).also { snapshots[source]=it }
        }
        var failure: Exception? = null
        for (alternative in references) {
            val candidates = matcher.find(alternative).filter { candidate ->
                titleKey(alternative.title) == titleKey(candidate.title) && song.duration > 0 && candidate.duration > 0 && kotlin.math.abs(song.duration-candidate.duration) <= 5000
            }
            for (candidate in candidates) {
                try {
                    val lines = load(candidate).filter { it.text.isNotBlank() }
                    if (lines.isNotEmpty() && lines.all { it.timeMs <= song.duration + 1500 }) return lines
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure = e }
            }
        }
        if (failure != null) throw failure
        return emptyList()
    }
    companion object {
        private fun titleKey(value:String)=value.lowercase(java.util.Locale.ROOT).replace(Regex("[\\p{P}\\p{Z}\\s]"),"")
        internal fun reference(song: Song): Song {
            if (song.source != "bili") return song.copy(artist = song.artist.takeUnless { it == "<unknown>" }.orEmpty())
            val title = song.title.replace(Regex("[【\\[（(](?:[^】\\]）)]*(?:歌词|字幕|高清|4K|1080|官方MV)[^】\\]）)]*)[】\\]）)]", RegexOption.IGNORE_CASE)) { match ->
                if(listOf("live","现场","翻唱","伴奏","remix").any { match.value.contains(it,true) }) match.value else ""
            }.trim()
            val book = Regex("《([^》]+)》").find(title)
            val split = title.split(Regex("\\s*[-–—]\\s*"), limit = 2)
            return when {
                book != null -> song.copy(title = book.groupValues[1], artist = "")
                split.size == 2 -> song.copy(title = split[1].trim(), artist = split[0].trim())
                else -> song.copy(title = title, artist = "")
            }
        }
    }
}
