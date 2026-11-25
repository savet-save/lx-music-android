package cn.toside.music.nativeapp.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cn.toside.music.nativeapp.NativeApplication
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.karaoke.KaraokePreparation
import cn.toside.music.nativeapp.karaoke.KaraokePreparation.Status
import kotlinx.coroutines.launch

internal fun Context.karaokePreparationStatus(progress: KaraokePreparation.Progress): String = when (progress.status) {
    Status.WAITING -> if (progress.position > 0) getString(R.string.karaoke_queue_waiting, progress.position)
        else getString(R.string.karaoke_preparing)
    Status.RUNNING -> progress.modelDownload?.let { getString(R.string.karaoke_model_downloading, it) }
        ?: getString(R.string.karaoke_preparing_progress, progress.percent)
    Status.CANCELLING -> getString(R.string.karaoke_queue_cancelling)
}

class KaraokeQueueActivity : FragmentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(UiPreferences.context(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        val theme = NativeThemes(this).active()
        setTheme(if (theme.dark) R.style.NativeDark else R.style.NativeLight)
        super.onCreate(savedInstanceState)
        val palette = UiPalette(this, theme)
        val styles = LxStyles(this, palette, Typeface.createFromAsset(assets, "fonts/icomoon.ttf"))
        val preparation = (application as NativeApplication).karaokePreparation
        val root = LinearLayout(this).apply {
            tag = "karaoke-queue-page"
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.surface)
        }
        val toolbar = LinearLayout(this).apply {
            minimumHeight = styles.dp(56)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(styles.dp(4), 0, styles.dp(12), 0)
            addView(styles.backButton(::finish), LinearLayout.LayoutParams(styles.dp(48), styles.dp(48)))
            addView(styles.label(getString(R.string.karaoke_queue_title), 20f).apply {
                setTypeface(typeface, Typeface.BOLD)
                isAccessibilityHeading = true
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(toolbar)
        root.addView(View(this).apply { setBackgroundColor(palette.divider) }, LinearLayout.LayoutParams(-1, styles.dp(1)))
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = styles.dp(if (resources.configuration.screenWidthDp >= 600) 24 else 16)
            setPadding(inset, styles.dp(12), inset, styles.dp(12))
        }
        val empty = styles.label(getString(R.string.karaoke_queue_empty), 15f, palette.muted).apply {
            tag = "karaoke-queue-empty"
            maxLines = 3
            setPadding(0, styles.dp(16), 0, styles.dp(16))
        }
        list.addView(empty)
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ScreenInsets.install(window, root)
        ScreenInsets.styleSystemBars(window, theme.dark)
        val rows = mutableMapOf<String, QueueRow>()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                preparation.queue.collect { entries ->
                    empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                    (rows.keys - entries.map { it.id }.toSet()).forEach { id ->
                        rows.remove(id)?.let { list.removeView(it.view) }
                    }
                    entries.forEach { entry ->
                        val row = rows.getOrPut(entry.id) {
                            QueueRow(entry, palette, styles) { preparation.cancel(entry.id) }.also {
                                list.addView(it.view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = styles.dp(8) })
                            }
                        }
                        row.render(entry.progress)
                    }
                }
            }
        }
    }

    private inner class QueueRow(entry: KaraokePreparation.QueueEntry, palette: UiPalette, styles: LxStyles, onCancel: () -> Unit) {
        private val status = styles.label("", 13f, palette.muted).apply {
            tag = "karaoke-queue-status"
            maxLines = 2
        }
        private val progressBar = ProgressBar(this@KaraokeQueueActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(palette.primary)
        }
        private val cancel = Button(this@KaraokeQueueActivity).apply {
            tag = "karaoke-queue-cancel"
            setText(android.R.string.cancel)
            contentDescription = getString(R.string.karaoke_queue_cancel_song, entry.song.title)
            isAllCaps = false
            minHeight = styles.dp(48)
            setPadding(styles.dp(12), 0, styles.dp(12), 0)
            setTextColor(palette.foreground)
            background = styles.touch()
            setOnClickListener { onCancel() }
        }
        val view = LinearLayout(this@KaraokeQueueActivity).apply {
            tag = "karaoke-queue:${entry.id}"
            orientation = LinearLayout.VERTICAL
            background = styles.shape(styles.panel, radius = 10, border = palette.divider)
            setPadding(styles.dp(12), styles.dp(8), styles.dp(8), styles.dp(8))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(styles.label(entry.song.title, 16f).apply { maxLines = 2 })
                    if (entry.song.artist.isNotBlank()) addView(styles.label(entry.song.artist, 13f, palette.muted))
                    addView(status)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(cancel, LinearLayout.LayoutParams(-2, -2))
            })
            addView(progressBar, LinearLayout.LayoutParams(-1, styles.dp(8)).apply { topMargin = styles.dp(6) })
        }

        fun render(progress: KaraokePreparation.Progress) {
            val label = karaokePreparationStatus(progress)
            if (status.text != label) status.text = label
            progressBar.progress = progress.modelDownload ?: progress.percent
            progressBar.visibility = if (progress.status == Status.WAITING) View.GONE else View.VISIBLE
            cancel.isEnabled = progress.status != Status.CANCELLING
        }
    }
}
