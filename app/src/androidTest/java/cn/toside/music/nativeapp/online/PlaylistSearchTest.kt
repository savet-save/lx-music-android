package cn.toside.music.nativeapp.online

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistSearchTest {
    @Test fun mapsProviderSearchResultsToPlayablePlaylistIds() {
        val parser = PlaylistSearch()
        val netease = parser.parse(JSONArray("""[{"id":123,"name":"Mix","creator":{"nickname":"User"},"coverImgUrl":"//img.example/wy.png"}]"""), "wy").single()
        assertEquals("User", netease.author)
        assertEquals("https://img.example/wy.png", netease.coverUrl)
        val qq = parser.parse(JSONArray("""[{"dissid":234,"dissname":"Rock &amp; Roll","creator":{"name":"User"},"imgurl":"https://img.example/tx.png"}]"""), "tx").single()
        assertEquals("Rock & Roll", qq.name)
        assertEquals("https://img.example/tx.png", qq.coverUrl)
        val kuwo = parser.parse(JSONArray("""[{"playlistid":345,"name":"Mix","nickname":"User","pic":"https://img.example/kw.png"}]"""), "kw").single()
        assertEquals("8", kuwo.digest)
        assertEquals("https://img.example/kw.png", kuwo.coverUrl)
        val migu = parser.parse(JSONArray("""[{"id":456,"name":"Mix","userName":"User","musicListPicUrl":"https://img.example/mg.png"},{"id":"bad"}]"""), "mg").single()
        assertEquals("456", migu.id)
        assertEquals("User", migu.author)
        assertEquals("https://img.example/mg.png", migu.coverUrl)
        val kugou = KugouPlaylists().parseLists(JSONArray("""[{"specialid":567,"specialname":"Mix","imgurl":"https://img.example/{size}/kg.png"}]""")).single()
        assertEquals("https://img.example/256/kg.png", kugou.coverUrl)
        assertEquals("", parser.parse(JSONArray("""[{"id":678,"name":"No cover","coverImgUrl":"invalid"}]"""), "wy").single().coverUrl)
    }
}
