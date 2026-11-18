package cn.toside.music.nativeapp.online

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.ui.LoginQrCode
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class BilibiliQrTest {
    private val key = "0123456789abcdef0123456789abcdef"
    private val url = "https://passport.bilibili.com/h5-app/passport/login/scan?navhide=1&qrcode_key=$key"
    private fun reply(code: Int, url: String = "") = JSONObject().put("code", 0).put("data", JSONObject().put("code", code).put("url", url))
    private fun fails(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }

    @Test fun generatedChallengeRequiresThePassportHostAndMatchingKeyAndExpiresInThreeMinutes() {
        val data = JSONObject().put("url", url).put("qrcode_key", key)
        val body = JSONObject().put("code", 0).put("data", data)
        val session = BilibiliQrLogin.parseSession(body, 500)
        assertEquals(180500, session.expiresAt); assertEquals(key, session.key); assertEquals(url, session.url)
        val currentUrl = url.replace("passport.bilibili.com/h5-app/passport/login/scan", "account.bilibili.com/h5/account-h5/auth/scan-web")
        data.put("url", currentUrl); assertEquals(currentUrl, BilibiliQrLogin.parseSession(body, 0).url)
        data.put("url", url.replace("passport.bilibili.com", "example.com")); fails { BilibiliQrLogin.parseSession(body, 0) }
        data.put("url", url.replace(key, "abcdef0123456789abcdef0123456789")); fails { BilibiliQrLogin.parseSession(body, 0) }
    }

    @Test fun pollingDistinguishesWaitingScannedExpiredAndConfirmedAndRejectsUnknownStates() {
        assertSame(BilibiliQrState.Waiting, BilibiliQrLogin.parsePoll(reply(86101), emptyList()))
        assertSame(BilibiliQrState.Scanned, BilibiliQrLogin.parsePoll(reply(86090), emptyList()))
        assertSame(BilibiliQrState.Expired, BilibiliQrLogin.parsePoll(reply(86038), emptyList()))
        fails { BilibiliQrLogin.parsePoll(reply(123), emptyList()) }
        fails { BilibiliQrLogin.parsePoll(reply(0), emptyList()) }
    }

    @Test fun confirmedLoginPrefersThePassportCookieAndNeverAcceptsUntrustedSsoUrls() {
        val confirmed = reply(0, "https://passport.biligame.com/crossDomain?SESSDATA=url-value")
        val result = BilibiliQrLogin.parsePoll(confirmed, listOf("SESSDATA=cookie%2Cfixture; Path=/; Domain=.bilibili.com; Secure; HttpOnly")) as BilibiliQrState.Authorized
        assertEquals("cookie%2Cfixture", result.token)
        assertEquals("url-value", (BilibiliQrLogin.parsePoll(confirmed, emptyList()) as BilibiliQrState.Authorized).token)
        fails { BilibiliQrLogin.parsePoll(reply(0, "https://example.com/crossDomain?SESSDATA=bad"), listOf("SESSDATA=bad; Domain=example.com")) }
        fails { BilibiliQrLogin.parsePoll(reply(0, "http://passport.biligame.com/crossDomain?SESSDATA=bad"), emptyList()) }
    }

    @Test fun savedPngDecodesToTheExactLoginUrlWithAWhiteQuietZone() {
        val bitmap = LoginQrCode.bitmap(url)
        try {
            val bytes = ByteArrayOutputStream().use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
            val saved = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try {
                val pixels = IntArray(saved.width * saved.height); saved.getPixels(pixels, 0, saved.width, 0, 0, saved.width, saved.height)
                val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(saved.width, saved.height, pixels))))
                assertEquals(url, decoded.text)
                assertEquals(android.graphics.Color.WHITE, saved.getPixel(0, 0))
            } finally { saved.recycle() }
        } finally { bitmap.recycle() }
    }

    @Test fun liveAnonymousChallengeCanBeGeneratedAndPolledWithoutCredentials() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveBilibiliQr") == "true")
        val api = BilibiliQrLogin(); val session = api.generate()
        assertTrue(session.expiresAt > SystemClock.elapsedRealtime())
        assertTrue(android.net.Uri.parse(session.url).host in setOf("passport.bilibili.com", "account.bilibili.com"))
        assertSame(BilibiliQrState.Waiting, api.poll(session.key))
    }
}
