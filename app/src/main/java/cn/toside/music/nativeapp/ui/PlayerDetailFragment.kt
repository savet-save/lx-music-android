package cn.toside.music.nativeapp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

/** Full-window player in the Activity's back stack, above the retained browsing page. */
class PlayerDetailFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        (requireActivity() as MainActivity).createPlayerDetailView(
            savedInstanceState?.takeIf { it.containsKey(LYRICS) }?.getBoolean(LYRICS))

    override fun onResume() {
        super.onResume()
        // A cancelled predictive pop can leave the restored view at its last seeked transform.
        view?.apply { alpha = 1f; scaleX = 1f; scaleY = 1f }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(LYRICS, (requireActivity() as MainActivity).playerLyricsVisible())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        (requireActivity() as MainActivity).releasePlayerDetailView(view)
        super.onDestroyView()
    }

    internal companion object {
        const val TAG = "player-details"
        private const val LYRICS = "show-lyrics"
    }
}
