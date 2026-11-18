package cn.toside.music.nativeapp.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.online.CommentsRepository
import cn.toside.music.nativeapp.online.MusicComment
import kotlinx.coroutines.launch
import org.json.JSONObject

internal fun showComments(activity: Activity, song: Song) { activity.startActivity(CommentsActivity.intent(activity, song)) }

/** A regular page in its own window/back stack; playback stays with the existing service. */
class CommentsActivity : FragmentActivity() {
    private lateinit var content: CommentsContent
    internal val model: CommentsViewModel by lazy {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val song = decodeSong(requireNotNull(intent.getStringExtra("song")))
                return CommentsViewModel(extras.createSavedStateHandle(), song, intent.getStringExtra("reply"),
                    CommentsRepository(applicationContext, song)) as T
            }
        })[CommentsViewModel::class.java]
    }
    override fun attachBaseContext(base: Context) = super.attachBaseContext(UiPreferences.context(base))
    override fun onCreate(savedInstanceState: Bundle?) {
        val theme = NativeThemes(this).active()
        setTheme(if (theme.dark) R.style.NativeDark else R.style.NativeLight)
        super.onCreate(savedInstanceState)
        content = CommentsContent(this, lifecycleScope, UiPalette(this, theme), intent.getStringExtra("reply-name"),
            close = { finish() }, select = { hot -> content.resetScroll(); model.select(hot) },
            refresh = { content.resetScroll(); model.refresh() }, more = { model.loadMore() },
            replies = { song, comment -> startActivity(intent(this, song, comment)) })
        content.restoreListState(model.listState)
        setContentView(content)
        ScreenInsets.install(window, content); ScreenInsets.styleSystemBars(window, theme.dark)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect(content::render) } }
        model.ensureLoaded()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        model.listState = content.saveListState()
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        model.listState = content.saveListState()
        super.onStop()
    }
    override fun onDestroy() { content.close(); super.onDestroy() }

    companion object {
        internal fun intent(context: Context, song: Song, reply: MusicComment? = null) = Intent(context, CommentsActivity::class.java)
            .putExtra("song", JSONObject().put("id", song.id).put("uri", song.uri.toString()).put("title", song.title)
                .put("artist", song.artist).put("duration", song.duration).put("source", song.source)
                .put("info", song.sourceInfo ?: JSONObject.NULL).toString())
            .apply { if (reply != null) { putExtra("reply", reply.id); putExtra("reply-name", reply.name) } }
        private fun decodeSong(value: String): Song = JSONObject(value).let {
            Song(it.getLong("id"), Uri.parse(it.getString("uri")), it.getString("title"), it.getString("artist"), it.getLong("duration"),
                it.getString("source"), if (it.isNull("info")) null else it.getString("info"))
        }
    }
}
