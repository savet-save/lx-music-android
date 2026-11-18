package cn.toside.music.nativeapp.online

import android.os.SystemClock
import cn.toside.music.nativeapp.data.PlaylistCredentials
import cn.toside.music.nativeapp.utils.readBounded
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class BilibiliQrSession(val url: String, val key: String, val expiresAt: Long)
internal sealed interface BilibiliQrState {
    data object Waiting : BilibiliQrState
    data object Scanned : BilibiliQrState
    data object Expired : BilibiliQrState
    class Authorized(val token: String) : BilibiliQrState
}
internal interface BilibiliQrClient {
    suspend fun generate(): BilibiliQrSession
    suspend fun poll(key: String): BilibiliQrState
}

/** Anonymous passport requests only. Never follows the credential-bearing SSO URL. */
internal class BilibiliQrLogin : BilibiliQrClient {
    private class Reply(val body: JSONObject, val cookies: List<String>)
    private suspend fun request(path: String, key: String? = null): Reply = suspendCancellableCoroutine { continuation ->
        val url = "$ORIGIN/x/passport-login/web/qrcode/$path".toHttpUrl().newBuilder().apply {
            key?.let { addQueryParameter("qrcode_key", it) }
        }.build()
        val request = Request.Builder().url(url).apply {
            BilibiliApi.audioHeaders.forEach { (name, value) -> header(name, value) }
        }.build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("B站扫码请求失败"))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val reply = response.use {
                        check(it.isSuccessful) { "B站扫码请求失败（HTTP ${it.code}）" }
                        val body = requireNotNull(it.body)
                        require(body.contentLength() <= 64 * 1024)
                        Reply(JSONObject(body.byteStream().use { stream -> stream.readBounded(64 * 1024).toString(Charsets.UTF_8) }), it.headers("Set-Cookie"))
                    }
                    if (continuation.isActive) continuation.resume(reply)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("B站扫码响应无效"))
                }
            }
        })
    }

    override suspend fun generate(): BilibiliQrSession = parseSession(request("generate").body, SystemClock.elapsedRealtime())
    override suspend fun poll(key: String): BilibiliQrState {
        require(key.matches(Regex("[a-fA-F0-9]{32}")))
        val reply = request("poll", key)
        return parsePoll(reply.body, reply.cookies)
    }

    internal companion object {
        private const val ORIGIN = "https://passport.bilibili.com"
        private val client = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(13, TimeUnit.SECONDS).readTimeout(13, TimeUnit.SECONDS).callTimeout(18, TimeUnit.SECONDS).build()
        fun parseSession(body: JSONObject, now: Long): BilibiliQrSession {
            val data = BilibiliApi.checked(body)
            val key = data.getString("qrcode_key")
            require(key.matches(Regex("[a-fA-F0-9]{32}")))
            val url = data.getString("url").toHttpUrl()
            val loginPages = setOf("passport.bilibili.com" to "/h5-app/passport/login/scan",
                "account.bilibili.com" to "/h5/account-h5/auth/scan-web")
            require(url.scheme == "https" && (url.host to url.encodedPath) in loginPages && url.queryParameter("qrcode_key") == key)
            return BilibiliQrSession(url.toString(), key, now + 180_000)
        }
        fun parsePoll(body: JSONObject, headers: List<String>): BilibiliQrState {
            val data = BilibiliApi.checked(body)
            return when (data.getInt("code")) {
                86101 -> BilibiliQrState.Waiting
                86090 -> BilibiliQrState.Scanned
                86038 -> BilibiliQrState.Expired
                0 -> {
                    val origin = ORIGIN.toHttpUrl()
                    val cookie = headers.mapNotNull { Cookie.parse(origin, it) }
                        .firstOrNull { it.name == "SESSDATA" && it.matches(origin) && it.value.isNotEmpty() }?.value
                    val sso = data.optString("url").toHttpUrlOrNull()
                    val token = cookie ?: sso?.takeIf { it.scheme == "https" && it.host == "passport.biligame.com" && it.encodedPath == "/crossDomain" }
                        ?.queryParameter("SESSDATA") ?: error("B站未返回登录凭据")
                    PlaylistCredentials.validate(token)
                    BilibiliQrState.Authorized(token)
                }
                else -> error("B站返回未知扫码状态")
            }
        }
    }
}
