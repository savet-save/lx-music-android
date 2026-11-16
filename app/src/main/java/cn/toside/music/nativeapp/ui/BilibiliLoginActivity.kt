package cn.toside.music.nativeapp.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import androidx.fragment.app.FragmentActivity
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.online.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

class BilibiliLoginActivity : FragmentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val credentialsMutex = Mutex()
    private var operation: Job? = null
    private var qrOperation: Job? = null
    private var qrSession: BilibiliQrSession? = null
    private var qrBitmap: Bitmap? = null
    private var exportDraft: File? = null
    private var verifySession: suspend (String) -> String = { BilibiliApi(applicationContext, it).account() }
    private var qrClient: BilibiliQrClient = BilibiliQrLogin()
    private lateinit var palette: UiPalette
    private lateinit var styles: LxStyles
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var save: TextView
    private lateinit var qrStatus: TextView
    private lateinit var qrImage: ImageView
    private lateinit var qrPlaceholder: TextView
    private lateinit var refreshQr: TextView
    private lateinit var saveQr: TextView
    private lateinit var manual: LinearLayout

    override fun attachBaseContext(base: Context) = super.attachBaseContext(UiPreferences.context(base))
    override fun onCreate(savedInstanceState: Bundle?) {
        val theme = NativeThemes(this).active()
        setTheme(if (theme.dark) R.style.NativeDark else R.style.NativeLight)
        super.onCreate(savedInstanceState)
        palette = UiPalette(this, theme)
        styles = LxStyles(this, palette, Typeface.createFromAsset(assets, "fonts/icomoon.ttf"))
        exportDraft = savedInstanceState?.getString("qr-export")?.takeIf { it.matches(Regex("bili-login-qr-[a-f0-9-]{36}\\.png")) }?.let { File(cacheDir, it) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "bili-login-page"; setBackgroundColor(palette.surface) }
        val header = LinearLayout(this).apply { tag = "bili-login-header"; gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false; setPadding(dp(4), dp(4), dp(12), dp(4)) }
        header.addView(styles.backButton { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label(R.string.bili_login, 19f).apply { includeFontPadding = false; gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        val panel = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(
                View.MeasureSpec.makeMeasureSpec(minOf(View.MeasureSpec.getSize(widthMeasureSpec), dp(640)), View.MeasureSpec.EXACTLY), heightMeasureSpec)
        }.apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(24)) }
        panel.addView(label(R.string.bili_account_settings, 13f, palette.muted))
        status = label(R.string.bili_logged_out, 14f, palette.primary).apply { tag = "bili-login-status"; setPadding(0, dp(16), 0, dp(8)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        panel.addView(status)
        panel.addView(label(R.string.bili_qr_title, 18f).apply { isAccessibilityHeading = true; setPadding(0, dp(12), 0, dp(8)) })
        panel.addView(label(R.string.bili_qr_hint, 13f, palette.muted))
        val qrArea = FrameLayout(this).apply { background = styles.shape(Color.WHITE, border = palette.divider); setPadding(dp(8), dp(8), dp(8), dp(8)) }
        qrImage = ImageView(this).apply { tag = "bili-qr-image"; scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = getString(R.string.bili_qr_title); visibility = View.GONE }
        qrPlaceholder = label(R.string.bili_qr_idle, 14f, Color.DKGRAY).apply { gravity = Gravity.CENTER }
        qrArea.addView(qrImage, FrameLayout.LayoutParams(-1, -1)); qrArea.addView(qrPlaceholder, FrameLayout.LayoutParams(-1, -1))
        panel.addView(qrArea, LinearLayout.LayoutParams(-1, dp(240)).apply { topMargin = dp(16) })
        qrStatus = label(R.string.bili_qr_idle, 13f, palette.muted).apply { tag = "bili-qr-status"; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        panel.addView(qrStatus)
        refreshQr = button(R.string.bili_qr_generate, true) { startQrLogin() }.apply { tag = "bili-qr-refresh" }
        panel.addView(refreshQr)
        saveQr = button(R.string.bili_qr_save) { saveQrImage() }.apply { tag = "bili-qr-save"; isEnabled = false; alpha = .45f }
        panel.addView(saveQr)
        panel.addView(button(R.string.bili_manual) {
            manual.visibility = if (manual.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (manual.visibility == View.GONE) input.text.clear()
        }.apply { tag = "bili-manual-toggle" })
        manual = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = if (savedInstanceState?.getBoolean("manual") == true) View.VISIBLE else View.GONE }
        manual.addView(label(R.string.bili_login_hint, 13f, palette.muted))
        input = EditText(this).apply {
            tag = "bili-token"; hint = "SESSDATA"; contentDescription = hint; textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true); isSaveEnabled = false; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            filters = arrayOf(android.text.InputFilter.LengthFilter(4105))
            setTextColor(palette.foreground); setHintTextColor(palette.muted); minHeight = dp(48)
            setPadding(dp(12), dp(12), dp(12), dp(12)); background = styles.shape(styles.panel, border = palette.divider)
        }
        manual.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        save = button(R.string.bili_login_save, true) { saveManualToken() }.apply { tag = "bili-token-save" }
        manual.addView(save, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }); panel.addView(manual)
        panel.addView(button(R.string.bili_logout) { clearCredentials() }.apply { tag = "bili-token-clear" })
        val wrapper = FrameLayout(this).apply { addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)) }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(wrapper) }, LinearLayout.LayoutParams(-1, 0, 1f))
        palette.background(root); palette.textStyle(root)
        root.background = android.graphics.drawable.LayerDrawable(arrayOf(android.graphics.drawable.ColorDrawable(palette.surface), requireNotNull(root.background)))
        setContentView(root); ScreenInsets.install(window, root); ScreenInsets.styleSystemBars(window, theme.dark)
        operation = scope.launch {
            if (withContext(Dispatchers.IO) { BilibiliCredentials(applicationContext).load().isNotBlank() }) {
                try { status.text = getString(R.string.bili_logged_in, BilibiliApi(applicationContext).account()) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { status.text = e.localizedMessage }
            }
        }
    }

    private fun dp(value: Int) = styles.dp(value)
    private fun label(id: Int, size: Float, color: Int = palette.foreground) = TextView(this).apply {
        text = getString(id); textSize = size; setTextColor(color); setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(id: Int, primary: Boolean = false, click: () -> Unit) = TextView(this).apply {
        text = getString(id); textSize = 14f; gravity = Gravity.CENTER; minHeight = dp(48)
        setPadding(dp(16), dp(12), dp(16), dp(12)); setTextColor(if (primary) palette.surface else palette.primary)
        background = styles.touch(if (primary) palette.primary else Color.TRANSPARENT); isFocusable = true
        setOnClickListener { click() }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) { super.onInitializeAccessibilityNodeInfo(host, info); info.className = "android.widget.Button" }
        }
    }

    private fun resetQr(message: Int = R.string.bili_qr_idle) {
        qrSession = null; qrBitmap = null; qrImage.setImageDrawable(null); qrImage.visibility = View.GONE
        qrPlaceholder.visibility = View.VISIBLE; qrPlaceholder.text = getString(message); qrStatus.text = getString(message); saveQr.isEnabled = false; saveQr.alpha = .45f
    }
    private fun startQrLogin() {
        operation?.cancel(); qrOperation?.cancel(); save.isEnabled = true
        resetQr(R.string.bili_qr_loading); refreshQr.text = getString(R.string.bili_qr_refresh)
        input.text.clear()
        qrOperation = scope.launch {
            try {
                val session = qrClient.generate()
                val bitmap = withContext(Dispatchers.Default) { LoginQrCode.bitmap(session.url) }
                ensureActive(); qrSession = session; qrBitmap = bitmap; qrImage.setImageBitmap(bitmap)
                qrImage.visibility = View.VISIBLE; qrPlaceholder.visibility = View.GONE; saveQr.isEnabled = true; saveQr.alpha = 1f
                pollQr(session)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { resetQr(R.string.bili_qr_failed) }
        }
    }
    private suspend fun pollQr(session: BilibiliQrSession) {
        while (currentCoroutineContext().isActive && qrSession === session) {
            if (SystemClock.elapsedRealtime() >= session.expiresAt) { resetQr(R.string.bili_qr_expired); return }
            val remaining = ((session.expiresAt - SystemClock.elapsedRealtime()) / 1000).toInt().coerceAtLeast(0)
            val result = qrClient.poll(session.key)
            currentCoroutineContext().ensureActive()
            if (qrSession !== session) return
            when (result) {
                BilibiliQrState.Waiting -> qrStatus.text = getString(R.string.bili_qr_waiting, remaining)
                BilibiliQrState.Scanned -> qrStatus.text = getString(R.string.bili_qr_scanned)
                BilibiliQrState.Expired -> { resetQr(R.string.bili_qr_expired); return }
                is BilibiliQrState.Authorized -> {
                    val name = verifySession(result.token); storeToken(result.token)
                    input.text.clear(); resetQr(R.string.bili_qr_success); status.text = getString(R.string.bili_logged_in, name)
                    return
                }
            }
            delay(2000)
        }
    }
    private suspend fun storeToken(token: String) = withContext(Dispatchers.IO) {
        credentialsMutex.withLock { currentCoroutineContext().ensureActive(); BilibiliCredentials(applicationContext).save(token) }
    }
    private fun saveManualToken() {
        val token = input.text.toString().trim().removePrefix("SESSDATA=")
        operation?.cancel(); qrOperation?.cancel(); resetQr()
        save.isEnabled = false; status.text = getString(R.string.bili_checking)
        operation = scope.launch {
            try {
                val name = verifySession(token); storeToken(token)
                input.text.clear(); status.text = getString(R.string.bili_logged_in, name)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = e.localizedMessage }
            finally { if (currentCoroutineContext().isActive) save.isEnabled = true }
        }
    }
    private fun clearCredentials() {
        operation?.cancel(); qrOperation?.cancel(); resetQr(); input.text.clear(); save.isEnabled = false
        operation = scope.launch {
            try {
                withContext(Dispatchers.IO) { credentialsMutex.withLock { BilibiliCredentials(applicationContext).clear() } }
                status.text = getString(R.string.bili_logged_out)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = e.localizedMessage }
            finally { if (currentCoroutineContext().isActive) save.isEnabled = true }
        }
    }

    private fun saveQrImage() {
        val bitmap = qrBitmap?.takeIf { qrSession?.expiresAt?.let { SystemClock.elapsedRealtime() < it } == true }?.copy(Bitmap.Config.ARGB_8888, false) ?: return
        scope.launch {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    withContext(Dispatchers.IO) {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "Bilibili-login-${System.currentTimeMillis()}.png")
                            put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LX Music")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        val uri = requireNotNull(contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                        try {
                            requireNotNull(contentResolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                            ensureActive(); contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                        } catch (e: Exception) { contentResolver.delete(uri, null, null); throw e }
                    }
                    Toast.makeText(this@BilibiliLoginActivity, R.string.bili_qr_saved, Toast.LENGTH_LONG).show()
                } else {
                    exportDraft?.delete()
                    val draft = File(cacheDir, "bili-login-qr-${UUID.randomUUID()}.png"); exportDraft = draft
                    withContext(Dispatchers.IO) { draft.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("image/png").putExtra(Intent.EXTRA_TITLE, "Bilibili-login.png"), 1)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(this@BilibiliLoginActivity, R.string.bili_qr_save_failed, Toast.LENGTH_LONG).show() }
            finally { bitmap.recycle() }
        }
    }
    @Deprecated("Legacy document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1) return
        val draft = exportDraft ?: return
        val uri = data?.data
        exportDraft = null
        if (resultCode != RESULT_OK || uri == null) { draft.delete(); return }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { requireNotNull(contentResolver.openOutputStream(uri, "w")).use { out -> draft.inputStream().use { it.copyTo(out) } } }
                Toast.makeText(this@BilibiliLoginActivity, R.string.bili_qr_saved, Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(this@BilibiliLoginActivity, R.string.bili_qr_save_failed, Toast.LENGTH_LONG).show() }
            finally { draft.delete() }
        }
    }
    override fun onStart() {
        super.onStart()
        qrSession?.let { session ->
            qrOperation = scope.launch {
                try { pollQr(session) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { resetQr(R.string.bili_qr_failed) }
            }
        }
    }
    override fun onStop() { qrOperation?.cancel(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("manual", manual.visibility == View.VISIBLE)
        exportDraft?.let { outState.putString("qr-export", it.name) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { scope.cancel(); if (isFinishing) exportDraft?.delete(); super.onDestroy() }
}
