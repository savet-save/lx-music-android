package cn.toside.music.nativeapp.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal data class BilibiliDownloadOption(val quality: String, val label: String)

/** Cached choices are available offline; other accessible bitrates are loaded only when needed. */
internal fun showBilibiliDownloadQuality(activity: Activity, song: Song, palette: UiPalette, styles: LxStyles,
    parentScope: CoroutineScope, cached: suspend () -> List<BilibiliDownloadOption>,
    remote: suspend () -> List<BilibiliDownloadOption>, dismissed: (Dialog) -> Unit,
    selected: (String) -> Unit): Dialog {
    val dialog = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE); setTitle(activity.getString(R.string.download_choose_quality)) }
    val work = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    dialog.setOnDismissListener { work.cancel(); dismissed(dialog) }
    val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; setPadding(styles.dp(16), styles.dp(12), styles.dp(16), styles.dp(16))
        background = styles.shape(palette.surface, radius = 6, border = palette.divider)
    }
    fun label(text: String, size: Float = 14f) = styles.label(text, size).apply { maxLines = 4; gravity = Gravity.CENTER }
    fun button(text: String, tagValue: String, click: () -> Unit) = Button(activity).apply {
        tag = tagValue; this.text = text; isAllCaps = false; minHeight = styles.dp(48)
        setTextColor(palette.primary); background = styles.touch(styles.selected); backgroundTintList = null; stateListAnimator = null
        setOnClickListener { click() }
    }
    body.addView(label(activity.getString(R.string.download_choose_quality), 16f))
    body.addView(label(song.title))
    val options = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    val status = label(activity.getString(R.string.download_bitrate_loading)).apply { tag = "bili-download-status" }
    val more = button(activity.getString(R.string.download_other_bitrates), "bili-download-more") {}.apply { visibility = View.GONE }
    body.addView(status); body.addView(options)
    body.addView(more, LinearLayout.LayoutParams(-1, -2).apply { topMargin = styles.dp(8) })
    body.addView(button(activity.getString(android.R.string.cancel), "download-quality-close", dialog::dismiss),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = styles.dp(8) })
    fun render(values: List<BilibiliDownloadOption>) {
        options.removeAllViews()
        values.forEach { value ->
            options.addView(button(value.label, "download-quality-${value.quality}") { dialog.dismiss(); selected(value.quality) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = styles.dp(8) })
        }
    }
    fun loadRemote() {
        more.isEnabled = false
        status.visibility = View.VISIBLE; status.text = activity.getString(R.string.download_bitrate_loading)
        work.launch {
            try {
                val values = remote()
                check(values.isNotEmpty())
                render(values); status.visibility = View.GONE; more.visibility = View.GONE
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                status.text = e.localizedMessage ?: activity.getString(R.string.download_probe_unavailable)
                more.text = activity.getString(R.string.download_bitrate_retry); more.visibility = View.VISIBLE
            } finally { more.isEnabled = true }
        }
    }
    more.setOnClickListener { loadRemote() }
    dialog.setContentView(ScrollView(activity).apply { addView(body) })
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialog.show()
    dialog.window?.setLayout(styles.dp((activity.resources.configuration.screenWidthDp - 32).coerceIn(1, 360)), -2)
    work.launch {
        try {
            val values = cached()
            if (values.isEmpty()) loadRemote() else {
                render(values); status.visibility = View.GONE; more.visibility = View.VISIBLE
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { loadRemote() }
    }
    return dialog
}
