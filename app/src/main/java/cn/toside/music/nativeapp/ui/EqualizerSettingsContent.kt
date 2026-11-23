package cn.toside.music.nativeapp.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import androidx.core.view.ViewCompat
import androidx.core.graphics.ColorUtils
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.player.PlaybackService
import cn.toside.music.nativeapp.utils.ToastUtils
import java.text.NumberFormat
import kotlin.math.ceil

internal fun equalizerSettingsContent(
    context: Context,
    styles: LxStyles,
    palette: UiPalette,
    layout: AdaptiveLayout,
    player: PlaybackService,
): View {
    val state = player.equalizerState
    val wide = layout.expanded && layout.heightDp >= 480
    val inset = styles.dp(if (wide) 16 else 12)
    val accent = if (ColorUtils.calculateContrast(palette.primary, palette.surface) >= 3) palette.primary
        else ColorUtils.blendARGB(palette.primary, palette.foreground, .38f)
    val root = LinearLayout(context).apply {
        tag = "settings-equalizer"
        orientation = LinearLayout.VERTICAL
        background = styles.shape(styles.panel, radius = 14, border = palette.divider)
        setPadding(inset, 0, inset, 0)
    }
    @Suppress("UseSwitchCompatOrMaterialCode")
    val toggle = Switch(context).apply {
        tag = "settings-equalizer-enabled"
        setText(R.string.equalizer_title)
        setTextColor(palette.foreground)
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        minHeight = styles.dp(56)
        isChecked = state?.enabled == true
        isEnabled = state != null
        thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, palette.muted))
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ColorUtils.blendARGB(styles.panel, accent, .4f), ColorUtils.blendARGB(styles.panel, palette.muted, .35f)))
    }
    root.addView(toggle, LinearLayout.LayoutParams(-1, -2))
    if (state == null) {
        root.addView(styles.label(context.getString(R.string.equalizer_unavailable), 13f, palette.muted).apply {
            maxLines = 3
            setPadding(0, 0, 0, styles.dp(12))
        })
        return root
    }

    val controls = LinearLayout(context).apply {
        tag = "settings-equalizer-bands"
        orientation = LinearLayout.VERTICAL
        visibility = if (state.enabled) View.VISIBLE else View.GONE
    }
    val frequencyFormat = NumberFormat.getNumberInstance(context.resources.configuration.locales[0]).apply {
        maximumFractionDigits = 2
        isGroupingUsed = false
    }
    val frequencyLabels = state.bands.map { band ->
        val khz = band.frequencyMillihertz >= 1_000_000
        val frequency = frequencyFormat.format(band.frequencyMillihertz / if (khz) 1_000_000.0 else 1000.0)
        context.getString(if (khz) R.string.equalizer_khz else R.string.equalizer_hz, frequency)
    }
    fun gainText(gain: Int) = context.getString(
        if (gain > 0) R.string.equalizer_gain_positive else R.string.equalizer_gain, gain / 100.0)
    fun showError() = ToastUtils.show(context, R.string.equalizer_error)

    val chart = EqualizerGainChart(context, styles, palette, accent, state, frequencyLabels, ::gainText, wide).apply {
        background = styles.shape(palette.surface, radius = 10, border = palette.divider)
    }
    val reset = Button(context).apply {
        tag = "settings-equalizer-reset"
        setText(R.string.equalizer_reset)
        setTextColor(palette.foreground)
        textSize = 12f
        isAllCaps = false
        background = styles.touch()
        minHeight = styles.dp(48)
        setPadding(inset, 0, inset, 0)
        minimumWidth = 0
        minWidth = styles.dp(48)
    }
    controls.addView(LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(styles.label(context.getString(R.string.equalizer_gain_chart), 13f, palette.foreground).apply {
            isAccessibilityHeading = true
            maxLines = 2
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(reset, LinearLayout.LayoutParams(-2, -2))
    }, LinearLayout.LayoutParams(-1, -2))
    controls.addView(chart, LinearLayout.LayoutParams(-1, styles.dp(if (wide) 192 else 132)))

    val faders = LinearLayout(context).apply {
        tag = "settings-equalizer-faders"
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        isBaselineAligned = false
    }
    val bank = HorizontalScrollView(context).apply {
        tag = "settings-equalizer-fader-scroll"
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        isFillViewport = true
        scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
        setPadding(0, styles.dp(10), 0, styles.dp(6))
        addView(faders, FrameLayout.LayoutParams(-2, -2))
    }
    val labelSize = if (wide) 12f else 11f
    val labelPaint = styles.label("", labelSize).paint
    val maxLabel = (frequencyLabels + listOf(gainText(state.gainRange.first), gainText(state.gainRange.last)))
        .maxOf(labelPaint::measureText)
    val bandWidth = maxOf(styles.dp(if (wide) 62 else 48), ceil(maxLabel).toInt() + styles.dp(8))
    val railLength = styles.dp(if (wide) 142 else 96)
    val sliders = state.bands.mapIndexed { index, band ->
        val label = frequencyLabels[index]
        val cell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        cell.addView(styles.label(label, labelSize).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, -2))
        val gain = styles.label(gainText(band.gainMillibels), labelSize, palette.foreground).apply {
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val slider = EqualizerFader(context, styles, palette, accent).apply {
            tag = "settings-equalizer-band-$index"
            min = state.gainRange.first
            max = state.gainRange.last
            progress = band.gainMillibels
            keyProgressIncrement = 100
            contentDescription = context.getString(R.string.equalizer_band, label)
            ViewCompat.setStateDescription(this, gain.text)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser && !player.setEqualizerGain(index, progress)) {
                        bar.progress = player.equalizerState?.bands?.getOrNull(index)?.gainMillibels ?: 0
                        showError()
                    }
                    gain.text = gainText(bar.progress)
                    chart.updateGain(index, bar.progress)
                    bar.invalidate()
                    ViewCompat.setStateDescription(bar, gain.text)
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        cell.addView(FrameLayout(context).apply {
            clipChildren = false
            addView(slider, FrameLayout.LayoutParams(railLength, styles.dp(48), Gravity.CENTER))
        }, LinearLayout.LayoutParams(-1, railLength))
        cell.addView(gain, LinearLayout.LayoutParams(-1, -2))
        faders.addView(cell, LinearLayout.LayoutParams(bandWidth, -2, 1f))
        slider
    }
    reset.setOnClickListener {
        if (!player.resetEqualizer()) showError()
        player.equalizerState?.bands?.forEachIndexed { index, band -> sliders[index].progress = band.gainMillibels }
    }
    controls.addView(bank, LinearLayout.LayoutParams(-1, -2))
    controls.addView(styles.label(context.getString(R.string.equalizer_offload_hint), 11f, palette.muted).apply {
        maxLines = 3
        setPadding(0, styles.dp(6), 0, styles.dp(12))
    })
    root.addView(controls, LinearLayout.LayoutParams(-1, -2))
    toggle.setOnCheckedChangeListener { _, checked ->
        val enabled = player.equalizerState?.enabled == true
        if (checked != enabled && !player.setEqualizerEnabled(checked)) {
            toggle.isChecked = enabled
            showError()
        }
        controls.visibility = if (toggle.isChecked) View.VISIBLE else View.GONE
    }
    return root
}
