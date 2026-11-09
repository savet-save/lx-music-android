package cn.toside.music.nativeapp.online

import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.utils.NativeHttp
import org.json.JSONArray
import org.json.JSONObject

internal object OnlineArtwork {
    private val qqMid = Regex("[A-Za-z0-9]+")

    suspend fun url(song: Song, info: JSONObject): String {
        val id = info.optString("songmid").takeIf { it.isNotBlank() && it != "null" } ?: return ""
        return when (song.source) {
            "kw" -> NativeHttp.text("http://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic&pictype=500&size=500&rid=${NativeHttp.encode(id)}",
                maxBytes = 4096).trim()
            "kg" -> kugou(song, info, id)
            "tx" -> qq(info, id)
            "wy" -> {
                val entries = JSONArray().put(JSONObject().put("id", id))
                val data = NeteaseApi().publicRequest("/weapi/v3/song/detail", JSONObject().put("c", entries.toString())
                    .put("ids", JSONArray().put(id).toString()))
                data.optJSONArray("songs")?.optJSONObject(0)?.optJSONObject("al")?.optString("picUrl").orEmpty()
            }
            "mg" -> OnlineSongDetails.migu(info)?.optJSONArray("albumImgs")?.optJSONObject(0)?.optString("img").orEmpty()
            else -> ""
        }
    }

    private suspend fun qq(info: JSONObject, id: String): String {
        fun valid(mid: String) = mid != "null" && qqMid.matches(mid)
        val album = sequenceOf(info.optString("albumMid"), info.optString("albumId")).firstOrNull(::valid)
        if (album != null) return "https://y.gtimg.cn/music/photo_new/T002R500x500M000$album.jpg"
        val track = OnlineSongDetails.qq(id) ?: return ""
        val albumMid = track.optJSONObject("album")?.optString("mid").orEmpty()
        if (valid(albumMid)) return "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumMid.jpg"
        val singerMid = track.optJSONArray("singer")?.optJSONObject(0)?.optString("mid").orEmpty()
        return if (valid(singerMid)) "https://y.gtimg.cn/music/photo_new/T001R500x500M000$singerMid.jpg" else ""
    }

    private suspend fun kugou(song: Song, info: JSONObject, id: String): String {
        val hash = info.optString("hash").takeIf { it.isNotBlank() && it != "null" } ?: return ""
        val audioId = id.toLongOrNull() ?: info.optString("audioId").substringBefore('_').toLongOrNull()
        val resource = JSONObject().put("album_audio_id", audioId ?: "").put("album_id", info.opt("albumId") ?: "")
            .put("hash", hash).put("id", 0).put("name", "${song.artist} - ${song.title}.mp3").put("type", "audio")
        val body = JSONObject().put("appid", 1001).put("area_code", "1").put("behavior", "play").put("clientver", "9020")
            .put("need_hash_offset", 1).put("relate", 1).put("resource", JSONArray().put(resource))
            .put("token", "").put("userid", 0).put("vip", 0)
        val response = NativeHttp.json("http://media.store.kugou.com/v1/get_res_privilege", body,
            headers = mapOf("KG-RC" to "1", "KG-THash" to "expand_search_manager.cpp:852736169:451",
                "User-Agent" to "KuGou2012-9020-ExpandSearchManager"))
        if (response.optInt("error_code", -1) != 0) return ""
        val cover = response.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("info") ?: return ""
        val size = cover.optJSONArray("imgsize")?.optInt(0)?.takeIf { it > 0 } ?: 400
        return cover.optString("image").replace("{size}", size.toString())
    }
}
