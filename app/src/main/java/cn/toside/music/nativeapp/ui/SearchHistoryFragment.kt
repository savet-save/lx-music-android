package cn.toside.music.nativeapp.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.online.SearchHistory
import cn.toside.music.nativeapp.online.SearchSuggestions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SearchHistoryFragment : Fragment() {
    private val host get() = requireActivity() as MainActivity
    private val source get() = requireNotNull(requireArguments().getString(SOURCE))
    private val history by lazy { SearchHistory(requireContext()) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        HistoryPage(savedInstanceState)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        (view as HistoryPage).start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        (view as? HistoryPage)?.save(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        view?.apply { alpha = 1f; scaleX = 1f; scaleY = 1f }
    }

    private inner class HistoryPage(savedState: Bundle?) : LinearLayout(requireContext()) {
        private val palette = UiPalette(context, NativeThemes(context).active())
        private val styles = LxStyles(context, palette, Typeface.createFromAsset(context.assets, "fonts/icomoon.ttf"))
        private val inset = styles.dp(if (resources.configuration.screenWidthDp >= 600) 24 else 16)
        private var showingTrending = savedState?.getBoolean(TRENDING_SELECTED) == true
        private var trendingTerms: List<String>? = savedState?.getStringArrayList(TRENDING_TERMS)
        private var trendingError: String? = null
        private var trendingJob: Job? = null
        private val termsAdapter = TermAdapter()
        private val back = styles.backButton { host.closeSearchHistory() }
        private val clear = action(getString(R.string.ui_1ef3de06b32e), "search-history-clear") {
            history.clear()
            render()
        }.apply { contentDescription = getString(R.string.ui_02c0ab8fbd15) }
        private val historyTab = action(getString(R.string.search_history_tab), "search-history-tab") { select(false) }
        private val trendingTab = action(getString(R.string.ui_2a61478eeecf, host.sourceLabel(source)), "search-trending-tab") {
            select(true)
        }
        private val hint = styles.label(getString(R.string.search_history_remove_hint), 13f, palette.muted).apply {
            maxLines = 2
            setPaddingRelative(inset, styles.dp(4), inset, styles.dp(4))
        }
        private val message = styles.label("", 14f, palette.muted).apply {
            tag = "search-history-empty"
            gravity = Gravity.CENTER
            maxLines = 4
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        private val retry = action(getString(R.string.ui_b8784c8dd563), "search-trending-retry", ::loadTrending)
        private val empty = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPaddingRelative(inset, 0, inset, 0)
            addView(message, LayoutParams(-1, -2))
            addView(retry, LayoutParams(-2, -2))
        }
        private val list = ListView(context).apply {
            tag = "search-history-list"
            divider = null
            selector = styles.touch()
            setDrawSelectorOnTop(true)
            adapter = termsAdapter
            emptyView = empty
            setOnItemClickListener { _, _, position, _ -> host.searchFromHistory(termsAdapter.getItem(position)) }
            setOnItemLongClickListener { _, _, position, _ ->
                if (showingTrending) false else {
                    history.remove(termsAdapter.getItem(position))
                    render()
                    true
                }
            }
        }

        init {
            tag = "search-history-page"
            orientation = VERTICAL
            isClickable = true
            setBackgroundColor(palette.surface)
            ViewCompat.setAccessibilityPaneTitle(this, getString(R.string.ui_cad84b75d661))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = styles.dp(56)
                setPaddingRelative(styles.dp(4), 0, styles.dp(8), 0)
                addView(back, LayoutParams(styles.dp(48), styles.dp(48)))
                addView(styles.label(getString(R.string.ui_cad84b75d661), 19f).apply {
                    setTypeface(null, Typeface.BOLD)
                    maxLines = 2
                    isAccessibilityHeading = true
                    setPaddingRelative(styles.dp(8), 0, styles.dp(8), 0)
                }, LayoutParams(0, -2, 1f))
                addView(clear, LayoutParams(-2, -2))
            }, LayoutParams(-1, -2))
            addView(View(context).apply { setBackgroundColor(palette.divider) }, LayoutParams(-1, styles.dp(1)))
            addView(LinearLayout(context).apply {
                setPaddingRelative(inset, styles.dp(8), inset, styles.dp(4))
                addView(historyTab, LayoutParams(0, -2, 1f).apply { marginEnd = styles.dp(8) })
                addView(trendingTab, LayoutParams(0, -2, 1f))
            }, LayoutParams(-1, -2))
            addView(hint, LayoutParams(-1, -2))
            addView(FrameLayout(context).apply {
                addView(list, FrameLayout.LayoutParams(-1, -1))
                addView(empty, FrameLayout.LayoutParams(-1, -1))
            }, LayoutParams(-1, 0, 1f))
            palette.textStyle(this)
        }

        fun start() {
            render()
            if (showingTrending) loadTrending()
            if (!isInTouchMode) back.requestFocus()
        }

        fun save(state: Bundle) {
            state.putBoolean(TRENDING_SELECTED, showingTrending)
            trendingTerms?.let { state.putStringArrayList(TRENDING_TERMS, ArrayList(it)) }
        }

        private fun select(trending: Boolean) {
            if (showingTrending != trending) {
                showingTrending = trending
                render()
                list.setSelection(0)
            }
            if (trending) loadTrending()
        }

        private fun loadTrending() {
            if (trendingTerms != null || trendingJob?.isActive == true) return
            trendingError = null
            render()
            trendingJob = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    trendingTerms = SearchSuggestions().hot(source)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    trendingError = getString(R.string.ui_bf00a3daba4a, e.localizedMessage.orEmpty())
                }
                if (showingTrending) render()
            }
        }

        private fun render() {
            val terms = if (showingTrending) trendingTerms.orEmpty() else history.list()
            termsAdapter.submit(terms)
            val showHistoryActions = !showingTrending && terms.isNotEmpty()
            clear.visibility = if (showHistoryActions) View.VISIBLE else View.GONE
            hint.visibility = clear.visibility
            retry.visibility = if (showingTrending && trendingError != null) View.VISIBLE else View.GONE
            message.text = when {
                !showingTrending -> getString(R.string.search_history_empty)
                trendingError != null -> trendingError
                trendingTerms == null -> getString(R.string.ui_fa0ead846beb)
                else -> getString(R.string.ui_50933f24c7aa)
            }
            styleTab(historyTab, !showingTrending)
            styleTab(trendingTab, showingTrending)
        }

        private fun styleTab(tab: TextView, selected: Boolean) {
            tab.isSelected = selected
            tab.setTextColor(if (selected) palette.primary else palette.foreground)
            tab.background = if (selected) styles.touch(styles.selected) else styles.touch()
        }

        private fun action(label: String, id: String, click: () -> Unit) = styles.label(label, 14f, palette.primary).apply {
            tag = id
            minHeight = styles.dp(48)
            maxLines = 2
            isFocusable = true
            gravity = Gravity.CENTER
            setPaddingRelative(styles.dp(8), styles.dp(4), styles.dp(8), styles.dp(4))
            background = styles.touch()
            setOnClickListener { click() }
        }

        private inner class TermAdapter : BaseAdapter() {
            private var terms = emptyList<String>()
            fun submit(values: List<String>) {
                if (terms == values) return
                terms = values
                notifyDataSetChanged()
            }
            override fun getCount() = terms.size
            override fun getItem(position: Int) = terms[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View =
                ((recycled as? TextView) ?: styles.label("", 16f).apply {
                    minHeight = styles.dp(48)
                    maxLines = 2
                    setPaddingRelative(inset, styles.dp(6), inset, styles.dp(6))
                    palette.textStyle(this)
                }).apply { text = getItem(position) }
        }
    }

    internal companion object {
        const val TAG = "search-history"
        private const val SOURCE = "source"
        private const val TRENDING_SELECTED = "trending-selected"
        private const val TRENDING_TERMS = "trending-terms"

        fun create(source: String) = SearchHistoryFragment().apply {
            arguments = Bundle().apply { putString(SOURCE, source) }
        }
    }
}
