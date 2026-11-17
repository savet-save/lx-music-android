package cn.toside.music.nativeapp.online

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.toside.music.nativeapp.data.Song
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class BilibiliCommentsTest {
    private val song = Song(1, Uri.EMPTY, "video", "UP", 1000, "bili", """{"bvid":"BV1xx411c7mD","aid":42}""")
    private fun data(id: String = "1", next: String = "cursor-2", end: Boolean = false) = JSONObject().put("cursor",
        JSONObject().put("all_count", 51).put("is_end", end).put("pagination_reply", JSONObject().put("next_offset", next)))
        .put("replies", org.json.JSONArray().put(JSONObject().put("rpid_str", id).put("content", JSONObject().put("message", "text"))))
    @Test fun parsesAvatarAuthorDateLikesPicturesAndLoadedRepliesWithoutLosingLargeIds() {
        val response = JSONObject("""{"cursor":{"all_count":51,"is_end":false},"replies":[{
          "rpid_str":"987654321012345678","member":{"uname":"reader","avatar":"//i0.hdslb.com/face.jpg"},
          "ctime":1700000000,"like":5080,"rcount":3,
          "content":{"message":"line one\nline two","pictures":[{"img_src":"https://i0.hdslb.com/picture.jpg"}]},
          "replies":[{"rpid":2,"member":{"uname":"child"},"content":{"message":"reply"}}]
        }]}""")
        val result = BilibiliComments.parse(response, 1)
        val comment = result.comments.single()
        assertEquals("987654321012345678", comment.id); assertEquals("reader", comment.name)
        assertEquals("//i0.hdslb.com/face.jpg", comment.avatar); assertEquals(5080L, comment.likes)
        assertEquals("1700000000", comment.date); assertEquals(3, comment.replyCount)
        assertEquals("reply", comment.replies.single().text); assertEquals(1, comment.images.size)
        assertEquals(51, result.total); assertTrue(result.hasMore)
    }
    @Test fun signedReadOnlyMainRequestsKeepSeparateHotAndLatestOffsets() = runBlocking {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val api = BilibiliComments(null) { path, parameters, signed ->
            assertEquals("/x/v2/reply/wbi/main", path); assertTrue(signed)
            requests.add(path to parameters)
            data(next = "next-${parameters.getValue("mode")}")
        }
        api.load(song, true, 1); api.load(song, false, 1); api.load(song, true, 2); api.load(song, false, 2)
        assertEquals(listOf("3", "2", "3", "2"), requests.map { it.second["mode"] })
        assertEquals(listOf("", "", "next-3", "next-2"), requests.map { JSONObject(it.second.getValue("pagination_str")).getString("offset") })
        assertTrue(requests.all { it.second["oid"] == "42" && it.second["type"] == "1" })
        assertTrue(requests.none { it.second.containsKey("csrf") })
    }
    @Test fun failedPageDoesNotConsumeTheCursorAndEndOfListStopsPaging() = runBlocking {
        var calls = 0
        val offsets = mutableListOf<String>()
        val api = BilibiliComments(null) { _, parameters, _ ->
            offsets.add(JSONObject(parameters.getValue("pagination_str")).getString("offset"))
            if (++calls == 2) throw IOException("temporary")
            data(end = calls > 2)
        }
        assertTrue(api.load(song, true, 1).hasMore)
        try { api.load(song, true, 2); fail("Expected request failure") } catch (_: IOException) { }
        assertFalse(api.load(song, true, 2).hasMore)
        assertEquals(listOf("", "cursor-2", "cursor-2"), offsets)
    }
    @Test fun resolvesVideoAidOnceAndLoadsRepliesUsingOnlyTheReadEndpoint() = runBlocking {
        val paths = mutableListOf<String>()
        val api = BilibiliComments(null) { path, parameters, signed ->
            paths.add(path); assertFalse(signed)
            when (path) {
                "/x/web-interface/view" -> JSONObject().put("aid", 42)
                "/x/v2/reply/reply" -> {
                    assertEquals("42", parameters["oid"]); assertEquals("100", parameters["root"])
                    JSONObject("""{"page":{"count":1,"size":20},"replies":[{"rpid":2,"content":{"message":"reply"}}]}""")
                }
                else -> error("No mutation endpoints are allowed")
            }
        }
        val video = song.copy(sourceInfo = """{"bvid":"BV1xx411c7mD"}""")
        assertEquals("reply", api.load(video, false, 1, "100").comments.single().text)
        api.load(video, false, 1, "100")
        assertEquals(listOf("/x/web-interface/view", "/x/v2/reply/reply", "/x/v2/reply/reply"), paths)
    }
    @Test fun numericCursorFallbackPreservesTheDocumentedSortSpecificShape() {
        val data = JSONObject("""{"cursor":{"next":8}}""")
        val hot = JSONObject(BilibiliComments.nextOffset(data, true))
        val latest = JSONObject(BilibiliComments.nextOffset(data, false))
        assertEquals(1, hot.getInt("type")); assertEquals(8, hot.getJSONObject("data").getInt("pn"))
        assertEquals(3, latest.getInt("type")); assertEquals(8, latest.getJSONObject("Data").getInt("cursor"))
    }
    @Test fun existingMusicProvidersExposeAvatarFieldsAlongsideTheirCommentData() {
        val api = OnlineComments()
        assertEquals("https://image.test/kw", api.parse("kw", JSONObject("""{"comments_counts":1,"comments":[{"id":"1","u_pic":"https://image.test/kw"}]}"""), false, 1).comments.single().avatar)
        assertEquals("https://image.test/wy", api.parse("wy", JSONObject("""{"data":{"totalCount":1,"comments":[{"commentId":1,"user":{"avatarUrl":"https://image.test/wy"}}]}}"""), false, 1).comments.single().avatar)
        assertEquals("https://image.test/tx", api.parse("tx", JSONObject("""{"req":{"data":{"CommentList":{"Total":1,"Comments":[{"SeqNo":"1","Avatar":"https://image.test/tx"}]}}}}"""), true, 1).comments.single().avatar)
    }
}
