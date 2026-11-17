package cn.toside.music.nativeapp.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.online.MusicComment
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CommentsPageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val video = Song(1, Uri.EMPTY, "Monsters", "Katie Sky", 217000, "bili", """{"bvid":"invalid"}""")
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun await(check: () -> Boolean) {
        repeat(200) { var ready = false; main { ready = check() }; if (ready) return; SystemClock.sleep(50) }
        error("Timed out waiting for comments UI")
    }
    private fun start() = ForegroundTestActivities.start(CommentsActivity.intent(context, video).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CommentsActivity
    private fun snapshot(name: String, view: View) {
        if (InstrumentationRegistry.getArguments().getString("commentsScreenshots") != "true") return
        val folder = File(context.getExternalFilesDir(null), "comments-validation").apply { mkdirs() }
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image)); File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    @Test fun phoneTabletRowsWrapAndLikesStayDisplayOnlyWhileTabsAndRefreshWork() {
        val activity = start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var content: CommentsContent? = null
        try {
            await { !activity.model.state.value.loading }
            CoverHttpFixture().use { images ->
                var selected: Boolean? = null; var refreshed = 0
                val comments = (1..12).map { index -> MusicComment("$index", if (index == 1) "音乐爱好者" else "听友 $index",
                    if (index == 1) "有些旋律听过很多次，依然会在某个瞬间想起。\n这是一条包含换行的长评论，用于检查手机和平板上的自然换行与阅读间距。" else "又听了一遍，还是很喜欢这首歌。",
                    "1700000000", if (index == 1) 5080 else index.toLong(), avatar = "${images.address}/avatar-$index",
                    replies = if (index == 1) listOf(MusicComment("child", "回复用户", "楼中楼也保留头像、时间与点赞数", "1700000000", 32,
                        avatar = "${images.address}/avatar-child")) else emptyList()) }
                val source = video.copy(source = "kw", sourceInfo = "{}")
                main {
                    content = CommentsContent(activity, scope, UiPalette(activity, NativeThemes(activity).active()), null,
                        {}, { selected = it }, { refreshed++ }, {}, { _, _ -> })
                    activity.setContentView(content)
                    val root = requireNotNull(content)
                    root.render(CommentsState(source, source, items = comments, totals = mapOf(true to 344L), page = 1, hasMore = false))
                    for ((width, height) in listOf(390 to 844, 1024 to 700)) {
                        val density = root.resources.displayMetrics.density
                        repeat(3) {
                            root.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
                            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                        }
                        val body = root.findViewWithTag<TextView>("comment-body-1")
                        val likes = root.findViewWithTag<TextView>("comment-likes-1")
                        assertTrue(body.lineCount > 1); assertEquals("5080", likes.text.toString())
                        assertFalse(likes.isClickable); assertFalse(likes.isFocusable); assertFalse(likes.performClick())
                        val childLikes = root.findViewWithTag<TextView>("comment-likes-child")
                        assertEquals("32", childLikes.text.toString()); assertFalse(childLikes.isClickable)
                        assertEquals("回复用户", root.findViewWithTag<TextView>("comment-author-child").text.toString())
                        assertTrue(likes.left >= 0 && likes.right <= (likes.parent as View).width)
                        assertTrue(descendants(root).none { it is EditText })
                        assertTrue(root.findViewWithTag<TextView>("comments-hot").text.contains("344"))
                        snapshot("$width-comments", root)
                    }
                    root.findViewWithTag<View>("comments-latest").performClick(); assertEquals(false, selected)
                    root.findViewWithTag<View>("comments-refresh").performClick(); assertEquals(1, refreshed)
                }
                await { descendants(requireNotNull(content)).filterIsInstance<ImageView>().any { it.drawable is BitmapDrawable } }
                main { snapshot("tablet-comments-avatars", requireNotNull(content)) }
            }
        } finally { main { content?.close(); activity.finish() }; scope.cancel() }
    }
    @Test fun loadingFailureEmptyAndFallbackSourceHaveDistinctStates() {
        val activity = start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var content: CommentsContent? = null
        try {
            await { !activity.model.state.value.loading }
            main {
                content = CommentsContent(activity, scope, UiPalette(activity, NativeThemes(activity).active()), null, {}, {}, {}, {}, { _, _ -> })
                val root = requireNotNull(content); activity.setContentView(root)
                val requested = video.copy(source = "tx")
                root.render(CommentsState(requested, loading = true))
                assertEquals(View.GONE, root.findViewWithTag<View>("comments-retry").visibility)
                root.render(CommentsState(requested, error = "fixture error"))
                assertEquals(View.VISIBLE, root.findViewWithTag<View>("comments-retry").visibility)
                assertTrue(root.findViewWithTag<TextView>("comments-status").text.contains("fixture error"))
                root.render(CommentsState(requested, page = 1, hasMore = false))
                assertEquals(activity.getString(R.string.comments_empty), root.findViewWithTag<TextView>("comments-status").text.toString())
                root.render(CommentsState(requested, requested.copy(source = "kw"), items = listOf(MusicComment("1", "name", "text")), page = 1))
                assertEquals(View.VISIBLE, root.findViewWithTag<View>("comments-source").visibility)
                assertTrue(root.findViewWithTag<TextView>("comments-source").text.contains(UiPreferences.sourceName(activity, "kw")))
                assertEquals(CommentsContent.formatDate("1700000000000"), CommentsContent.formatDate("1700000000"))
                assertTrue(CommentsContent.formatDate("1700000000").matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")))
            }
        } finally { main { content?.close(); activity.finish() }; scope.cancel() }
    }
    @Test fun readOnlyRowsKeepPaginationAndRepliesTappable() {
        val activity = start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var content: CommentsContent? = null
        var loaded = 0; var replied: MusicComment? = null
        fun tap(tag: String) {
            main {
                val view = requireNotNull(content).findViewWithTag<View>(tag)
                val point = IntArray(2); val origin = IntArray(2)
                assertTrue(view.isShown); view.getLocationOnScreen(point)
                activity.window.decorView.getLocationOnScreen(origin)
                point[0] += view.width / 2 - origin[0]; point[1] += view.height / 2 - origin[1]
                val now = SystemClock.uptimeMillis()
                // Dispatch through the full window hierarchy without OEM event-injection permission.
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 80 else 0, action, point[0].toFloat(), point[1].toFloat(), 0)
                    try { activity.window.decorView.dispatchTouchEvent(event) } finally { event.recycle() }
                }
            }
            instrumentation.waitForIdleSync()
        }
        try {
            await { !activity.model.state.value.loading }
            val comment = MusicComment("touch", "name", "Read only", likes = 42, replyCount = 3)
            main {
                content = CommentsContent(activity, scope, UiPalette(activity, NativeThemes(activity).active()), null,
                    {}, {}, {}, { loaded++ }, { _, item -> replied = item })
                activity.setContentView(content)
                requireNotNull(content).render(CommentsState(video, items = listOf(comment), page = 1))
            }
            await { content?.findViewWithTag<View>("comments-more")?.let { it.isShown && it.width > 0 && it.height > 0 } == true }
            tap("comment-likes-touch")
            main { assertEquals(0, loaded); assertNull(replied); assertEquals("42", content?.findViewWithTag<TextView>("comment-likes-touch")?.text.toString()) }
            tap("comment-replies-touch")
            main { assertEquals(comment, replied) }
            tap("comments-more")
            main { assertEquals(1, loaded) }
        } finally { main { content?.close(); activity.finish() }; scope.cancel() }
    }
    @Test fun commentsUseANonFloatingActivityAndBackRestoresTheSameHomePage() {
        val host = ForegroundTestActivities.start(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        await { host.settingsPlayer() != null }
        main {
            MainActivity::class.java.getDeclaredMethod("openSearchPage", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(host, false)
            (MainActivity::class.java.getDeclaredField("search").apply { isAccessible = true }.get(host) as EditText).setText("保留搜索")
            (MainActivity::class.java.getDeclaredField("suggestionJob").apply { isAccessible = true }.get(host) as? Job)?.cancel()
        }
        val monitor = instrumentation.addMonitor(CommentsActivity::class.java.name, null, false)
        var activity: CommentsActivity? = null
        try {
            main { showComments(host, video) }
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 10000) as CommentsActivity
            val page = requireNotNull(activity)
            await { page.window.decorView.findViewWithTag<View>("comments-page")?.isShown == true }
            main {
                val floating = TypedValue()
                page.theme.resolveAttribute(android.R.attr.windowIsFloating, floating, true)
                assertEquals(0, floating.data)
                page.window.decorView.findViewWithTag<View>("page-back").performClick()
            }
            await { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).contains(host) }
            main { assertEquals("保留搜索", (MainActivity::class.java.getDeclaredField("search").apply { isAccessible = true }.get(host) as EditText).text.toString()) }
        } finally {
            instrumentation.removeMonitor(monitor)
            main { activity?.finish(); host.finish() }
        }
    }
    @Test fun darkEnglishLargeTextKeepsTheHeaderAndCountsWithinThePhoneWindow() {
        val preferences = context.getSharedPreferences("app-settings", 0)
        val keys = listOf("common.langId", "common.fontScale", "theme.id", "common.isAutoTheme")
        val before = keys.associateWith { preferences.all[it] }
        preferences.edit().putString("common.langId", "en").putString("common.fontScale", "1.6")
            .putString("theme.id", "black").putBoolean("common.isAutoTheme", false).commit()
        var activity: CommentsActivity? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var content: CommentsContent? = null
        try {
            activity = start()
            val page = activity
            await { !page.model.state.value.loading }
            main {
                val palette = UiPalette(page, NativeThemes(page).active())
                assertTrue(palette.theme.dark)
                content = CommentsContent(page, scope, palette, null, {}, {}, {}, {}, { _, _ -> })
                val root = requireNotNull(content); page.setContentView(root)
                root.render(CommentsState(video.copy(source = "kw"), items = listOf(MusicComment("large", "A listener with a very long nickname",
                    "A longer comment that should wrap naturally at a large accessibility font size.\nThe next line stays readable.", "1700000000", 12345678)),
                    totals = mapOf(true to 123456789L, false to 123456789L), page = 1, hasMore = false))
                val density = root.resources.displayMetrics.density
                repeat(3) {
                    root.measure(View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((780 * density).toInt(), View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                }
                for (tag in listOf("comments-hot", "comments-latest", "comments-refresh")) {
                    val control = root.findViewWithTag<View>(tag)
                    assertTrue(control.left >= 0 && control.right <= (control.parent as View).width)
                }
                val likes = root.findViewWithTag<TextView>("comment-likes-large")
                assertEquals(palette.muted, likes.currentTextColor); assertFalse(likes.isClickable)
                assertTrue(root.findViewWithTag<TextView>("comment-body-large").lineCount > 1)
                snapshot("phone-dark-english-large-comments", root)
            }
        } finally {
            main { content?.close(); activity?.finish() }; scope.cancel()
            preferences.edit().apply { before.forEach { (key, value) -> when (value) {
                is String -> putString(key, value); is Boolean -> putBoolean(key, value); else -> remove(key)
            } } }.commit()
        }
    }
    @Test fun rotationKeepsTheSameViewModelAndSelectedTab() {
        var activity = start()
        val monitor = instrumentation.addMonitor(CommentsActivity::class.java.name, null, false)
        try {
            await { !activity.model.state.value.loading }
            val model = activity.model
            main { model.select(false) }
            await { !model.state.value.loading }
            main { activity.recreate() }
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 10000) as CommentsActivity
            val recreated = activity
            await { recreated.window.decorView.findViewWithTag<View>("comments-latest")?.isSelected == true }
            assertSame(model, recreated.model); assertFalse(recreated.model.state.value.hot)
        } finally { instrumentation.removeMonitor(monitor); main { activity.finish() } }
    }
    @Test fun longCommentAndReplyTitlesUseOneLineAndRefreshSharesTheHeader() {
        val activity = start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var content: CommentsContent? = null
        try {
            await { !activity.model.state.value.loading }
            main {
                for (reply in listOf(null, "很长的回复用户名".repeat(12))) {
                    var refreshes = 0
                    content?.close()
                    content = CommentsContent(activity, scope, UiPalette(activity, NativeThemes(activity).active()), reply, {}, {}, { refreshes++ }, {}, { _, _ -> })
                    val root = requireNotNull(content); activity.setContentView(root)
                    root.render(CommentsState(video.copy(title = "【戴荃】《悟空》经典现场，如果一去不回，便一去不回！".repeat(4)), page = 1, hasMore = false))
                    val density = root.resources.displayMetrics.density
                    repeat(3) {
                        root.measure(View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((780 * density).toInt(), View.MeasureSpec.EXACTLY))
                        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                    }
                    val title = root.findViewWithTag<TextView>("comments-title")
                    val header = root.findViewWithTag<View>("comments-header")
                    val refresh = root.findViewWithTag<View>("comments-refresh")
                    assertEquals(1, title.maxLines); assertEquals(1, title.lineCount)
                    assertSame(header, refresh.parent); assertTrue(header.height <= 57 * density)
                    assertEquals(if (reply == null) View.VISIBLE else View.GONE, root.findViewWithTag<View>("comments-tabs").visibility)
                    refresh.performClick(); assertEquals(1, refreshes)
                    snapshot(if (reply == null) "single-line-comment-header" else "single-line-reply-header", root)
                }
            }
        } finally { main { content?.close(); activity.finish() }; scope.cancel() }
    }
}
