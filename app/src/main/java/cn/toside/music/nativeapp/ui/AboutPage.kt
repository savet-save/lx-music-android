package cn.toside.music.nativeapp.ui

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.AppPreferences
import cn.toside.music.nativeapp.update.NativeRelease
import cn.toside.music.nativeapp.update.NativeReleases
import cn.toside.music.nativeapp.update.NativeUpdates
import cn.toside.music.nativeapp.update.UpdateNotes
import cn.toside.music.nativeapp.utils.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Share a successful check with the page without opening a window or interrupting navigation. */
internal object NativeUpdateNotice {
    val release = MutableStateFlow<NativeRelease?>(null)
    fun restore(activity: Activity) {
        if (release.value != null) return
        release.value = runCatching {
            val saved = activity.getSharedPreferences("native-updates", Activity.MODE_PRIVATE).getString("release-notice", null) ?: return
            val json = JSONObject(saved)
            NativeRelease(json.getString("version"), json.getString("url"), json.getString("notes"),
                json.optString("apkUrl").takeIf { it.isNotBlank() }, json.optLong("size"), json.optString("digest").takeIf { it.isNotBlank() })
        }.getOrNull()
    }
    fun remember(activity: Activity, value: NativeRelease?) {
        release.value = value
        activity.getSharedPreferences("native-updates", Activity.MODE_PRIVATE).edit().apply {
            if (value == null) remove("release-notice")
            else putString("release-notice", JSONObject().put("version", value.version).put("url", value.url).put("notes", value.notes)
                .put("apkUrl", value.apkUrl.orEmpty()).put("size", value.size).put("digest", value.digest.orEmpty()).toString())
        }.apply()
    }
}

internal fun checkNativeUpdatesOnStartup(activity: Activity, scope: CoroutineScope, available: (NativeRelease) -> Unit = {}) {
    val settings = AppPreferences(activity)
    NativeUpdateNotice.restore(activity)
    if (!settings.bool("version.autoCheck", true)) return
    val now = System.currentTimeMillis()
    val previous = settings.string("version.lastCheck", "0").toLongOrNull() ?: 0
    if (now - previous in 0 until 24 * 60 * 60 * 1000L) return
    settings.set("version.lastCheck", now.toString())
    scope.launch {
        try {
            val release = NativeReleases.latest() ?: return@launch
            NativeUpdateNotice.remember(activity, release)
            @Suppress("DEPRECATION") val current = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
            if (!activity.isFinishing && NativeReleases.compare(release.version, current) > 0 && settings.string("version.ignored", "") != release.version) available(release)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { DiagnosticLog(activity).record("update", e.javaClass.simpleName, "INFO") }
    }
}

internal class AboutPage(
    private val activity: Activity,
    parentScope: CoroutineScope,
    private val content: LinearLayout,
    private val styles: LxStyles,
    private val palette: UiPalette,
    private val noticeChanged: () -> Unit = {},
) : AutoCloseable {
    private val work = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val preferences = AppPreferences(activity)
    private val updates = NativeUpdates(activity)
    @Suppress("DEPRECATION") private val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
    private var available: NativeRelease? = null
    private var busy = false
    private var transferNotice: String? = null
    private val status = label("").apply { tag = "update-status"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val notes = label("").apply { tag = "update-notes"; setTextIsSelectable(true) }
    private val releasePage = button(R.string.ui_bac19e2d086b) { open(available?.url ?: NativeReleases.PAGE) }.apply { tag = "update-release-page" }
    private val check = button(R.string.ui_4db966cc21a9) { check() }.apply { tag = "update-check" }
    private val download = button(R.string.update_download) { available?.let(::begin) }.apply { tag = "update-download"; isEnabled = false; visibility = View.GONE }
    private val ignore = button(R.string.update_ignore) {
        available?.let { preferences.set("version.ignored", it.version); ignoreVersion(); noticeChanged() }
    }.apply { tag = "update-ignore"; visibility = View.GONE }
    private val install = button(R.string.update_install) { install() }.apply { tag = "update-install"; isEnabled = false; visibility = View.GONE }
    private val cancel = button(R.string.update_cancel) { cancel() }.apply { tag = "update-cancel"; isEnabled = false; visibility = View.GONE }
    private val transferStatus = label("").apply { tag = "update-transfer"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }

    init {
        NativeUpdateNotice.restore(activity)
        content.addView(label(activity.getString(R.string.ui_22425f239f2d, info.versionName, info.longVersionCode.toString())).apply { tag = "about-version" })
        content.addView(CheckBox(activity).apply {
            text = activity.getString(R.string.update_auto_check); isChecked = preferences.bool("version.autoCheck", true)
            setOnCheckedChangeListener { _, value -> preferences.set("version.autoCheck", value) }
        })
        content.addView(check); content.addView(status); content.addView(notes); content.addView(releasePage)
        content.addView(download); content.addView(ignore); content.addView(transferStatus)
        content.addView(install); content.addView(cancel)
        link(R.string.ui_88e03b310fe2, "https://github.com/${NativeReleases.REPOSITORY}")
        link(R.string.ui_397b5581b4a9, "https://github.com/lyswhut/lx-music-mobile")
        link(R.string.ui_45a6d115fdfb, "https://lyswhut.github.io/lx-music-doc/mobile/faq")
        link(R.string.ui_31882138222f, "https://github.com/${NativeReleases.REPOSITORY}/issues")
        content.addView(button(R.string.pact_title) { showPact(activity, work) })
        val license = label("").apply { tag = "about-license"; visibility = View.GONE; setTextIsSelectable(true) }
        content.addView(button(R.string.ui_038ad816c53f) {
            if (license.visibility == View.VISIBLE) license.visibility = View.GONE
            else {
                license.text = "LX Music — Apache License 2.0\n\n" + activity.assets.open("licenses/Apache-2.0.txt").use { it.readBytes().toString(Charsets.UTF_8) } +
                    "\n\nJAudiotagger 3.0.1 — LGPL 2.1 or later\nhttps://www.jthink.net/jaudiotagger/\nSource: https://repo.maven.apache.org/maven2/net/jthink/jaudiotagger/3.0.1/jaudiotagger-3.0.1-sources.jar\n\n" +
                    activity.assets.open("licenses/LGPL-2.1.txt").use { it.readBytes().toString(Charsets.UTF_8) }
                license.visibility = View.VISIBLE
            }
        }); content.addView(license)
        work.launch { NativeUpdateNotice.release.collect { showRelease(it) } }
        work.launch { while (true) { refreshTransfer(); delay(500) } }
    }

    private fun showRelease(release: NativeRelease?) {
        available = release?.takeIf { NativeReleases.compare(it.version, info.versionName.orEmpty()) > 0 }
        status.text = when {
            release == null -> ""
            available == null -> activity.getString(R.string.ui_cda525a300a1, release.version)
            else -> activity.getString(R.string.ui_936707017ead, release.version)
        }
        notes.text = available?.let { UpdateNotes.summary(it.notes).ifBlank { activity.getString(R.string.ui_1c947644f8de) } }.orEmpty()
        notes.visibility = if (available != null) View.VISIBLE else View.GONE
        releasePage.setText(if (available != null) R.string.update_view_full_notes else R.string.ui_bac19e2d086b)
        download.visibility = if (available?.apkUrl != null) View.VISIBLE else View.GONE
        ignoreVersion()
    }
    private fun ignoreVersion() {
        ignore.visibility = if (available != null) View.VISIBLE else View.GONE
        ignore.isEnabled = available?.version != preferences.string("version.ignored", "")
    }
    private fun check() {
        check.isEnabled = false; status.text = activity.getString(R.string.ui_6b72c3d6855c)
        work.launch {
            try {
                val release = NativeReleases.latest()
                NativeUpdateNotice.remember(activity, release)
                showRelease(release)
                if (release == null) status.text = activity.getString(R.string.ui_ca985784214d)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = activity.getString(R.string.ui_506c6f6ae493, e.localizedMessage.orEmpty()) }
            finally { check.isEnabled = true }
        }
    }
    private fun begin(release: NativeRelease) = transfer {
        withContext(Dispatchers.IO) { updates.start(release) }
    }
    private fun cancel() = transfer {
        withContext(Dispatchers.IO) { updates.cancel() }
        transferNotice = activity.getString(R.string.update_cancelled)
    }
    private fun install() = transfer {
        val current = updates.saved() ?: error(activity.getString(R.string.update_expired))
        transferStatus.text = activity.getString(R.string.update_verifying)
        if (!updates.install(activity, current)) transferNotice = activity.getString(R.string.update_install_permission)
    }
    private fun transfer(block: suspend () -> Unit) {
        if (busy) return
        busy = true; transferNotice = null
        download.isEnabled = false; install.isEnabled = false; cancel.isEnabled = false
        work.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { transferNotice = activity.getString(R.string.update_failed, e.localizedMessage.orEmpty()) }
            finally { busy = false; refreshTransfer() }
        }
    }
    private suspend fun refreshTransfer() {
        if (busy) return
        try {
            val value = withContext(Dispatchers.IO) { updates.progress() }
            if (busy) return
            val active = value?.status in setOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED)
            download.isEnabled = available?.apkUrl != null && !active
            install.isEnabled = value?.status == DownloadManager.STATUS_SUCCESSFUL
            cancel.isEnabled = value != null || updates.saved() != null
            install.visibility = if (install.isEnabled) View.VISIBLE else View.GONE
            cancel.visibility = if (cancel.isEnabled) View.VISIBLE else View.GONE
            transferStatus.text = transferNotice ?: when (value?.status) {
                DownloadManager.STATUS_SUCCESSFUL -> activity.getString(R.string.update_ready)
                DownloadManager.STATUS_FAILED -> activity.getString(R.string.update_download_error, value.reason)
                DownloadManager.STATUS_RUNNING -> activity.getString(R.string.update_progress, Formatter.formatShortFileSize(activity, value.current.coerceAtLeast(0)), Formatter.formatShortFileSize(activity, value.total.coerceAtLeast(0)))
                null -> ""
                else -> activity.getString(R.string.update_waiting)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { transferStatus.text = activity.getString(R.string.update_failed, e.localizedMessage.orEmpty()) }
    }
    private fun open(url: String) { runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    private fun link(title: Int, url: String) { content.addView(button(title) { open(url) }) }
    private fun label(value: String) = styles.label(value, 14f, palette.foreground).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        setSingleLine(false); setHorizontallyScrolling(false)
        maxLines = Int.MAX_VALUE; ellipsize = null
        gravity = Gravity.TOP or Gravity.START
        setPadding(0, styles.dp(8), 0, styles.dp(8))
    }
    private fun button(title: Int, action: () -> Unit) = Button(activity).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        text = activity.getString(title); isAllCaps = false; setTextColor(palette.primary); background = styles.touch()
        minHeight = styles.dp(48); setOnClickListener { action() }
    }
    override fun close() { work.cancel() }
}

internal fun diagnosticsContent(activity: Activity, scope: CoroutineScope, content: LinearLayout, export: () -> Unit) {
    val preferences = AppPreferences(activity); val log = DiagnosticLog(activity)
    listOf(R.string.ui_c7520af426a5 to DiagnosticLog.SYNC, R.string.ui_8a57b342c746 to DiagnosticLog.SOURCE).forEach { (label, key) ->
        content.addView(CheckBox(activity).apply {
            text = activity.getString(label); isChecked = preferences.bool(key)
            setOnCheckedChangeListener { _, value -> preferences.set(key, value) }
        })
    }
    val text = TextView(activity).apply { textSize = 12f; setTextIsSelectable(true); minLines = 4; tag = "diagnostics-content" }
    fun refresh() { scope.launch { text.text = log.read().ifBlank { activity.getString(R.string.ui_10fafbeaa7fd) } } }
    content.addView(Button(activity).apply { setText(R.string.ui_76420433f22d); setOnClickListener { export() } })
    content.addView(Button(activity).apply { setText(R.string.ui_1ef3de06b32e); setOnClickListener { scope.launch { log.clear(); refresh() } } })
    content.addView(text); refresh()
}
