package cn.toside.music.nativeapp.online

import android.content.Context
import cn.toside.music.nativeapp.data.Song
import org.json.JSONArray
import org.json.JSONObject

/** Read-only endpoints: https://github.com/alittlehuaji/bilibili-api-collect-mirror/blob/master/docs/comment/list.md */
internal class BilibiliComments(context: Context?,
    private val request: suspend (String, Map<String, String>, Boolean) -> JSONObject = BilibiliApi(context)::json) {
    private val aids = mutableMapOf<String, String>()
    private val offsets = mutableMapOf<String, MutableMap<Int, String>>()

    suspend fun load(song: Song, hot: Boolean, page: Int, reply: String? = null): CommentPage {
        require(page > 0)
        val info = JSONObject(requireNotNull(song.sourceInfo))
        val bvid = info.getString("bvid")
        require(bvid.matches(Regex("BV[0-9A-Za-z]{10}"))) { "B站视频编号无效" }
        val aid = aids[bvid] ?: info.optLong("aid").takeIf { it > 0 }?.toString()
            ?: request("/x/web-interface/view", mapOf("bvid" to bvid), false).getLong("aid").also { require(it > 0) }.toString()
        aids[bvid] = aid
        if (reply != null) {
            require(reply.matches(Regex("[0-9]+")))
            return parse(request("/x/v2/reply/reply", mapOf("type" to "1", "oid" to aid, "root" to reply, "pn" to "$page", "ps" to "20"), false), page, true)
        }
        val key = "$bvid:$hot"
        val cursors = if (page == 1) mutableMapOf(1 to "").also { offsets[key] = it } else offsets[key]
            ?: error("评论分页已失效，请刷新重试")
        val offset = cursors[page] ?: error("评论分页已失效，请刷新重试")
        val data = request("/x/v2/reply/wbi/main", mapOf("type" to "1", "oid" to aid,
            "mode" to if (hot) "3" else "2", "plat" to "1", "web_location" to "1315875",
            "pagination_str" to JSONObject().put("offset", offset).toString()), true)
        val parsed = parse(data, page)
        val next = nextOffset(data, hot)
        val hasMore = parsed.hasMore && next.isNotEmpty() && next != offset
        if (hasMore) cursors[page + 1] = next
        return parsed.copy(hasMore = hasMore)
    }

    companion object {
        internal fun nextOffset(data: JSONObject, hot: Boolean): String {
            val cursor = data.optJSONObject("cursor") ?: return ""
            cursor.optJSONObject("pagination_reply")?.optString("next_offset")?.takeIf { it.isNotBlank() }?.let { return it }
            if (!cursor.has("next")) return ""
            val next = cursor.optLong("next", -1).takeIf { it >= 0 } ?: return ""
            return JSONObject().put("type", if (hot) 1 else 3).put("direction", 1)
                .put(if (hot) "data" else "Data", JSONObject().put(if (hot) "pn" else "cursor", next)).toString()
        }
        internal fun parse(data: JSONObject, page: Int, reply: Boolean = false): CommentPage {
            val comments = objects(data.optJSONArray("replies")).map { item(it) }.filter { it.id.isNotBlank() }.distinctBy { it.id }
            val paging = data.optJSONObject(if (reply) "page" else "cursor") ?: JSONObject()
            val total = paging.optLong(if (reply) "count" else "all_count", Long.MAX_VALUE).coerceAtLeast(0)
            val more = if (reply) comments.size >= paging.optInt("size", 20) && page.toLong() * paging.optInt("size", 20) < total
                else !paging.optBoolean("is_end", true)
            return CommentPage(comments, total, comments.isNotEmpty() && more)
        }
        private fun item(data: JSONObject, depth: Int = 0): MusicComment {
            val member = data.optJSONObject("member") ?: JSONObject()
            val content = data.optJSONObject("content") ?: JSONObject()
            return MusicComment(data.optString("rpid_str").ifBlank { data.optString("rpid") }, member.optString("uname"), content.optString("message"),
                data.optString("ctime"), data.optLong("like").coerceAtLeast(0), data.optJSONObject("reply_control")?.optString("location").orEmpty(),
                objects(content.optJSONArray("pictures")).mapNotNull { it.optString("img_src").ifBlank { it.optString("url") }.takeIf(String::isNotBlank) },
                if (depth < 3) objects(data.optJSONArray("replies")).map { item(it, depth + 1) } else emptyList(),
                data.optInt("rcount", data.optInt("count")).coerceAtLeast(0), member.optString("avatar"))
        }
        private fun objects(array: JSONArray?): List<JSONObject> = if (array == null) emptyList() else (0 until array.length()).mapNotNull(array::optJSONObject)
    }
}
