package cn.toside.music.nativeapp.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Space
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.Artwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class CommentImageState(val bitmap: Bitmap? = null, val loading: Boolean = false, val failed: Boolean = false)
internal class CommentImageModel(private val artwork: Artwork, private val url: String) : ViewModel() {
    private val mutable = MutableStateFlow(CommentImageState())
    val state = mutable.asStateFlow()
    private var request: Job? = null
    init { load() }
    fun load() {
        if (request?.isActive == true || mutable.value.bitmap != null) return
        mutable.value = CommentImageState(loading = true)
        request = viewModelScope.launch {
            try {
                val image = artwork.loadUrl(url, maxDimension = 4096)
                mutable.value = CommentImageState(bitmap = image, failed = image == null)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = CommentImageState(failed = true) }
        }
    }
}

/** An internal image page, with the comment list kept underneath in the activity back stack. */
class CommentImageActivity : FragmentActivity() {
    private lateinit var image: ZoomableImageView
    internal val model: CommentImageModel by lazy {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CommentImageModel(Artwork(applicationContext), intent.getStringExtra("image").orEmpty()) as T
        })[CommentImageModel::class.java]
    }
    override fun attachBaseContext(base: Context) = super.attachBaseContext(UiPreferences.context(base))
    override fun onCreate(savedInstanceState: Bundle?) {
        val theme = NativeThemes(this).active()
        setTheme(if (theme.dark) R.style.NativeDark else R.style.NativeLight)
        super.onCreate(savedInstanceState)
        val palette = UiPalette(this, theme)
        val styles = LxStyles(this, palette, Typeface.createFromAsset(assets, "fonts/icomoon.ttf"))
        val root = LinearLayout(this).apply { tag = "comment-image-page"; orientation = LinearLayout.VERTICAL; setBackgroundColor(palette.surface) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(styles.dp(4), styles.dp(4), styles.dp(4), styles.dp(4)) }
        header.addView(styles.backButton { finish() }, LinearLayout.LayoutParams(styles.dp(48), styles.dp(48)))
        header.addView(styles.label(getString(R.string.comments_image_title), 19f).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Space(this), LinearLayout.LayoutParams(styles.dp(48), styles.dp(48)))
        root.addView(header)
        val body = FrameLayout(this)
        image = ZoomableImageView(this)
        if (savedInstanceState?.containsKey("image-zoom") == true) image.restore(ImageViewport.Position(
            savedInstanceState.getFloat("image-zoom", 1f), savedInstanceState.getFloat("image-x", 0.5f), savedInstanceState.getFloat("image-y", 0.5f)))
        body.addView(image, FrameLayout.LayoutParams(-1, -1))
        val progress = ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(palette.primary) }
        body.addView(progress, FrameLayout.LayoutParams(styles.dp(40), styles.dp(40), Gravity.CENTER))
        val error = LinearLayout(this).apply { tag = "comment-image-error"; orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        error.addView(styles.label(getString(R.string.comments_image_error), 15f, palette.muted))
        error.addView(styles.label(getString(R.string.ui_b8784c8dd563), 15f, palette.primary).apply {
            tag = "comment-image-retry"; gravity = Gravity.CENTER; minHeight = styles.dp(48); minWidth = styles.dp(96)
            background = styles.touch(); isFocusable = true; setOnClickListener { model.load() }
        })
        body.addView(error, FrameLayout.LayoutParams(-1, -1))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f)); palette.textStyle(root)
        setContentView(root); ScreenInsets.install(window, root); ScreenInsets.styleSystemBars(window, theme.dark)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { state ->
            state.bitmap?.let(image::image)
            progress.visibility = if (state.loading) View.VISIBLE else View.GONE
            error.visibility = if (state.failed) View.VISIBLE else View.GONE
        } } }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        val position = image.viewport.position()
        outState.putFloat("image-zoom", position.zoom); outState.putFloat("image-x", position.centerX); outState.putFloat("image-y", position.centerY)
        super.onSaveInstanceState(outState)
    }
    companion object {
        internal fun intent(context: Context, url: String) = Intent(context, CommentImageActivity::class.java)
            .putExtra("image", requireNotNull(commentImageUrl(url)))
    }
}
