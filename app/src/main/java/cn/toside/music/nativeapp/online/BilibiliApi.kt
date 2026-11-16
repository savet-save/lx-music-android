package cn.toside.music.nativeapp.online

import android.content.Context
import android.net.Uri
import android.text.Html
import cn.toside.music.nativeapp.data.PlaylistCredentials
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.utils.readBounded
import cn.toside.music.nativeapp.utils.NativeHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal class BilibiliCredentials(context: Context) {
    private val store = PlaylistCredentials(context, "lx-bilibili-credentials", "bilibili-credentials")
    fun load() = store.load("1").orEmpty()
    fun save(value: String) = store.save("1", value.trim().removePrefix("SESSDATA="))
    fun clear() = store.clear()
}

/** Web endpoints documented by bilibili-plugins/bilibili-api-collect. No account data goes to CDNs. */
internal class BilibiliApi(private val context: Context? = null, private val session: String? = null) {
    private suspend fun json(path: String, parameters: Map<String, String> = emptyMap(), signed: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        val query = if (signed) {
            val nav = request("https://api.bilibili.com/x/web-interface/nav")
            val images = nav.getJSONObject("data").getJSONObject("wbi_img")
            sign(parameters, images.getString("img_url").substringAfterLast('/').substringBefore('.'),
                images.getString("sub_url").substringAfterLast('/').substringBefore('.'), System.currentTimeMillis() / 1000)
        } else parameters.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        checked(request("https://api.bilibili.com$path?$query"))
    }
    private fun request(url: String): JSONObject {
        val token = session ?: context?.let { BilibiliCredentials(it).load() }.orEmpty()
        val builder = Request.Builder().url(url)
        audioHeaders.forEach { (name, value) -> builder.header(name, value) }
        if (token.isNotBlank()) PlaylistCredentials.validate(token)
        val existing = jar.loadForRequest(builder.build().url).filterNot { it.name == "SESSDATA" }.joinToString("; ") { "${it.name}=${it.value}" }
        val cookie = listOf(existing, if(token.isNotBlank()) "SESSDATA=$token" else "").filter { it.isNotEmpty() }.joinToString("; ")
        if(cookie.isNotBlank()) builder.header("Cookie", cookie)
        val request = builder.build()
        directClient.newCall(request).execute().use { response ->
            jar.saveFromResponse(request.url, response.headers("Set-Cookie").mapNotNull { Cookie.parse(request.url, it) })
            check(response.isSuccessful) { "B站请求失败（HTTP ${response.code}），请在设置中检查登录状态" }
            val body = response.body ?: error("B站返回空响应")
            require(body.contentLength() <= 8 * 1024 * 1024) { "B站响应过大" }
            return JSONObject(body.byteStream().use { it.readBounded(8 * 1024 * 1024).toString(Charsets.UTF_8) })
        }
    }
    suspend fun account(): String = withContext(Dispatchers.IO) {
        val data = checked(request("https://api.bilibili.com/x/web-interface/nav"))
        check(data.optBoolean("isLogin")) { "登录已失效，请重新填写 SESSDATA" }
        data.optString("uname").ifBlank { data.optLong("mid").toString() }
    }
    suspend fun search(query: String, page: Int): List<Song> {
        require(query.isNotBlank() && page > 0)
        withContext(Dispatchers.IO) {
            // Obtain the anonymous buvid cookie through the normal web entry point.
            client.newCall(Request.Builder().url("https://www.bilibili.com/").header("User-Agent", audioHeaders.getValue("User-Agent")).build()).execute().close()
        }
        return parseSearch(json("/x/web-interface/wbi/search/type", mapOf("search_type" to "video", "keyword" to query, "page" to "$page", "page_size" to "20"), true))
    }
    suspend fun audio(info: JSONObject): String {
        val bvid = info.getString("bvid")
        require(bvid.matches(Regex("BV[0-9A-Za-z]{10}"))) { "B站视频编号无效" }
        val view = json("/x/web-interface/view", mapOf("bvid" to bvid))
        val cid = view.getJSONArray("pages").getJSONObject(0).getLong("cid")
        return audioUrl(json("/x/player/wbi/playurl", mapOf("bvid" to bvid, "cid" to "$cid", "fnval" to "16", "fnver" to "0", "fourk" to "0"), true))
    }
    suspend fun firstPart(song: Song): Song {
        val info=JSONObject(requireNotNull(song.sourceInfo))
        val bvid=info.getString("bvid")
        require(bvid.matches(Regex("BV[0-9A-Za-z]{10}")))
        val view=json("/x/web-interface/view",mapOf("bvid" to bvid))
        val first=view.getJSONArray("pages").getJSONObject(0)
        info.put("cid",first.getLong("cid")).put("img",view.optString("pic",info.optString("img")))
        return song.copy(duration=first.getLong("duration")*1000,sourceInfo=info.toString())
    }
    companion object {
        val audioHeaders = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36", "Referer" to "https://www.bilibili.com/")
        private val cookies = mutableListOf<Cookie>()
        private val jar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, values: List<Cookie>) = synchronized(cookies) {
                for (cookie in values.filterNot { it.name == "SESSDATA" }) { cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }; cookies.add(cookie) }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) {
                cookies.removeAll { it.expiresAt < System.currentTimeMillis() }; cookies.filter { it.matches(url) }
            }
        }
        private val client = OkHttpClient.Builder().cookieJar(jar).connectTimeout(13, TimeUnit.SECONDS).readTimeout(13, TimeUnit.SECONDS).build()
        private val directClient = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).build()
        private val mix = intArrayOf(46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52)
        private fun encode(value: String) = NativeHttp.encode(value).replace("+", "%20").replace("%7E", "~")
        internal fun sign(parameters: Map<String, String>, img: String, sub: String, timestamp: Long): String {
            val raw = img + sub; require(raw.length == 64)
            val key = mix.take(32).map { raw[it] }.joinToString("")
            val query = (parameters + ("wts" to "$timestamp")).toSortedMap().entries.joinToString("&") {
                "${encode(it.key)}=${encode(it.value.replace(Regex("[!'()*]"), ""))}"
            }
            val digest = MessageDigest.getInstance("MD5").digest((query + key).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            return "$query&w_rid=$digest"
        }
        internal fun checked(root: JSONObject): JSONObject {
            val code = root.optInt("code", -1)
            check(code == 0) { "B站接口错误（$code）${if (code in setOf(-101, -111, -412, -352)) "，请在设置中登录或更新 SESSDATA" else "：${root.optString("message").take(120)}"}" }
            val data = root.optJSONObject("data") ?: error("B站没有返回有效数据")
            check(!data.has("v_voucher")) { "B站需要验证，请在官方客户端完成验证后重试" }
            return data
        }
        internal fun parseSearch(data: JSONObject): List<Song> = buildList {
            val items = data.optJSONArray("result") ?: JSONArray()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val bvid = item.optString("bvid"); if (!bvid.matches(Regex("BV[0-9A-Za-z]{10}"))) continue
                val title = Html.fromHtml(item.optString("title"), Html.FROM_HTML_MODE_LEGACY).toString()
                val duration = item.optString("duration").split(':').fold(0L) { total, n -> total * 60 + (n.toLongOrNull() ?: 0) } * 1000
                val info = JSONObject().put("songmid", bvid).put("bvid", bvid).put("img", item.optString("pic").let { if (it.startsWith("//")) "https:$it" else it })
                    .put("uploader", item.optString("author")).put("name", title).put("singer", item.optString("author"))
                add(Song(item.optLong("aid", bvid.hashCode().toLong()), Uri.EMPTY, title, "UP · ${item.optString("author")}", duration, "bili", info.toString()))
            }
        }
        internal fun audioUrl(data: JSONObject): String {
            val audio = data.optJSONObject("dash")?.optJSONArray("audio") ?: error("该视频没有可用的独立音频流，可能需要登录")
            return (0 until audio.length()).mapNotNull { audio.optJSONObject(it) }.filter {
                it.optString("codecs").startsWith("mp4a") && it.optString("mimeType", it.optString("mime_type")).startsWith("audio/")
            }.maxByOrNull { it.optLong("bandwidth") }?.let { it.optString("baseUrl", it.optString("base_url")) }
                ?.takeIf { Uri.parse(it).scheme == "https" && !Uri.parse(it).host.isNullOrBlank() }
                ?: error("没有可用的 AAC 音频流")
        }
    }
}
