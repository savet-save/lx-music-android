package cn.toside.music.nativeapp.ui

import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.AppPreferences

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.StyleSpan
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils.blendARGB
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The original project's agreement text and first-use acknowledgement. */
internal fun showPact(activity: Activity, scope: CoroutineScope, accepted: (() -> Unit)? = null): Dialog {
    val preferences = AppPreferences(activity)
    val agreed = preferences.bool("common.isAgreePact")
    val palette = UiPalette(activity, NativeThemes(activity).active())
    val lx = LxStyles(activity, palette, Typeface.DEFAULT)
    val configuration = activity.resources.configuration
    val expanded = minOf(configuration.screenWidthDp, configuration.smallestScreenWidthDp) >= 600
    val titleText = activity.getString(R.string.pact_title)
    val dialog = Dialog(activity).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setTitle(titleText)
        setCancelable(agreed)
        setCanceledOnTouchOutside(agreed)
    }
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = lx.shape(palette.surface, radius = 6, border = palette.divider)
        clipToOutline = true
    }
    root.addView(View(activity).apply { setBackgroundColor(blendARGB(palette.surface, palette.primary, .70f)) }, MATCH_PARENT, lx.dp(16))
    root.addView(TextView(activity).apply {
        text = titleText
        textSize = 16f
        gravity = Gravity.CENTER
        isAccessibilityHeading = true
        setTextColor(palette.foreground)
        setPadding(lx.dp(12), lx.dp(12), lx.dp(12), lx.dp(12))
    }, MATCH_PARENT, WRAP_CONTENT)

    val actionColor = blendARGB(palette.foreground, palette.primary, .40f)
    val body = TextView(activity).apply {
        autoLinkMask = Linkify.WEB_URLS
        text = pactText(activity, agreed)
        setTextIsSelectable(true)
        movementMethod = LinkMovementMethod.getInstance()
        textSize = 14f
        setLineSpacing(lx.dp(3).toFloat(), 1f)
        setTextColor(palette.foreground)
        setLinkTextColor(actionColor)
        setPaddingRelative(lx.dp(16), 0, lx.dp(20), lx.dp(12))
    }
    val scroll = ScrollView(activity).apply {
        id = R.id.pact_scroll
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        scrollBarSize = lx.dp(5)
        if (Build.VERSION.SDK_INT >= 29) {
            verticalScrollbarThumbDrawable = lx.shape(blendARGB(palette.surface, palette.primary, .55f), 3)
            verticalScrollbarTrackDrawable = lx.shape(blendARGB(palette.surface, palette.primary, .16f), 3)
        }
        addView(body, MATCH_PARENT, WRAP_CONTENT)
    }
    root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { marginEnd = lx.dp(8) })

    val actions = LinearLayout(activity).apply {
        orientation = if (expanded) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        setPadding(lx.dp(8), lx.dp(8), lx.dp(8), lx.dp(12))
    }
    val disabled = intArrayOf(-android.R.attr.state_enabled)
    fun button(id: Int, label: CharSequence) = Button(activity).apply {
        this.id = id
        text = label
        textSize = 14f
        isAllCaps = false
        minHeight = lx.dp(48)
        setPadding(lx.dp(10), lx.dp(6), lx.dp(10), lx.dp(6))
        stateListAnimator = null
        backgroundTintList = null
        background = StateListDrawable().apply {
            addState(disabled, lx.shape(lx.panel, 4))
            addState(intArrayOf(), lx.touch(lx.selected, 4))
        }
        setTextColor(ColorStateList(arrayOf(disabled, intArrayOf()), intArrayOf(palette.muted, actionColor)))
    }
    val accept = button(R.id.pact_accept_button, if (agreed) activity.getString(R.string.ui_2cd0f3be8738)
        else activity.getString(R.string.pact_countdown, PACT_DELAY_SECONDS)).apply {
        isEnabled = agreed
        setOnClickListener {
            if (isEnabled && dialog.isShowing) {
                dialog.dismiss()
                if (!agreed) {
                    preferences.set("common.isAgreePact", true)
                    accepted?.invoke()
                }
            }
        }
    }
    if (!agreed) {
        val reject = button(R.id.pact_reject_button, activity.getString(R.string.pact_reject)).apply {
            setOnClickListener { dialog.dismiss(); activity.finishAndRemoveTask() }
        }
        actions.addView(reject, if (expanded) LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            else LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
    actions.addView(accept, if (expanded) LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
        if (!agreed) marginStart = lx.dp(24)
    } else LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        if (!agreed) topMargin = lx.dp(8)
    })
    root.addView(actions, MATCH_PARENT, WRAP_CONTENT)

    var job: Job? = null
    dialog.setOnDismissListener { job?.cancel() }
    dialog.setOnShowListener {
        if (!agreed) job = scope.launch {
            for (seconds in PACT_DELAY_SECONDS downTo 1) {
                accept.text = activity.getString(R.string.pact_countdown, seconds)
                delay(1000)
            }
            accept.text = activity.getString(R.string.pact_accept)
            accept.isEnabled = true
        }
    }
    dialog.setContentView(root)
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialog.show()
    dialog.window?.apply {
        setGravity(Gravity.CENTER)
        val available = if (Build.VERSION.SDK_INT >= 30) {
            val metrics = activity.windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            (metrics.bounds.width() - insets.left - insets.right) to (metrics.bounds.height() - insets.top - insets.bottom)
        } else lx.dp(configuration.screenWidthDp) to lx.dp(configuration.screenHeightDp)
        setLayout((available.first - lx.dp(24)).coerceIn(1, lx.dp(768)),
            (available.second - lx.dp(24)).coerceIn(1, lx.dp(536)))
    }
    return dialog
}

private const val PACT_DELAY_SECONDS = 20
private val pactSection = Regex("^[一二三四五六七八九]、.+$", RegexOption.MULTILINE)

private fun pactText(activity: Activity, agreed: Boolean): CharSequence {
    val agreement = activity.assets.open("licenses/project-agreement.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
    val text = SpannableStringBuilder()
    if (!agreed) {
        text.append(activity.getString(R.string.pact_notice), StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.append("\n\n")
    }
    text.append(agreement)
    pactSection.findAll(text).forEach { section ->
        text.setSpan(StyleSpan(Typeface.BOLD), section.range.first, section.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return text
}
