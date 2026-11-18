package cn.toside.music.nativeapp.online

import android.content.Context
import android.net.Uri
import android.text.Html
import cn.toside.music.nativeapp.data.PlaylistCredentials
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.lyrics.LyricLine
import cn.toside.music.nativeapp.utils.readBounded
import cn.toside.music.nativeapp.utils.NativeHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

internal data class BilibiliAccount(val name: String, val vipActive: Boolean = false, val vipLabel: String = "")
internal data class BilibiliAudio(val url: String, val bitrate: Long?, val quality: String = "original", val trackId: Long? = null,
    val backupUrls: List<String> = emptyList())

internal interface BilibiliAudioProvider {
    suspend fun audioStreams(info: JSONObject): List<BilibiliAudio>
    suspend fun audioStream(info: JSONObject, quality: String = "original"): BilibiliAudio =
        audioStreams(info).firstOrNull { it.quality == quality || quality == "aac-${it.trackId}" }?.copy(quality = quality)
            ?: error("该视频没有所选码率的 AAC 音频流")
}

/** Web endpoints documented by bilibili-plugins/bilibili-api-collect. No account data goes to CDNs. */
internal class BilibiliApi(private val context: Context? = null, private val session: String? = null) : BilibiliAudioProvider {
    internal suspend fun json(path: String, parameters: Map<String, String> = emptyMap(), signed: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
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
    suspend fun account(): BilibiliAccount = withContext(Dispatchers.IO) {
        parseAccount(checked(request("https://api.bilibili.com/x/web-interface/nav")))
    }
    suspend fun search(query: String, page: Int): List<Song> {
        require(query.isNotBlank() && page > 0)
        withContext(Dispatchers.IO) {
            // Obtain the anonymous buvid cookie through the normal web entry point.
            client.newCall(Request.Builder().url("https://www.bilibili.com/").header("User-Agent", audioHeaders.getValue("User-Agent")).build()).execute().close()
        }
        return parseSearch(json("/x/web-interface/wbi/search/type", mapOf("search_type" to "video", "keyword" to query, "page" to "$page", "page_size" to "20"), true))
    }
    suspend fun audio(info: JSONObject): String = audioStream(info).url
    override suspend fun audioStreams(info: JSONObject): List<BilibiliAudio> {
        val bvid = info.getString("bvid")
        require(bvid.matches(Regex("BV[0-9A-Za-z]{10}"))) { "B站视频编号无效" }
        val cid = info.optLong("cid").takeIf { it > 0 }
            ?: json("/x/web-interface/view", mapOf("bvid" to bvid)).getJSONArray("pages").getJSONObject(0).getLong("cid")
        return parseAudioStreams(json("/x/player/wbi/playurl", mapOf("bvid" to bvid, "cid" to "$cid", "fnval" to "16", "fnver" to "0", "fourk" to "0", "force_host" to "2"), true))
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
    suspend fun subtitles(info: JSONObject): List<LyricLine> = withContext(Dispatchers.IO) {
        val bvid = info.getString("bvid")
        require(bvid.matches(Regex("BV[0-9A-Za-z]{10}"))) { "B站视频编号无效" }
        val cid = info.optLong("cid").takeIf { it > 0 }
            ?: json("/x/web-interface/view", mapOf("bvid" to bvid)).getJSONArray("pages").getJSONObject(0).getLong("cid")
        val data = json("/x/player/wbi/v2", mapOf("bvid" to bvid, "cid" to "$cid"), true)
        var failure: Exception? = null
        for (url in subtitleUrls(data)) {
            try {
                // Subtitle CDNs never receive the account cookie or the API cookie jar.
                val request = Request.Builder().url(url).apply { audioHeaders.forEach { (key, value) -> header(key, value) } }.build()
                val lines = directClient.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "B站字幕请求失败（HTTP ${response.code}）" }
                    val body = response.body ?: error("B站字幕为空")
                    require(body.contentLength() <= 2 * 1024 * 1024) { "B站字幕过大" }
                    parseSubtitles(JSONObject(body.byteStream().use { it.readBounded(2 * 1024 * 1024).toString(Charsets.UTF_8) }))
                }
                if (lines.any { it.text.isNotBlank() }) return@withContext lines
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e }
        }
        if (failure != null) throw failure
        emptyList()
    }
    companion object {
        internal fun subtitleUrls(data: JSONObject): List<String> {
            val tracks = data.optJSONObject("subtitle")?.optJSONArray("subtitles") ?: return emptyList()
            return (0 until tracks.length()).mapNotNull { tracks.optJSONObject(it) }
                .sortedWith(compareBy<JSONObject> {
                    if (it.optString("lan").removePrefix("ai-").startsWith("zh")) 0 else 1
                }.thenBy { if (it.optString("lan").startsWith("ai-") || it.optInt("ai_type") > 0) 1 else 0 }
                    .thenBy { if (it.optString("lan").removePrefix("ai-") in setOf("zh", "zh-CN", "zh-Hans")) 0 else 1 })
                .mapNotNull { track ->
                    val raw = track.optString("subtitle_url")
                    val url = (if (raw.startsWith("//")) "https:$raw" else raw).toHttpUrlOrNull()
                    url?.takeIf { it.isHttps && listOf("hdslb.com", "bilibili.com", "bilivideo.com").any { host -> it.host == host || it.host.endsWith(".$host") } }?.toString()
                }.distinct()
        }
        internal fun parseSubtitles(data: JSONObject): List<LyricLine> {
            val body = data.optJSONArray("body") ?: return emptyList()
            val cues = (0 until body.length()).mapNotNull { index ->
                val cue = body.optJSONObject(index) ?: return@mapNotNull null
                val start = cue.optDouble("from", Double.NaN)
                val end = cue.optDouble("to", Double.NaN)
                val text = cue.optString("content").trim()
                if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start || end >= Long.MAX_VALUE / 1000.0 || text.isBlank()) null
                else Triple((start * 1000).toLong(), (end * 1000).toLong(), text)
            }.sortedBy { it.first }
            return buildList {
                cues.forEachIndexed { index, (start, end, text) ->
                    add(LyricLine(start, text))
                    if (index == cues.lastIndex || end < cues[index + 1].first) add(LyricLine(end, ""))
                }
            }
        }
        // These are web playback URLs. Some CDNs reject an Android browser UA with HTTP 403.
        val audioHeaders = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36", "Referer" to "https://www.bilibili.com/")
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
        internal fun parseAccount(data: JSONObject): BilibiliAccount {
            check(data.optBoolean("isLogin")) { "登录已失效，请重新填写 SESSDATA" }
            val active = data.optInt("vipStatus") == 1
            return BilibiliAccount(data.optString("uname").ifBlank { data.optLong("mid").toString() }, active,
                if (active) data.optJSONObject("vip_label")?.optString("text").orEmpty().trim() else "")
        }
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
        internal fun audioUrl(data: JSONObject): String = parseAudio(data).url
        internal fun parseAudio(data: JSONObject): BilibiliAudio = parseAudioStreams(data).first()
        internal fun parseAudioStreams(data: JSONObject): List<BilibiliAudio> {
            val audio = data.optJSONObject("dash")?.optJSONArray("audio") ?: error("该视频没有可用的独立音频流，可能需要登录")
            val tracks = (0 until audio.length()).mapNotNull { audio.optJSONObject(it) }.filter {
                it.optString("codecs").startsWith("mp4a") && it.optString("mimeType", it.optString("mime_type")).startsWith("audio/")
            }.sortedByDescending { it.optLong("bandwidth") }.mapNotNull { selected ->
                val url = selected.optString("baseUrl", selected.optString("base_url"))
                    .takeIf { Uri.parse(it).scheme == "https" && !Uri.parse(it).host.isNullOrBlank() } ?: return@mapNotNull null
                val backups = selected.optJSONArray("backupUrl") ?: selected.optJSONArray("backup_url") ?: JSONArray()
                val urls = (0 until backups.length()).map { backups.optString(it) }.filter {
                    val uri = Uri.parse(it); uri.scheme == "https" && !uri.host.isNullOrBlank() && it != url
                }.distinct()
                BilibiliAudio(url, selected.optLong("bandwidth").takeIf { it > 0 },
                    "aac-${selected.optLong("id").takeIf { it > 0 } ?: selected.optLong("bandwidth")}", selected.optLong("id").takeIf { it > 0 }, urls)
            }.distinctBy { it.quality }
            check(tracks.isNotEmpty()) { "没有可用的 AAC 音频流" }
            return tracks.mapIndexed { index, track -> if (index == 0) track.copy(quality = "original") else track }
        }
    }
}
