package cn.toside.music.nativeapp.online

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Explicit, bounded public-video diagnostic. Never records signed URLs, cookies or audio bytes. */
@RunWith(AndroidJUnit4::class)
class BilibiliCdnProbeTest {
    @Test fun probeConfiguredPublicVideo() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val bvid = arguments.getString("probeBvid")
        assumeTrue("Pass probeBvid and probeCid to run this public CDN diagnostic", bvid != null)
        val cid = requireNotNull(arguments.getString("probeCid")).toLong()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = JSONObject().put("bvid", bvid).put("cid", cid)
        val responses = JSONArray(); result.put("responses", responses)
        val output = File(context.getExternalFilesDir(null), "bili-cdn-probe.json")
        try {
            val streams = BilibiliApi().audioStreams(JSONObject().put("bvid", bvid).put("cid", cid))
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
                .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
            val variants = listOf("mobile-root", "mobile-range", "mobile-video", "desktop-root", "desktop-range")
            val stream = streams.first()
            for (url in (listOf(stream.url) + stream.backupUrls).distinct().take(3)) {
                for (variant in variants) {
                    val builder = Request.Builder().url(url)
                    BilibiliApi.audioHeaders.forEach { (name, value) -> builder.header(name, value) }
                    if (variant.startsWith("mobile-")) builder.header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36")
                    if (variant.endsWith("-range") || variant == "mobile-video") builder.header("Range", "bytes=0-1023")
                    if (variant == "mobile-video") builder.header("Referer", "https://www.bilibili.com/video/$bvid/")
                    val request = builder.build()
                    val item = JSONObject().put("host", request.url.host).put("variant", variant)
                        .put("queryKeys", JSONArray(request.url.queryParameterNames.toList()))
                    request.url.queryParameter("deadline")?.toLongOrNull()?.let { item.put("expiresInSeconds", it - System.currentTimeMillis() / 1000) }
                    try {
                        client.newCall(request).execute().use { response ->
                            item.put("status", response.code).put("server", response.header("Server"))
                            if (response.code >= 400) {
                                val buffer = ByteArray(256)
                                val size = response.body?.byteStream()?.read(buffer) ?: -1
                                if (size > 0) item.put("errorBody", String(buffer, 0, size, Charsets.UTF_8))
                            } else {
                                val first = response.body?.byteStream()?.read()
                                item.put("firstByteAvailable", first != null && first != -1)
                            }
                        }
                    } catch (error: Exception) { item.put("failure", error.javaClass.simpleName) }
                    responses.put(item); output.writeText(result.toString(2))
                }
            }
        } catch (error: Exception) { result.put("apiError", error.javaClass.simpleName + ": " + error.message?.take(160)); throw error }
        finally { output.writeText(result.toString(2)) }
    }
}
