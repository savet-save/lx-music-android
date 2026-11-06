package cn.toside.music.nativeapp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment

/** Navigation destination for the shared local, search, chart and playlist song table. */
class MusicPageFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).apply { tag = "music-page"; showContent(this) }

    internal fun refreshContent() { (view as? FrameLayout)?.let(::showContent) }
    private fun showContent(root: FrameLayout) {
        val content = (requireActivity() as MainActivity).musicPageContent()
        (content.parent as? ViewGroup)?.removeView(content)
        root.removeAllViews(); root.addView(content, FrameLayout.LayoutParams(-1, -1))
    }
    override fun onDestroyView() {
        // The Activity's existing music renderer receives service/search updates across destinations.
        // Detach its views so the next destination can display the latest data without a new request.
        (view as? FrameLayout)?.removeAllViews()
        super.onDestroyView()
    }
}
