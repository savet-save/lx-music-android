package cn.toside.music.nativeapp.ui

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils.blendARGB
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.data.DownloadProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.Locale

internal fun downloadQualityLabel(code: String): String = when (code) {
    "flac24bit" -> "24-bit FLAC"
    "flac" -> "FLAC"
    else -> code.uppercase(Locale.ROOT)
}

internal fun showDownloadQuality(activity: Activity, song: Song, palette: UiPalette, styles: LxStyles,
    parentScope: CoroutineScope, probe: suspend (String, (Int) -> Unit) -> Unit,
    dismissed: (Dialog) -> Unit = {},
    selected: (String) -> Unit): Dialog {
    val qualities = runCatching { JSONObject(song.sourceInfo.orEmpty()) }.getOrNull()
    val byType = qualities?.optJSONObject("_types")
    val entries = qualities?.optJSONArray("types")
    val listed = (0 until (entries?.length() ?: 0)).mapNotNull { entries?.optJSONObject(it) }.associateBy { it.optString("type") }
    val available = listOf("128k", "320k", "flac", "flac24bit").filter { byType?.has(it) == true || it in listed }.ifEmpty { listOf("128k") }
    val dialog = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE); setTitle(activity.getString(R.string.download_choose_quality)) }
    val work = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    val permits = Semaphore(2)
    dialog.setOnDismissListener { work.cancel(); dismissed(dialog) }
    val panel = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = styles.shape(palette.surface, radius = 6, border = palette.divider)
        clipToOutline = true
    }
    val header = FrameLayout(activity).apply { tag = "download-quality-accent"; setBackgroundColor(blendARGB(palette.surface, palette.primary, .70f)) }
    header.addView(Button(activity).apply {
        tag = "download-quality-close"; text = "×"; textSize = 20f
        contentDescription = activity.getString(android.R.string.cancel)
        background = styles.touch(); backgroundTintList = null; stateListAnimator = null
        setTextColor(palette.foreground); setPadding(0, 0, 0, 0)
        setOnClickListener { dialog.dismiss() }
    }, FrameLayout.LayoutParams(styles.dp(48), MATCH_PARENT, Gravity.END))
    panel.addView(header, MATCH_PARENT, styles.dp(40))
    val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(styles.dp(16), styles.dp(14), styles.dp(16), styles.dp(16)) }
    body.addView(TextView(activity).apply {
        text = song.title; textSize = 16f; gravity = Gravity.CENTER
        setTextColor(palette.foreground); maxLines = 3; isAccessibilityHeading = true
    }, MATCH_PARENT, WRAP_CONTENT)
    if (song.artist.isNotBlank()) body.addView(TextView(activity).apply {
        text = song.artist; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(palette.muted); maxLines = 2
    }, MATCH_PARENT, WRAP_CONTENT)
    available.forEach { code ->
        val meta = byType?.optJSONObject(code) ?: listed[code]
        val size = meta?.optLong("bytes")?.takeIf { it > 0 }?.let {
            activity.getString(R.string.download_size_mib, it / (1024.0 * 1024))
        } ?: meta?.optString("size")?.trim()?.takeIf { it.matches(Regex("[0-9]+(?:\\.[0-9]+)?\\s*(?:[KMGT]i?B)", RegexOption.IGNORE_CASE)) }
        val title = activity.getString(when (code) {
            "320k" -> R.string.download_quality_high; "flac" -> R.string.download_quality_lossless
            "flac24bit" -> R.string.download_quality_hires; else -> R.string.download_quality_standard
        }, downloadQualityLabel(code))
        val row = FrameLayout(activity)
        val button = Button(activity).apply {
            tag = "download-quality-$code"; text = if (size == null) title else activity.getString(R.string.download_quality_size, title, size)
            isAllCaps = false; minHeight = styles.dp(48); setPaddingRelative(styles.dp(8), styles.dp(8), styles.dp(80), styles.dp(8))
            setTextColor(blendARGB(palette.foreground, palette.primary, .45f))
            background = styles.touch(styles.selected); backgroundTintList = null; stateListAnimator = null
            contentDescription = "$text · ${activity.getString(R.string.download_probe_checking)}"
            setOnClickListener { dialog.dismiss(); selected(code) }
        }
        row.addView(button, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            tag = "download-quality-progress-$code"; max = DownloadProbe.MAX_BYTES; isIndeterminate = true
            progressTintList = ColorStateList.valueOf(palette.primary)
            indeterminateTintList = ColorStateList.valueOf(palette.primary)
            progressBackgroundTintList = ColorStateList.valueOf(palette.divider)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val result = TextView(activity).apply {
            tag = "download-quality-result-$code"; textSize = 21f; gravity = Gravity.CENTER
            setTextColor(palette.primary); visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(FrameLayout(activity).apply {
            addView(progress, FrameLayout.LayoutParams(styles.dp(56), styles.dp(4), Gravity.CENTER))
            addView(result, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }, FrameLayout.LayoutParams(styles.dp(72), MATCH_PARENT, Gravity.END))
        body.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = styles.dp(8) })
        work.launch {
            val usable = try {
                permits.withPermit {
                    withTimeout(20_000) {
                        probe(code) { bytes -> work.launch { progress.isIndeterminate = false; progress.progress = bytes } }
                    }
                }
                true
            } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); false }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
            progress.visibility = View.GONE
            result.text = if (usable) "√" else "×"; result.visibility = View.VISIBLE
            button.contentDescription = "${button.text} · ${activity.getString(if (usable) R.string.download_probe_available else R.string.download_probe_unavailable)}"
        }
    }
    panel.addView(ScrollView(activity).apply { addView(body) }, MATCH_PARENT, WRAP_CONTENT)
    dialog.setContentView(panel)
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialog.show()
    val width = (activity.resources.configuration.screenWidthDp - 32).coerceIn(1, 360)
    dialog.window?.setLayout(styles.dp(width), WRAP_CONTENT)
    return dialog
}
