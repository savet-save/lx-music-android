package cn.toside.music.nativeapp.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.karaoke.KaraokeRecording
import cn.toside.music.nativeapp.lyrics.LrcParser
import cn.toside.music.nativeapp.player.PlaybackService
import cn.toside.music.nativeapp.player.PlayerLyrics
import cn.toside.music.nativeapp.player.PlayerSettings
import cn.toside.music.nativeapp.utils.PermissionPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.NumberFormat

class KaraokeActivity : FragmentActivity() {
    private val studio by viewModels<KaraokeViewModel>()
    private val song by lazy { intent.getBundleExtra(SONG)?.let { value ->
        Song(value.getLong("id"), value.getString("uri").orEmpty().toUri(), value.getString("title").orEmpty(),
            value.getString("artist").orEmpty(), value.getLong("duration"), value.getString("source", "local"), value.getString("info"))
    } }
    private lateinit var palette: UiPalette
    private lateinit var styles: LxStyles
    private lateinit var playerSettings: PlayerSettings
    private lateinit var headerTitle: TextView
    private lateinit var sectionButton: Button
    private lateinit var editorScroll: ScrollView
    private lateinit var libraryScroll: ScrollView
    private lateinit var libraryList: LinearLayout
    private lateinit var message: TextView
    private lateinit var status: TextView
    private lateinit var lyric: TextView
    private lateinit var timing: TextView
    private lateinit var progress: ProgressBar
    private lateinit var captureButton: Button
    private lateinit var stopButton: Button
    private lateinit var editor: LinearLayout
    private lateinit var musicGain: Slider
    private lateinit var voiceGain: Slider
    private lateinit var trimStart: Slider
    private lateinit var trimEnd: Slider
    private lateinit var voiceDelay: Slider
    private lateinit var noise: CheckBox
    private lateinit var preview: Button
    private lateinit var share: Button
    private lateinit var export: Button
    private var displayedRecordings: List<KaraokeRecording>? = null
    private val previewButtons = mutableMapOf<String, Button>()
    private var service: PlaybackService? = null
    private var bound = false
    private var lyricJob: Job? = null
    private var lyrics = PlayerLyrics()
    private var pendingExport: String? = null
    private val number by lazy { NumberFormat.getIntegerInstance(resources.configuration.locales[0]).apply { isGroupingUsed = false } }

    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) studio.record() else studio.message(R.string.karaoke_microphone_required)
    }
    private val libraryPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) prepareSong() else studio.message(R.string.karaoke_prepare_error)
    }
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val id = pendingExport
        pendingExport = null
        if (uri != null && id != null) studio.export(id, uri)
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val player = (binder as PlaybackService.LocalBinder).service
            service = player
            prepareSong()
            lyricJob = lifecycleScope.launch {
                player.lyricState.collect {
                    lyrics = it
                    renderLyrics(studio.state.value)
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null; lyricJob?.cancel() }
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(UiPreferences.context(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        val theme = NativeThemes(this).active()
        setTheme(if (theme.dark) R.style.NativeDark else R.style.NativeLight)
        super.onCreate(savedInstanceState)
        palette = UiPalette(this, theme)
        styles = LxStyles(this, palette, Typeface.createFromAsset(assets, "fonts/icomoon.ttf"))
        playerSettings = PlayerSettings(this)
        pendingExport = savedInstanceState?.getString("export")
        val root = buildPage()
        setContentView(root)
        ScreenInsets.install(window, root)
        ScreenInsets.styleSystemBars(window, theme.dark)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goBack() }
        })
        if (song == null) studio.showLibrary(true)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { studio.state.collect(::render) }
        }
    }

    override fun onStart() {
        super.onStart()
        if (song != null) {
            prepareSong()
            bound = bindService(Intent(this, PlaybackService::class.java), connection, BIND_AUTO_CREATE)
            if (!bound && song?.source != "local") studio.message(R.string.karaoke_prepare_error)
        }
    }

    override fun onStop() {
        studio.pause()
        studio.stopPreview()
        lyricJob?.cancel()
        if (bound) unbindService(connection)
        bound = false
        service = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("export", pendingExport)
        super.onSaveInstanceState(outState)
    }

    private fun prepareSong() {
        val selected = song ?: return
        if (PermissionPolicy.requiresLibraryAccess(this, selected) && checkSelfPermission(PermissionPolicy.audio(Build.VERSION.SDK_INT)) != PackageManager.PERMISSION_GRANTED) {
            studio.message(R.string.karaoke_prepare_error)
            return
        }
        if (selected.source == "local") studio.prepare { selected.uri }
        else service?.let { player -> studio.prepare { file -> player.karaokeInput(selected, file) } }
    }

    private fun record() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) studio.record()
        else microphone.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun captureAction() {
        when (studio.state.value.stage) {
            KaraokeStage.IDLE -> {
                if (PermissionPolicy.requiresLibraryAccess(this, song) && checkSelfPermission(PermissionPolicy.audio(Build.VERSION.SDK_INT)) != PackageManager.PERMISSION_GRANTED)
                    libraryPermission.launch(PermissionPolicy.audio(Build.VERSION.SDK_INT))
                else prepareSong()
            }
            KaraokeStage.READY -> record()
            KaraokeStage.RECORDING -> studio.pause()
            KaraokeStage.PAUSED -> studio.resume()
            KaraokeStage.REVIEW -> {
                if (studio.state.value.saved != null) record()
                else confirm(R.string.karaoke_replace_draft, ::record)
            }
            else -> Unit
        }
    }

    private fun goBack() {
        val state = studio.state.value
        if (state.libraryOpen && song != null) { studio.showLibrary(false); return }
        if (state.stage in setOf(KaraokeStage.RECORDING, KaraokeStage.PAUSED, KaraokeStage.MIXING) ||
            state.stage == KaraokeStage.REVIEW && state.saved == null) {
            studio.pause()
            confirm(R.string.karaoke_leave_draft, ::finish)
        } else finish()
    }

    private fun confirm(title: Int, proceed: () -> Unit) {
        AlertDialog.Builder(this).setMessage(title).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> proceed() }.show()
    }

    private fun export(recording: KaraokeRecording) {
        pendingExport = recording.id
        exportPicker.launch(recording.exportName)
    }

    private fun share(recording: KaraokeRecording) {
        val uri = FileProvider.getUriForFile(this, "$packageName.karaoke", recording.file, recording.exportName)
        val send = Intent(Intent.ACTION_SEND).setType("audio/wav").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, recording.title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newUri(contentResolver, recording.title, uri) }
        startActivity(Intent.createChooser(send, getString(R.string.karaoke_share)))
    }

    private fun buildPage() = column().apply {
        tag = "karaoke-page"
        setBackgroundColor(palette.surface)
        val toolbar = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(8), 0)
            addView(styles.backButton(::goBack), LinearLayout.LayoutParams(dp(48), dp(48)))
            headerTitle = styles.label(getString(R.string.karaoke_title), 20f).apply {
                setTypeface(typeface, Typeface.BOLD)
                isAccessibilityHeading = true
            }
            addView(headerTitle, LinearLayout.LayoutParams(0, -2, 1f))
            sectionButton = button(R.string.karaoke_library) { studio.showLibrary(!studio.state.value.libraryOpen) }
            addView(sectionButton, LinearLayout.LayoutParams(-2, -2))
        }
        addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        addView(View(context).apply { setBackgroundColor(palette.divider) }, LinearLayout.LayoutParams(-1, dp(1)))
        editorScroll = ScrollView(context).apply { addView(studioContent()) }
        libraryScroll = ScrollView(context).apply {
            tag = "karaoke-library"
            libraryList = column().apply { setPadding(dp(16), dp(12), dp(16), dp(16)) }
            addView(libraryList)
        }
        addView(editorScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(libraryScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        message = styles.label("", 13f, palette.muted).apply {
            tag = "karaoke-message"
            maxLines = 3
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        addView(message, LinearLayout.LayoutParams(-1, -2))
    }

    private fun studioContent() = column().apply {
        val selected = song
        val inset = dp(if (resources.configuration.screenWidthDp >= 600) 24 else 16)
        setPadding(inset, dp(16), inset, dp(20))
        val songCard = column().apply {
            background = styles.shape(styles.panel, radius = 12, border = palette.divider)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(styles.label(selected?.title.orEmpty(), 19f).apply {
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 2
            })
            selected?.artist?.takeIf { it.isNotBlank() }?.let { addView(styles.label(it, 13f, palette.muted)) }
            status = styles.label("", 13f, palette.muted).apply { maxLines = 3; setPadding(0, dp(8), 0, dp(4)) }
            addView(status)
            timing = styles.label("", 14f).apply { gravity = Gravity.END; setPadding(0, dp(4), 0, 0) }
            addView(timing, LinearLayout.LayoutParams(-1, -2))
            progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                progressTintList = ColorStateList.valueOf(palette.primary)
            }
            addView(progress, LinearLayout.LayoutParams(-1, dp(8)))
        }
        addView(songCard, LinearLayout.LayoutParams(-1, -2))
        lyric = styles.label("", 19f, palette.primary).apply {
            maxLines = 3
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }
        addView(lyric, LinearLayout.LayoutParams(-1, -2))
        captureButton = button(R.string.karaoke_record, filled = true, click = ::captureAction).apply { tag = "karaoke-capture" }
        stopButton = button(R.string.karaoke_stop, click = studio::stop).apply { tag = "karaoke-stop" }
        addView(actions(captureButton, stopButton))
        editor = column().apply {
            tag = "karaoke-editor"
            setPadding(0, dp(12), 0, 0)
            val gain = { value: Int -> getString(R.string.karaoke_percent, value) }
            musicGain = slider(this, R.string.karaoke_music_gain, 0, 100, gain) { value -> studio.edit { it.copy(music = value) } }
            voiceGain = slider(this, R.string.karaoke_voice_gain, 0, 100, gain) { value -> studio.edit { it.copy(voice = value) } }
            val trimTime = { value: Int -> getString(R.string.karaoke_trim_time, time(value), value % 1000 / 100) }
            trimStart = slider(this, R.string.karaoke_trim_start, 0, 0, trimTime) { value ->
                studio.edit { it.copy(trimStartMs = value.coerceAtMost((studio.state.value.duration - it.trimEndMs - 250).coerceAtLeast(0))) }
            }
            trimEnd = slider(this, R.string.karaoke_trim_end, 0, 0, trimTime) { value ->
                studio.edit { it.copy(trimEndMs = value.coerceAtMost((studio.state.value.duration - it.trimStartMs - 250).coerceAtLeast(0))) }
            }
            voiceDelay = slider(this, R.string.karaoke_voice_delay, -300, 300, { getString(R.string.karaoke_ms, it) }) { value ->
                studio.edit { it.copy(voiceDelayMs = value) }
            }
            noise = CheckBox(context).apply {
                setText(R.string.karaoke_noise)
                setTextColor(palette.foreground)
                buttonTintList = ColorStateList.valueOf(palette.primary)
                minHeight = dp(48)
                setOnCheckedChangeListener { button, checked ->
                    if (button.isPressed || checked != studio.state.value.edit.reduceNoise) studio.edit { it.copy(reduceNoise = checked) }
                }
            }
            addView(noise)
            preview = button(R.string.karaoke_preview, click = studio::previewTake).apply { tag = "karaoke-preview" }
            addView(actions(preview, button(R.string.karaoke_save, filled = true) { song?.let(studio::save) }.apply { tag = "karaoke-save" }))
            export = button(R.string.karaoke_export) { studio.state.value.saved?.let(::export) }
            share = button(R.string.karaoke_share) { studio.state.value.saved?.let(::share) }
            addView(actions(export, share))
        }
        addView(editor)
    }

    private fun render(state: KaraokeState) {
        val inLibrary = song == null || state.libraryOpen
        headerTitle.setText(if (inLibrary) R.string.karaoke_library else R.string.karaoke_title)
        sectionButton.visibility = if (song != null) View.VISIBLE else View.GONE
        sectionButton.setText(if (inLibrary) R.string.karaoke_title else R.string.karaoke_library)
        editorScroll.visibility = if (inLibrary) View.GONE else View.VISIBLE
        libraryScroll.visibility = if (inLibrary) View.VISIBLE else View.GONE
        message.visibility = if (state.message != null) View.VISIBLE else View.GONE
        state.message?.let(message::setText)
        if (inLibrary) {
            renderLibrary(state)
            return
        }
        status.setText(when (state.stage) {
            KaraokeStage.IDLE, KaraokeStage.PREPARING -> R.string.karaoke_preparing
            KaraokeStage.READY -> if (state.mono) R.string.karaoke_mono else R.string.karaoke_ready
            KaraokeStage.RECORDING -> R.string.karaoke_recording
            KaraokeStage.PAUSED -> R.string.karaoke_paused
            KaraokeStage.MIXING -> R.string.karaoke_mixing
            KaraokeStage.REVIEW -> R.string.karaoke_review
        })
        timing.text = getString(R.string.karaoke_timing, time(state.position), time(state.duration))
        progress.max = state.duration.coerceAtLeast(1)
        progress.progress = state.position
        renderLyrics(state)
        captureButton.isEnabled = state.stage !in setOf(KaraokeStage.PREPARING, KaraokeStage.MIXING)
        captureButton.setText(when (state.stage) {
            KaraokeStage.IDLE -> R.string.karaoke_retry
            KaraokeStage.RECORDING -> R.string.karaoke_pause
            KaraokeStage.PAUSED -> R.string.karaoke_resume
            KaraokeStage.REVIEW -> R.string.karaoke_rerecord
            else -> R.string.karaoke_record
        })
        stopButton.visibility = if (state.stage in setOf(KaraokeStage.RECORDING, KaraokeStage.PAUSED)) View.VISIBLE else View.GONE
        val editing = state.stage == KaraokeStage.REVIEW
        editor.visibility = if (editing || state.stage == KaraokeStage.MIXING) View.VISIBLE else View.GONE
        if (editor.visibility != View.VISIBLE) return
        for (index in 0 until editor.childCount) {
            val child = editor.getChildAt(index)
            child.isEnabled = editing
            if (child is LinearLayout) for (button in 0 until child.childCount) child.getChildAt(button).isEnabled = editing
        }
        musicGain.update(state.edit.music)
        voiceGain.update(state.edit.voice)
        trimStart.update(state.edit.trimStartMs, (state.duration - state.edit.trimEndMs - 250).coerceAtLeast(0))
        trimEnd.update(state.edit.trimEndMs, (state.duration - state.edit.trimStartMs - 250).coerceAtLeast(0))
        voiceDelay.update(state.edit.voiceDelayMs)
        noise.isChecked = state.edit.reduceNoise
        preview.setText(if (state.playingPreview == KaraokeViewModel.DRAFT) R.string.karaoke_preview_stop else R.string.karaoke_preview)
        export.visibility = if (state.saved != null) View.VISIBLE else View.GONE
        share.visibility = export.visibility
    }

    private fun renderLyrics(state: KaraokeState) {
        val activeSong = song ?: return
        val lines = lyrics.lines.takeIf { lyrics.key == "${activeSong.source}:${activeSong.id}" }.orEmpty()
        val index = LrcParser.indexAt(lines, state.position.toLong())
        val line = lines.getOrNull(index)?.let { LyricRendering.text(it, playerSettings) }.orEmpty()
        lyric.visibility = if (line.isNotBlank() && state.stage in setOf(KaraokeStage.RECORDING, KaraokeStage.PAUSED)) View.VISIBLE else View.GONE
        if (lyric.text != line) lyric.text = line
    }

    private fun renderLibrary(state: KaraokeState) {
        if (displayedRecordings != state.recordings) {
            displayedRecordings = state.recordings
            libraryList.removeAllViews()
            previewButtons.clear()
            if (state.recordings.isEmpty()) libraryList.addView(styles.label(getString(R.string.karaoke_empty_library), 15f, palette.muted).apply {
                gravity = Gravity.CENTER
                minHeight = dp(96)
            })
            state.recordings.forEach { recording ->
                val row = column().apply {
                    background = styles.shape(styles.panel, radius = 10, border = palette.divider)
                    setPadding(dp(12), dp(8), dp(12), dp(4))
                    addView(styles.label(recording.title, 16f).apply { maxLines = 2 })
                    val detail = listOf(recording.artist, time(recording.durationMs)).filter { it.isNotBlank() }.joinToString(" · ")
                    addView(styles.label(detail, 13f, palette.muted))
                    val play = button(R.string.karaoke_preview) { studio.previewRecording(recording) }
                    previewButtons[recording.id] = play
                    addView(actions(play, button(R.string.karaoke_export) { export(recording) }, button(R.string.karaoke_share) { share(recording) }))
                }
                libraryList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }
        previewButtons.forEach { (id, play) -> play.setText(if (id == state.playingPreview) R.string.karaoke_preview_stop else R.string.karaoke_preview) }
    }

    private fun slider(parent: LinearLayout, title: Int, low: Int, high: Int, format: (Int) -> String, change: (Int) -> Unit): Slider {
        val description = getString(title)
        val header = styles.label(description, 14f).apply { setPadding(0, dp(8), 0, 0) }
        val bar = SeekBar(this).apply {
            min = low
            max = high
            contentDescription = description
            progressTintList = ColorStateList.valueOf(palette.primary)
            thumbTintList = ColorStateList.valueOf(palette.primary)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(view: SeekBar, value: Int, fromUser: Boolean) { if (fromUser) change(value) }
                override fun onStartTrackingTouch(view: SeekBar) = Unit
                override fun onStopTrackingTouch(view: SeekBar) = Unit
            })
        }
        parent.addView(header)
        parent.addView(bar, LinearLayout.LayoutParams(-1, dp(48)))
        return Slider(header, bar, description, format)
    }

    private class Slider(val label: TextView, val bar: SeekBar, val title: String, val format: (Int) -> String) {
        fun update(value: Int, max: Int = bar.max) {
            bar.max = max
            bar.progress = value
            label.text = label.context.getString(R.string.karaoke_control_value, title, format(value))
        }
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun actions(vararg buttons: Button) = LinearLayout(this).apply {
        setPadding(0, dp(4), 0, dp(4))
        buttons.forEach { button -> addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) }) }
    }

    private fun button(label: Int, filled: Boolean = false, click: () -> Unit) = Button(this).apply {
        setText(label)
        isAllCaps = false
        textSize = 13f
        minHeight = dp(48)
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(palette.foreground)
        background = styles.touch(if (filled) styles.selected else android.graphics.Color.TRANSPARENT)
        setOnClickListener { click() }
    }

    private fun time(ms: Int): String {
        val seconds = ms / 1000
        return "${number.format(seconds / 60)}:${number.format(seconds % 60).padStart(2, '0')}"
    }

    private fun dp(value: Int) = styles.dp(value)

    internal companion object {
        private const val SONG = "song"
        fun intent(context: Context, song: Song?): Intent = Intent(context, KaraokeActivity::class.java).apply {
            if (song != null) putExtra(SONG, Bundle().apply {
                putLong("id", song.id)
                putString("uri", song.uri.toString())
                putString("title", song.title)
                putString("artist", song.artist)
                putLong("duration", song.duration)
                putString("source", song.source)
                putString("info", song.sourceInfo)
            })
        }
    }
}

class KaraokeFileProvider : FileProvider()
