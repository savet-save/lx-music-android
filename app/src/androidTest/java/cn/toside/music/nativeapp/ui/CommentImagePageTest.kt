package cn.toside.music.nativeapp.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.SystemClock
import android.util.TypedValue
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cn.toside.music.nativeapp.data.Artwork
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.online.MusicComment
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CommentImagePageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(check: () -> Boolean) {
        repeat(300) { var ready = false; main { ready = check() }; if (ready) return; SystemClock.sleep(50) }
        error("Timed out waiting for comment image")
    }
    private fun start(url: String) = ForegroundTestActivities.start(CommentImageActivity.intent(context, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CommentImageActivity
    private fun snapshot(name: String, view: View) {
        if (InstrumentationRegistry.getArguments().getString("commentsScreenshots") != "true") return
        val folder = File(context.getExternalFilesDir(null), "comments-validation").apply { mkdirs() }
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image)); File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    @Test fun imagesHaveSeparateDecodeSizesAndReuseEachCacheOffline() = runBlocking {
        val server = CoverHttpFixture(1600, 900)
        val url = "${server.address}/cache-${System.nanoTime()}"
        val artwork = Artwork(context)
        try {
            val cover = requireNotNull(artwork.loadUrl(url))
            val thumbnail = requireNotNull(artwork.loadUrl(url, maxDimension = 256))
            val detail = requireNotNull(artwork.loadUrl(url, maxDimension = 4096))
            assertEquals(400, cover.width); assertEquals(225, cover.height)
            assertEquals(200, thumbnail.width); assertEquals(112, thumbnail.height)
            assertEquals(1600, detail.width); assertEquals(900, detail.height)
            assertEquals(3, server.requests.get())
            server.close()
            assertEquals(400, artwork.loadUrl(url)?.width)
            assertEquals(200, artwork.loadUrl(url, maxDimension = 256)?.width)
            assertEquals(1600, artwork.loadUrl(url, maxDimension = 4096)?.width)
        } finally { server.close() }
    }
    @Test fun thumbnailOpensInternalDetailAndBackReturnsToTheSameCommentsPage() {
        val song = Song(1, Uri.EMPTY, "【戴荃】《悟空》经典现场，如果一去不回，便一去不回！", "戴荃", 0, "bili", """{"bvid":"invalid"}""")
        val host = ForegroundTestActivities.start(CommentsActivity.intent(context, song).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CommentsActivity
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val monitor = instrumentation.addMonitor(CommentImageActivity::class.java.name, null, false)
        var content: CommentsContent? = null; var detail: CommentImageActivity? = null
        try {
            await { !host.model.state.value.loading }
            CoverHttpFixture(1600, 900).use { server ->
                val urls = (1..3).map { "${server.address}/thumbnail-$it-${System.nanoTime()}" }
                main {
                    content = CommentsContent(host, scope, UiPalette(host, NativeThemes(host).active()), null, {}, {}, {}, {}, { _, _ -> })
                    host.setContentView(content)
                    requireNotNull(content).render(CommentsState(song, items = listOf(MusicComment("image", "听友", "评论配图", images = listOf("javascript:invalid") + urls)), page = 1, hasMore = false))
                }
                await { content?.findViewWithTag<ImageView>("comment-thumbnail-image")?.drawable is BitmapDrawable }
                main {
                    val root = requireNotNull(content)
                    val thumbnail = root.findViewWithTag<View>("comment-thumbnail-0")
                    assertTrue(thumbnail.width > 0 && thumbnail.width <= 113 * root.resources.displayMetrics.density)
                    snapshot("comment-image-thumbnails", root)
                    thumbnail.performClick()
                }
                detail = instrumentation.waitForMonitorWithTimeout(monitor, 10000) as CommentImageActivity
                val page = requireNotNull(detail)
                await { page.model.state.value.bitmap != null }
                main {
                    assertEquals(context.packageName, page.packageName)
                    val floating = TypedValue(); page.theme.resolveAttribute(android.R.attr.windowIsFloating, floating, true)
                    assertEquals(0, floating.data)
                    val image = page.findViewById<View>(android.R.id.content).findViewWithTag<ZoomableImageView>("comment-image-zoom")
                    assertEquals(1f, image.viewport.zoom, 0f)
                    assertTrue(image.viewport.scaledWidth <= image.width + 1 && image.viewport.scaledHeight <= image.height + 1)
                    assertEquals((image.width - image.viewport.scaledWidth) / 2, image.viewport.x, 0.5f)
                    assertEquals((image.height - image.viewport.scaledHeight) / 2, image.viewport.y, 0.5f)
                    snapshot("comment-image-detail-fit", page.window.decorView)
                    page.window.decorView.findViewWithTag<View>("page-back").performClick()
                }
                await { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).contains(host) }
                main { assertTrue(requireNotNull(content).findViewWithTag<View>("comment-thumbnail-0").isShown) }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            main { detail?.finish(); content?.close(); host.finish() }; scope.cancel()
        }
    }
    @Test fun pinchDragAndRecreationRetainTheImageZoomAndPosition() {
        // A square image overflows both axes after zooming in either phone orientation.
        CoverHttpFixture(1200, 1200).use { server ->
            var activity = start("${server.address}/gestures-${System.nanoTime()}")
            val monitor = instrumentation.addMonitor(CommentImageActivity::class.java.name, null, false)
            try {
                await { activity.model.state.value.bitmap != null && activity.window.decorView.findViewWithTag<View>("comment-image-zoom")?.width?.let { it > 0 } == true }
                val model = activity.model
                var saved: ImageViewport.Position? = null
                main {
                    val image = activity.window.decorView.findViewWithTag<ZoomableImageView>("comment-image-zoom")
                    val cx = image.width / 2f; val cy = image.height / 2f
                    val span = image.width / 5f
                    val down = SystemClock.uptimeMillis()
                    var gestureDown = down
                    fun event(action: Int, time: Long, points: List<Pair<Float, Float>>) {
                        val properties = points.mapIndexed { index, _ -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
                        val coords = points.map { point -> MotionEvent.PointerCoords().apply { x = point.first; y = point.second; pressure = 1f; size = 1f } }.toTypedArray()
                        val touch = MotionEvent.obtain(gestureDown, time, action, points.size, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                        try { image.dispatchTouchEvent(touch) } finally { touch.recycle() }
                    }
                    // Gesture activation spans vary by device. Repeat a natural pinch until both
                    // axes overflow, since an axis whose image still fits must remain centered.
                    for (cycle in 0..2) {
                        if (image.viewport.scaledWidth > image.width && image.viewport.scaledHeight > image.height) break
                        val start = down + cycle * 2000L; gestureDown = start
                        event(MotionEvent.ACTION_DOWN, start, listOf(cx - span / 2 to cy))
                        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), start + 20, listOf(cx - span / 2 to cy, cx + span / 2 to cy))
                        for (step in 1..16) {
                            val half = span / 2 * (1 + step * 0.16f)
                            event(MotionEvent.ACTION_MOVE, start + 20 + step * 20L, listOf(cx - half to cy, cx + half to cy))
                        }
                        val lastHalf = span / 2 * (1 + 16 * 0.16f)
                        event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), start + 360, listOf(cx - lastHalf to cy, cx + lastHalf to cy))
                        event(MotionEvent.ACTION_UP, start + 380, listOf(cx - lastHalf to cy))
                    }
                    assertTrue("Pinch must enlarge the image", image.viewport.zoom > 1.5f)
                    assertTrue("Zoom ${image.viewport.zoom} must overflow both axes of ${image.width} x ${image.height}",
                        image.viewport.scaledWidth > image.width && image.viewport.scaledHeight > image.height)
                    val beforeX = image.viewport.x; val beforeY = image.viewport.y
                    gestureDown = down + 8000
                    event(MotionEvent.ACTION_DOWN, gestureDown, listOf(cx to cy))
                    event(MotionEvent.ACTION_MOVE, gestureDown + 100, listOf(cx - 80 to cy - 80))
                    event(MotionEvent.ACTION_UP, gestureDown + 200, listOf(cx - 80 to cy - 80))
                    assertTrue("Horizontal drag: $beforeX -> ${image.viewport.x}", image.viewport.x < beforeX)
                    assertTrue("Vertical drag: $beforeY -> ${image.viewport.y}", image.viewport.y < beforeY)
                    assertTrue(image.viewport.x <= 0 && image.viewport.x + image.viewport.scaledWidth >= image.width - 1)
                    assertTrue(image.viewport.y <= 0 && image.viewport.y + image.viewport.scaledHeight >= image.height - 1)
                    saved = image.viewport.position(); snapshot("comment-image-detail-zoomed", activity.window.decorView)
                    activity.recreate()
                }
                activity = instrumentation.waitForMonitorWithTimeout(monitor, 10000) as CommentImageActivity
                val page = activity; val position = requireNotNull(saved)
                await { page.window.decorView.findViewWithTag<ZoomableImageView>("comment-image-zoom")?.viewport?.zoom?.let { it > 1.5f } == true }
                main {
                    val image = page.window.decorView.findViewWithTag<ZoomableImageView>("comment-image-zoom")
                    assertSame(model, page.model); assertEquals(1, server.requests.get())
                    assertEquals(position.zoom, image.viewport.zoom, 0.01f)
                    assertEquals(position.centerX, image.viewport.position().centerX, 0.01f)
                    assertEquals(position.centerY, image.viewport.position().centerY, 0.01f)
                    val now = SystemClock.uptimeMillis() + 2000
                    for (time in listOf(now, now + 120)) for ((action, offset) in listOf(MotionEvent.ACTION_DOWN to 0, MotionEvent.ACTION_UP to 30)) {
                        val event = MotionEvent.obtain(time, time + offset, action, image.width / 2f, image.height / 2f, 0)
                        try { image.dispatchTouchEvent(event) } finally { event.recycle() }
                    }
                    assertEquals(1f, image.viewport.zoom, 0f)
                }
            } finally { instrumentation.removeMonitor(monitor); main { activity.finish() } }
        }
    }
    @Test fun loadFailureCanBeRetriedAndUnsupportedUrlsCannotOpen() {
        assertNull(commentImageUrl("javascript:alert(1)")); assertNull(commentImageUrl("file:///sdcard/image.png"))
        assertNull(commentImageUrl("https:")); assertEquals("https://example.com/image.png", commentImageUrl("//example.com/image.png"))
        CoverHttpFixture(600, 1800).use { server ->
            server.failuresRemaining.set(1)
            val activity = start("${server.address}/retry-${System.nanoTime()}")
            try {
                await { activity.model.state.value.failed }
                main { assertTrue(activity.window.decorView.findViewWithTag<View>("comment-image-error").isShown); activity.window.decorView.findViewWithTag<View>("comment-image-retry").performClick() }
                await { activity.model.state.value.bitmap != null }
                main {
                    assertEquals(2, server.requests.get()); assertFalse(activity.window.decorView.findViewWithTag<View>("comment-image-error").isShown)
                    val image = activity.window.decorView.findViewWithTag<ZoomableImageView>("comment-image-zoom")
                    assertTrue(image.viewport.scaledWidth <= image.width + 1 && image.viewport.scaledHeight <= image.height + 1)
                    assertEquals(1f / 3, image.viewport.scaledWidth / image.viewport.scaledHeight, 0.001f)
                }
            } finally { main { activity.finish() } }
        }
    }
}
