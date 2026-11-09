package cn.toside.music.nativeapp.ui

import android.app.Activity
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.sync.BackgroundSyncState
import cn.toside.music.nativeapp.sync.SyncCredentialStore
import cn.toside.music.nativeapp.sync.SyncFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Connection details and pending decisions stay in the settings page. Leaving never chooses a mode. */
internal class SyncSettingsSection(
    private val activity: Activity,
    parentScope: CoroutineScope,
    private val content: LinearLayout,
    private val styles: LxStyles,
    private val palette: UiPalette,
    private val actions: SettingsActions,
    draftAddress: String?,
) : AutoCloseable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val store = SyncCredentialStore(activity)
    private val status = label("").apply { tag = "sync-status"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val address = input(R.string.ui_9f9e4c872ae8, InputType.TYPE_TEXT_VARIATION_URI).apply {
        tag = "sync-address"; setText(draftAddress ?: store.lastAddress())
    }
    private val code = input(R.string.ui_0464d293b4f4, InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
        tag = "sync-code"; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO; isSaveEnabled = false
    }
    private val connect = button(R.string.ui_a5574109f020) {
        val host = address.text.toString().trim().trimEnd('/')
        if (host.isBlank()) status.text = activity.getString(R.string.ui_686944dc637e)
        else { actions.connectSync(host, code.text.toString()); code.text.clear() }
    }.apply { tag = "sync-connect" }
    private val disconnect = button(R.string.ui_a33f3804768e, actions.disconnectSync).apply { tag = "sync-disconnect" }
    private val conflicts = column().apply { tag = "sync-conflict" }
    private val history = column().apply { tag = "sync-history" }
    private var active = false
    private var conflictId: Long? = null
    private var hosts = store.history()

    init {
        content.addView(label(activity.getString(R.string.ui_f40e986f62c5)))
        content.addView(status)
        field(R.string.layout_sync_address, address)
        field(R.string.layout_sync_code, code)
        content.addView(connect); content.addView(disconnect); content.addView(conflicts)
        content.addView(label(activity.getString(R.string.sync_history)))
        content.addView(history)
        render(actions.syncState())
    }

    fun addressDraft(): String = address.text.toString()

    fun render(value: BackgroundSyncState) {
        status.text = activity.syncMessage(value.message).ifBlank {
            activity.getString(if (value.active) R.string.ui_9daac0f16f9a else R.string.ui_49d0208f4a94)
        }
        active = value.active
        address.isEnabled = !active; code.isEnabled = !active
        connect.visibility = if (active) View.GONE else View.VISIBLE
        disconnect.visibility = if (active) View.VISIBLE else View.GONE
        val request = value.conflict
        if (request?.id != conflictId) {
            conflictId = request?.id; conflicts.removeAllViews()
            if (request != null) {
                val feature = request.feature
                val name = feature.localized(activity)
                conflicts.addView(label(activity.getString(R.string.ui_5265436b89fb, name,
                    request.serverName.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())))
                val labels = listOf(R.string.ui_9bc599d27f67, R.string.ui_d46592bdaed4, R.string.ui_039142dac5c0, R.string.ui_3e4d21031b6b)
                    .map { activity.getString(it, name, name) } +
                    (if (feature == SyncFeature.LIST) listOf(activity.getString(R.string.ui_318ed0b4705c), activity.getString(R.string.ui_155471e97da7)) else emptyList()) +
                    activity.getString(R.string.ui_5034ddc8102c)
                labels.forEachIndexed { index, title ->
                    conflicts.addView(action(title) {
                        if (actions.chooseSync(request.id, feature.modes[index])) {
                            for (i in 0 until conflicts.childCount) conflicts.getChildAt(i).isEnabled = false
                        }
                    }.apply { tag = "sync-mode-${feature.modes[index]}" })
                }
            }
        }
        val updated = store.history()
        if (updated != hosts || history.childCount == 0 || history.isEnabled == active) {
            hosts = updated; history.isEnabled = !active; renderHistory()
        }
    }

    private fun renderHistory() {
        history.removeAllViews()
        if (hosts.isEmpty()) history.addView(label(activity.getString(R.string.sync_history_empty)))
        hosts.forEach { host ->
            val row = column()
            row.addView(action(listOf(host.name, host.address).filter { it.isNotBlank() }.joinToString("\n")) {
                address.setText(host.address); code.text.clear(); address.requestFocus()
            }.apply { isEnabled = !active })
            row.addView(button(R.string.sync_history_remove_action) {
                val confirmation = LinearLayout(activity)
                confirmation.addView(button(android.R.string.ok) {
                    for (i in 0 until confirmation.childCount) confirmation.getChildAt(i).isEnabled = false
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { store.remove(host.address) }
                            hosts = store.history(); renderHistory()
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { status.text = e.localizedMessage; renderHistory() }
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                confirmation.addView(button(android.R.string.cancel) { renderHistory() }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(label(activity.getString(R.string.sync_history_remove) + " · " + host.address))
                row.addView(confirmation)
                row.getChildAt(1).isEnabled = false
            }.apply { isEnabled = !active })
            history.addView(row)
        }
    }

    private fun field(title: Int, input: EditText) {
        content.addView(label(activity.getString(title)).apply { labelFor = input.id })
        content.addView(input, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = styles.dp(12) })
    }
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun label(value: String) = styles.label(value, 14f, palette.muted).apply { setPadding(0, styles.dp(12), 0, styles.dp(12)) }
    private fun input(title: Int, variation: Int) = EditText(activity).apply {
        id = View.generateViewId(); hint = activity.getString(title); contentDescription = hint; setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or variation; textSize = 14f
        setTextColor(palette.foreground); setHintTextColor(palette.muted)
        setPadding(styles.dp(12), styles.dp(12), styles.dp(12), styles.dp(12)); minHeight = styles.dp(48)
        background = styles.shape(styles.panel, border = palette.divider)
    }
    private fun button(title: Int, click: () -> Unit) = action(activity.getString(title), click)
    private fun action(title: String, click: () -> Unit): TextView = styles.label(title, 14f, palette.primary).apply {
        minHeight = styles.dp(48); setPadding(styles.dp(12), styles.dp(12), styles.dp(12), styles.dp(12))
        background = styles.touch(); isFocusable = true; setOnClickListener { click() }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.className = "android.widget.Button"
            }
        }
    }
    override fun close() { code.text.clear(); scope.cancel() }
}
