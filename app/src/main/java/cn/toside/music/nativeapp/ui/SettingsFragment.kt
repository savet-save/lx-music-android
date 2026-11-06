package cn.toside.music.nativeapp.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope

/** A full settings screen in the activity back stack, with work owned by its view lifecycle. */
class SettingsFragment : Fragment() {
    private var page: SettingsPage? = null
    private var pageState: Bundle? = null
    private var preferences: SharedPreferences? = null
    private val appearanceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        val host = activity as? MainActivity ?: return@OnSharedPreferenceChangeListener
        if (key in SettingsPage.themeKeys) host.refreshSettingsAppearance(recreate = false)
        else if (key in SettingsPage.recreationKeys) host.refreshSettingsAppearance(recreate = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageState = savedInstanceState?.getBundle(PAGE_STATE) ?: arguments?.getBundle(PAGE_STATE)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val host = requireActivity() as MainActivity
        val controller = SettingsPage(host, lifecycleScope, { host.settingsPlayer() }, host.settingsActions(),
            AdaptiveLayout(resources.configuration.screenWidthDp, resources.configuration.screenHeightDp), pageState,
            onClose = host::closeSettings, refresh = host::refreshSettingsPreferences)
        page = controller
        return FrameLayout(requireContext()).apply {
            tag = "settings-fragment"; isClickable = true; isFocusableInTouchMode = true
            setBackgroundColor(controller.backgroundColor)
            addView(controller.createContent(), FrameLayout.LayoutParams(-1, -1))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ScreenInsets.install(requireActivity().window, view)
        preferences = requireContext().getSharedPreferences("app-settings", Context.MODE_PRIVATE).also {
            it.registerOnSharedPreferenceChangeListener(appearanceListener)
        }
        view.requestFocus()
    }

    internal fun savePageState(): Bundle? = page?.saveState() ?: pageState
    internal fun refreshSources() { page?.refreshSources() }
    internal fun applyTheme() {
        val controller = page ?: return
        val root = view as? FrameLayout ?: return
        val content = controller.applyTheme()
        root.setBackgroundColor(controller.backgroundColor)
        root.removeAllViews(); root.addView(content, FrameLayout.LayoutParams(-1, -1))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle(PAGE_STATE, savePageState())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        preferences?.unregisterOnSharedPreferenceChangeListener(appearanceListener); preferences = null
        pageState = page?.saveState() ?: pageState
        page?.close(); page = null
        super.onDestroyView()
    }

    companion object {
        internal const val TAG = "settings"
        private const val PAGE_STATE = "settings-page-state"
        internal fun create(state: Bundle?) = SettingsFragment().apply {
            arguments = Bundle().apply { putBundle(PAGE_STATE, state) }
        }
    }
}
