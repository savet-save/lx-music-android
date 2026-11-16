package cn.toside.music.nativeapp.online

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.lyrics.LyricLine
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BilibiliTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun membershipDisplayRequiresAnActiveMembershipEvenWhenOldVipDetailsRemain() {
        val expired = BilibiliApi.parseAccount(JSONObject("""{"isLogin":true,"uname":"Fixture","vipStatus":0,"vipType":2,"vip_label":{"text":"年度大会员"}}"""))
        assertEquals(BilibiliAccount("Fixture"), expired)
        val ordinary = BilibiliApi.parseAccount(JSONObject("""{"isLogin":true,"uname":"Fixture"}"""))
        assertEquals(BilibiliAccount("Fixture"), ordinary)
        try { BilibiliApi.parseAccount(JSONObject("""{"isLogin":false,"vipStatus":1}""")); fail("Expired login") }
        catch (_: IllegalStateException) { }
    }
    @Test fun activeMembershipKeepsTheOfficialLabelAndAllowsMissingLabels() {
        for (label in listOf("大会员", "年度大会员", "超级大会员")) {
            val account = BilibiliApi.parseAccount(JSONObject().put("isLogin", true).put("uname", "Fixture").put("vipStatus", 1)
                .put("vip_label", JSONObject().put("text", label)))
            assertEquals(BilibiliAccount("Fixture", true, label), account)
        }
        assertEquals(BilibiliAccount("42", true), BilibiliApi.parseAccount(JSONObject("""{"isLogin":true,"mid":42,"vipStatus":1}""")))
    }
    @Test fun wbiSignatureMatchesPublishedExampleAndEscapesSpaces() {
        val signed=BilibiliApi.sign(mapOf("foo" to "114","bar" to "514","zab" to "1919810"),"7cd084941338484aae1ad9425b84077c","4932caff0ff746eab6f01bf08b70ac45",1702204169)
        assertTrue(signed.endsWith("w_rid=8f6f2b5b3d485fe1886cec6a0be8c5d4"))
        val escaped=BilibiliApi.sign(mapOf("keyword" to "茶花 开了!'()*"),"7cd084941338484aae1ad9425b84077c","4932caff0ff746eab6f01bf08b70ac45",1702204169)
        assertTrue(escaped.contains("%20"));assertFalse(escaped.contains("+"));assertFalse(escaped.contains("%21"))
    }
    @Test fun videoSearchPreservesIdsDurationAndArtworkWithoutHtml() {
        val data=JSONObject("""{"result":[{"aid":42,"bvid":"BV1xx411c7mD","title":"王睿卓 - <em>茶花开了</em>","author":"上传者","duration":"4:30","pic":"//i0.hdslb.com/cover.jpg"}]}""")
        val song=BilibiliApi.parseSearch(data).single()
        assertEquals("bili",song.source);assertEquals(270000,song.duration);assertEquals(42,song.id)
        assertEquals("https://i0.hdslb.com/cover.jpg",JSONObject(song.sourceInfo!!).getString("img"))
        val reference=MatchedLyrics.reference(song)
        assertEquals("王睿卓",reference.artist)
        assertFalse(reference.artist.contains("上传者"))
    }
    @Test fun audioSelectionUsesTheHighestAccessibleAudioAndRejectsVideoOnly() {
        val data=JSONObject("""{"dash":{"audio":[{"codecs":"mp4a.40.2","mimeType":"audio/mp4","bandwidth":64000,"baseUrl":"https://a.example/low.m4a"},{"codecs":"mp4a.40.2","mime_type":"audio/mp4","bandwidth":192000,"base_url":"https://a.example/high.m4a"}]}}""")
        assertEquals("https://a.example/high.m4a",BilibiliApi.audioUrl(data))
        try { BilibiliApi.audioUrl(JSONObject("""{"dash":{"video":[]}}"""));fail("No audio") } catch(_:IllegalStateException) { }
        try { BilibiliApi.checked(JSONObject("""{"code":0,"data":{"v_voucher":"redacted"}}"""));fail("Verification required") } catch(_:IllegalStateException) { }
    }
    @Test fun downloadChoicesPreserveActualAacBitratesAndTrackIds() {
        val data = JSONObject("""{"dash":{"audio":[
            {"id":30216,"codecs":"mp4a.40.2","mimeType":"audio/mp4","bandwidth":64000,"baseUrl":"https://a.example/low.m4a"},
            {"id":30280,"codecs":"mp4a.40.2","mimeType":"audio/mp4","bandwidth":192000,"baseUrl":"https://a.example/high.m4a"},
            {"id":30251,"codecs":"fLaC","mimeType":"audio/flac","bandwidth":1000000,"baseUrl":"https://a.example/lossless.flac"}
        ]}}""")
        val choices = BilibiliApi.parseAudioStreams(data)
        assertEquals(listOf(192000L, 64000L), choices.map { it.bitrate })
        assertEquals(listOf("original", "aac-30216"), choices.map { it.quality })
        assertEquals("https://a.example/low.m4a", choices.single { it.quality == "aac-30216" }.url)
    }
    @Test fun audioKeepsDistinctHttpsBackupCdnsForPlaybackRecovery() {
        val data = JSONObject("""{"dash":{"audio":[{"id":30280,"codecs":"mp4a.40.2","mimeType":"audio/mp4","bandwidth":192000,
          "baseUrl":"https://primary.example/audio.m4a","backup_url":["https://backup.example/audio.m4a","https://backup.example/audio.m4a","http://insecure.example/audio.m4a","file:///private"]}]}}""")
        val audio = BilibiliApi.parseAudio(data)
        assertEquals(listOf("https://backup.example/audio.m4a"), audio.backupUrls)
        assertEquals("https://primary.example/audio.m4a", audio.url)
    }
    @Test fun lyricsMatchAnotherPlatformOnlyForTheSameRecordingDurationAndVersion() = runBlocking {
        val song=Song(42,Uri.EMPTY,"茶花开了","王睿卓",270000,"local")
        val wrongDuration=song.copy(id=43,duration=240000,source="kw")
        val wrongVersion=song.copy(id=44,title="茶花开了 (Live)",source="kw")
        val right=song.copy(id=45,duration=271000,source="kw")
        val loaded=mutableListOf<Long>()
        val lyrics=MatchedLyrics(context,{_,source -> if(source=="kw") listOf(wrongDuration,wrongVersion,right) else emptyList() }, {
            loaded.add(it.id);listOf(LyricLine(1000,"第一句"),LyricLine(265000,"最后一句"))
        }).find(song)
        assertEquals(2,lyrics.size);assertEquals(listOf(45L),loaded)
        val unavailable=MatchedLyrics(context,{_,source -> if(source=="kw") listOf(wrongDuration,wrongVersion) else emptyList() }, { error("wrong recording must not be fetched") }).find(song)
        assertTrue(unavailable.isEmpty())
    }
    @Test fun bilibiliTitlesNeverUseTheUploaderAsTheSinger() {
        val song=Song(1,Uri.EMPTY,"《茶花开了》【动态歌词】","某音乐频道",270000,"bili")
        val reference=MatchedLyrics.reference(song)
        assertEquals("茶花开了",reference.title);assertEquals("",reference.artist)
    }
    @Test fun credentialsAreEncryptedAndCanBeCleared() {
        val credentials=BilibiliCredentials(context)
        val previous=credentials.load()
        val token="fixture-secret-${System.nanoTime()}"
        try {
            credentials.save(token);assertEquals(token,credentials.load())
            val saved=context.getSharedPreferences("bilibili-credentials",0).all.values.joinToString()
            assertFalse(saved.contains(token));credentials.clear();assertEquals("",credentials.load())
        } finally { if(previous.isNotEmpty()) credentials.save(previous) else credentials.clear() }
    }
    @Test fun videoLyricsAcceptEitherTitleSingerOrderWithoutRepeatingProviderSearches()=runBlocking {
        val song=Song(1,Uri.EMPTY,"茶花开了 - 王睿卓（动态歌词）","上传者",270000,"bili")
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val candidate=Song(2,Uri.EMPTY,"茶花开了","王睿卓",270000,"kw","{}")
        val result=MatchedLyrics(context,{_,source -> calls.incrementAndGet();if(source=="kw") listOf(candidate) else emptyList() },{listOf(LyricLine(1000,"matched"))}).find(song)
        assertEquals("matched",result.single().text)
        assertEquals(5,calls.get())
    }
}
