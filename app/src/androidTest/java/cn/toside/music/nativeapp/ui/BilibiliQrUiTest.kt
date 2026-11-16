package cn.toside.music.nativeapp.ui

import android.content.ContentUris
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.online.*
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BilibiliQrUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitFor(label: String, condition: () -> Boolean) {
        repeat(120) {
            instrumentation.waitForIdleSync(); var done = false; main { done = condition() }
            if (done) return
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for $label")
    }
    private fun view(activity: BilibiliLoginActivity, tag: String) = activity.window.decorView.findViewWithTag<View>(tag)
    private fun text(activity: BilibiliLoginActivity, tag: String) = (view(activity, tag) as TextView).text.toString()
    private fun field(activity: BilibiliLoginActivity, name: String, value: Any) = BilibiliLoginActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    private fun session(key: String = "0123456789abcdef0123456789abcdef") = BilibiliQrSession(
        "https://passport.bilibili.com/h5-app/passport/login/scan?qrcode_key=$key", key, SystemClock.elapsedRealtime() + 180000)
    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("biliLoginScreenshots") != "true") return
        // OEM Activity transitions run in the compositor after the app queue is idle.
        SystemClock.sleep(1000)
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val path = File(context.getExternalFilesDir(null), "bili-login-validation").apply { mkdirs() }
        try { File(path, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    private fun withPage(block: (BilibiliLoginActivity) -> Unit) {
        val credentials = BilibiliCredentials(context); val previous = credentials.load(); credentials.clear()
        var activity: BilibiliLoginActivity? = null
        try {
            activity = ForegroundTestActivities.start(Intent(context, BilibiliLoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BilibiliLoginActivity
            block(activity)
        } finally {
            activity?.let { main { it.finish() }; instrumentation.waitForIdleSync() }
            if (previous.isNotEmpty()) credentials.save(previous) else credentials.clear()
        }
    }

    @Test fun confirmedQrEncryptsCredentialsWithoutFillingThePasswordField() = withPage { activity ->
        var polls = 0
        field(activity, "qrClient", object : BilibiliQrClient {
            override suspend fun generate() = session()
            override suspend fun poll(key: String) = if (polls++ == 0) BilibiliQrState.Scanned else BilibiliQrState.Authorized("qr-fixture-not-an-account")
        })
        val verify: suspend (String) -> BilibiliAccount = { assertEquals("qr-fixture-not-an-account", it); BilibiliAccount("Fixture", true, "年度大会员") }
        field(activity, "verifySession", verify)
        main { view(activity, "bili-qr-refresh").performClick() }
        waitFor("scan confirmation") { text(activity, "bili-qr-status") == activity.getString(R.string.bili_qr_scanned) }
        waitFor("encrypted login") { text(activity, "bili-login-status").contains("Fixture") }
        assertEquals(activity.getString(R.string.bili_logged_in_vip, "Fixture", "年度大会员"), text(activity, "bili-login-status"))
        assertEquals("qr-fixture-not-an-account", BilibiliCredentials(context).load())
        assertEquals("", (view(activity, "bili-token") as EditText).text.toString())
        val encrypted = context.getSharedPreferences("bilibili-credentials", 0).all.values.joinToString()
        assertFalse(encrypted.contains("qr-fixture-not-an-account"))
        assertEquals(View.GONE, view(activity, "bili-qr-image").visibility)
    }

    @Test fun refreshingAndClearingIgnoreLateConfirmationFromOlderQrRequests() = runBlocking {
        val first = CompletableDeferred<Unit>(); val second = CompletableDeferred<Unit>()
        val enteredFirst = CompletableDeferred<Unit>(); val enteredSecond = CompletableDeferred<Unit>()
        try { withPage { activity ->
            var generated = 0
            val firstKey = "0123456789abcdef0123456789abcdef"
            field(activity, "qrClient", object : BilibiliQrClient {
                override suspend fun generate() = session(if (generated++ == 0) firstKey else "abcdef0123456789abcdef0123456789")
                override suspend fun poll(key: String): BilibiliQrState = withContext(NonCancellable) {
                    if (key == firstKey) { enteredFirst.complete(Unit); first.await() }
                    else { enteredSecond.complete(Unit); second.await() }
                    BilibiliQrState.Authorized("late-fixture-not-an-account")
                }
            })
            val verify: suspend (String) -> BilibiliAccount = { BilibiliAccount("Late fixture") }; field(activity, "verifySession", verify)
            main { view(activity, "bili-qr-refresh").performClick() }
            runBlocking { withTimeout(10000) { enteredFirst.await() } }
            main { view(activity, "bili-qr-refresh").performClick() }
            runBlocking { withTimeout(10000) { enteredSecond.await() } }
            first.complete(Unit)
            main { view(activity, "bili-token-clear").performClick() }; second.complete(Unit)
            waitFor("cleared credentials") { view(activity, "bili-token-save").isEnabled && BilibiliCredentials(context).load().isEmpty() }
            assertEquals(View.GONE, view(activity, "bili-qr-image").visibility)
            assertFalse(view(activity, "bili-qr-save").isEnabled)
        } } finally { first.complete(Unit); second.complete(Unit) }
    }

    @Test fun expiredQrCanBeRefreshedAndTheSavedGalleryImageIsScannable() = withPage { activity ->
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        var generated = 0
        val challenge = session()
        field(activity, "qrClient", object : BilibiliQrClient {
            override suspend fun generate() = session().also { generated++ }
            override suspend fun poll(key: String) = if (generated == 1) BilibiliQrState.Expired else BilibiliQrState.Waiting
        })
        main { view(activity, "bili-qr-refresh").performClick() }
        waitFor("expired QR") { text(activity, "bili-qr-status") == activity.getString(R.string.bili_qr_expired) }
        assertFalse(view(activity, "bili-qr-save").isEnabled)
        main { view(activity, "bili-qr-refresh").performClick() }
        waitFor("new QR") { view(activity, "bili-qr-save").isEnabled }
        val before = System.currentTimeMillis()
        var exported: android.net.Uri? = null
        try {
            screenshot("qr-login-screen")
            main { view(activity, "bili-qr-save").performClick() }
            waitFor("gallery PNG") {
                context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf("_id", "_display_name"),
                    "_display_name LIKE ? AND is_pending = 0", arrayOf("Bilibili-login-%"), "_id DESC")?.use { rows ->
                    while (rows.moveToNext()) if (rows.getString(1).substringAfter("Bilibili-login-").substringBefore('.').toLongOrNull()?.let { it >= before } == true) {
                        exported = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, rows.getLong(0)); break
                    }
                }
                exported != null
            }
            val bitmap = context.contentResolver.openInputStream(exported!!)!!.use { BitmapFactory.decodeStream(it) }
            try {
                val pixels = IntArray(bitmap.width * bitmap.height); bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))))
                assertEquals(challenge.url, result.text)
            } finally { bitmap.recycle() }
        } finally { exported?.let { context.contentResolver.delete(it, null, null) } }
    }

    @Test fun themeLargeFontAndPhoneTabletWidthsKeepTheLoginPageReadable() {
        val preferences = context.getSharedPreferences("app-settings", 0)
        val keys = listOf("theme.id", "common.isAutoTheme", "common.fontScale", "common.langId")
        val previous = keys.associateWith { preferences.all[it] }
        preferences.edit().putString("theme.id", "black").putBoolean("common.isAutoTheme", false).putString("common.fontScale", "1.3").putString("common.langId", "en").commit()
        try { withPage { activity ->
            main {
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val originalWidth = root.width; val originalHeight = root.height
                assertEquals(Color.TRANSPARENT, activity.window.statusBarColor)
                val paletteField = BilibiliLoginActivity::class.java.getDeclaredField("palette").apply { isAccessible = true }
                assertTrue((paletteField.get(activity) as UiPalette).theme.dark)
                assertEquals("Generate login QR code", text(activity, "bili-qr-refresh"))
                view(activity, "bili-manual-toggle").performClick()
                for ((widthDp, heightDp) in listOf(360 to 640, 600 to 800, 1024 to 700)) {
                    val density = activity.resources.displayMetrics.density; val width = (widthDp * density).toInt(); val height = (heightDp * density).toInt()
                    repeat(2) {
                        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        root.layout(0, 0, width, height)
                    }
                    val input = view(activity, "bili-token")
                    assertTrue(input.width > 0 && input.width <= width)
                    assertTrue(input.width <= (640 * density).toInt())
                    assertTrue("QR action keeps a 48 dp touch target", view(activity, "bili-qr-refresh").height >= (48 * density).toInt())
                }
                repeat(2) {
                    root.measure(View.MeasureSpec.makeMeasureSpec(originalWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(originalHeight, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, originalWidth, originalHeight)
                }
            }
            instrumentation.waitForIdleSync()
            screenshot("dark-login-screen")
        } } finally {
            preferences.edit().apply { previous.forEach { (key, value) -> when (value) { is String -> putString(key, value); is Boolean -> putBoolean(key, value); else -> remove(key) } } }.commit()
        }
    }
}
