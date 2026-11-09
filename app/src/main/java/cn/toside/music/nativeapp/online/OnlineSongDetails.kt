package cn.toside.music.nativeapp.online

import cn.toside.music.nativeapp.utils.NativeHttp
import org.json.JSONObject

internal object OnlineSongDetails {
    suspend fun qq(mid: String): JSONObject? {
        if (mid.isBlank() || mid == "null") return null
        val data = NativeHttp.json("https://u.y.qq.com/cgi-bin/musicu.fcg", JSONObject().put("comm", JSONObject().put("ct", "19").put("cv", "1859").put("uin", "0"))
            .put("req", JSONObject().put("module", "music.pf_song_detail_svr").put("method", "get_song_detail_yqq")
                .put("param", JSONObject().put("song_type", 0).put("song_mid", mid))))
        return data.optJSONObject("req")?.optJSONObject("data")?.optJSONObject("track_info")
    }

    suspend fun migu(info: JSONObject): JSONObject? {
        val id = sequenceOf(info.optString("copyrightId"), info.optString("songmid"))
            .firstOrNull { it.isNotBlank() && it != "null" } ?: return null
        val data = NativeHttp.json("https://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do?resourceType=2",
            form = mapOf("resourceId" to id))
        return (data.optJSONObject("data") ?: data).optJSONArray("resource")?.optJSONObject(0)
    }
}
