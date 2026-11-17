package cn.toside.music.nativeapp.online

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.lyrics.LyricLine
import cn.toside.music.nativeapp.lyrics.Lyrics
import cn.toside.music.nativeapp.source.UserSourceLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class BilibiliLyricsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val song = Song(-System.nanoTime(), Uri.EMPTY, "Fixture", "Singer", 10000, "bili", "{}")

    @Test fun subtitlesKeepVideoTimingAndClearTextDuringGaps() {
        val lines = BilibiliApi.parseSubtitles(JSONObject("""{"body":[
            {"from":4.25,"to":6.5,"content":"second"},
            {"from":1.125,"to":2.75,"content":" first "},
            {"from":-1,"to":2,"content":"invalid"},
            {"from":7,"to":6,"content":"invalid"},
            {"from":8,"to":9,"content":" "},
            {"to":9,"content":"invalid"}
        ]}"""))
        assertEquals(listOf(LyricLine(1125, "first"), LyricLine(2750, ""), LyricLine(4250, "second"), LyricLine(6500, "")), lines)
        assertTrue(BilibiliApi.parseSubtitles(JSONObject()).isEmpty())
    }

    @Test fun subtitleTracksPreferChineseAndHumanCaptionsAndUseHttpsCdns() {
        val urls = BilibiliApi.subtitleUrls(JSONObject("""{"subtitle":{"subtitles":[
            {"lan":"en","subtitle_url":"https://aisubtitle.hdslb.com/english.json"},
            {"lan":"ai-zh","ai_type":1,"subtitle_url":"//aisubtitle.hdslb.com/ai.json"},
            {"lan":"zh-CN","subtitle_url":"//aisubtitle.hdslb.com/manual.json"},
            {"lan":"zh-CN","subtitle_url":"http://aisubtitle.hdslb.com/insecure.json"},
            {"lan":"zh-CN","subtitle_url":"https://hdslb.com.evil.example/invalid.json"}
        ]}}"""))
        assertEquals(listOf("manual.json", "ai.json", "english.json"), urls.map { it.substringAfterLast('/') })
        assertTrue(urls.all { it.startsWith("https://") })
    }

    @Test fun eachModeUsesItsPreferredResultWithoutCallingTheFallback() = runBlocking {
        for (preferSubtitles in listOf(false, true)) {
            val calls = mutableListOf<String>()
            val loader = BilibiliLyrics(context,
                { calls.add("subtitle"); listOf(LyricLine(1000, "subtitle")) },
                { calls.add("match"); listOf(LyricLine(2000, "match")) })
            val expected = if (preferSubtitles) "subtitle" else "match"
            assertEquals(expected, loader.find(song, preferSubtitles).single().text)
            assertEquals(listOf(expected), calls)
        }
    }

    @Test fun missingOrFailedPreferredLyricsFallBackInBothDirections() = runBlocking {
        for (preferSubtitles in listOf(false, true)) for (fails in listOf(false, true)) {
            val calls = mutableListOf<String>()
            val unavailable: suspend (Song) -> List<LyricLine> = {
                calls.add("preferred")
                if (fails) throw IOException("unavailable") else emptyList()
            }
            val fallback: suspend (Song) -> List<LyricLine> = { calls.add("fallback"); listOf(LyricLine(1000, "fallback")) }
            val loader = if (preferSubtitles) BilibiliLyrics(context, unavailable, fallback) else BilibiliLyrics(context, fallback, unavailable)
            assertEquals("fallback", loader.find(song, preferSubtitles).single().text)
            assertEquals(listOf("preferred", "fallback"), calls)
        }
    }

    @Test fun cancellationDoesNotStartTheFallback() = runBlocking {
        val loader = BilibiliLyrics(context, { throw CancellationException("cancelled") }, { error("Must not start fallback") })
        try { loader.find(song, true); fail("Must propagate cancellation") }
        catch (_: CancellationException) { }
    }

    @Test fun modeCachesAreSeparateAndImportedLyricsRemainFirst() = runBlocking {
        val lyrics = Lyrics(context)
        val imported = File(context.filesDir, "lyrics/bili_${song.id}.lrc")
        try {
            lyrics.cache(song, listOf(LyricLine(1000, "matched")), false)
            assertTrue(lyrics.load(song.id, "bili", true).isEmpty())
            lyrics.cache(song, listOf(LyricLine(2000, "subtitle")), true)
            assertEquals("matched", lyrics.load(song.id, "bili", false).single().text)
            assertEquals("subtitle", lyrics.load(song.id, "bili", true).single().text)
            imported.writeText("[00:03.00]imported")
            for (mode in listOf(false, true)) assertEquals("imported", lyrics.load(song.id, "bili", mode).single().text)
            imported.delete()
            lyrics.clearOnlineCache(song)
            for (mode in listOf(false, true)) assertTrue(lyrics.load(song.id, "bili", mode).isEmpty())
        } finally { imported.delete(); lyrics.clearOnlineCache(song) }
    }

    @Test fun onlyPackagedScriptsAreProtectedEvenWhenImportedNamesAreTheSame() {
        val library = UserSourceLibrary(context)
        for (asset in listOf(UserSourceLibrary.BUNDLED_ASSET, UserSourceLibrary.BUNDLED_KUWO_ASSET, UserSourceLibrary.BUNDLED_BILIBILI_ASSET)) {
            val entry = UserSourceLibrary.metadata(context.assets.open(asset).bufferedReader().use { it.readText() })
            assertTrue(library.isBundled(entry.id))
            assertFalse(library.isBundled(UserSourceLibrary.metadata("/**\n * @name ${entry.name}\n */\n// imported fixture").id))
        }
    }
}
