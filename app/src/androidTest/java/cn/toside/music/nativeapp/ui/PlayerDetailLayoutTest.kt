package cn.toside.music.nativeapp.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PlayerDetailLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun field(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun invoke(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun rect(root: ViewGroup, child: View) = Rect().also { child.getDrawingRect(it); root.offsetDescendantRectToMyCoords(child, it) }
    private fun snapshot(name: String, content: View) {
        if (InstrumentationRegistry.getArguments().getString("playerScreenshots") != "true") return
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "player-style-validation").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
        content.draw(Canvas(bitmap))
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun phoneAndTabletTransportArtworkAndLyricsFitWithoutOverlap() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val adaptiveField = MainActivity::class.java.getDeclaredField("uiLayout").apply { isAccessible = true }
        val original = adaptiveField.get(activity)
        try {
            repeat(100) {
                if (field(activity, "service") != null) return@repeat
                SystemClock.sleep(50)
            }
            assertNotNull(field(activity, "service"))
            instrumentation.waitForIdleSync()
            listOf(AdaptiveLayout(360, 780), AdaptiveLayout(360, 640), AdaptiveLayout(600, 800),
                AdaptiveLayout(1024, 700), AdaptiveLayout(844, 390)).forEach { layout ->
                instrumentation.runOnMainSync {
                    adaptiveField.set(activity, layout)
                    invoke(activity, "showPlayerDetails")
                    val content = field(activity, "detailContent") as PlayerDetailContent
                    val density = content.resources.displayMetrics.density
                    val width = (layout.widthDp * density).toInt(); val height = (layout.heightDp * density).toInt()
                    repeat(2) {
                        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        content.layout(0, 0, width, height)
                    }
                    val album = content.cover.getChildAt(0)
                    val albumRect = rect(content, album)
                    val footer = content.findViewWithTag<View>("player-footer")
                    val controls = content.findViewWithTag<View>("player-controls")
                    val tools = content.findViewWithTag<View>("player-tools")
                    val seek = rect(content, content.seek)
                    val controlRect = rect(content, controls)
                    assertEquals(album.width, album.height)
                    assertTrue("Artwork remains usable at $layout", album.width >= 100 * density)
                    assertTrue(albumRect.top >= 0 && albumRect.bottom <= footer.top)
                    assertTrue(controlRect.bottom <= height && controlRect.width() > 120 * density)
                    if (layout.splitPlayer) {
                        val lyricRect = rect(content, content.lyrics)
                        assertTrue(albumRect.right < lyricRect.left)
                        assertTrue(lyricRect.width() > width * .4f)
                        val metadata = rect(content, content.metadata)
                        assertEquals(albumRect.left, metadata.left)
                        assertTrue(metadata.top > albumRect.bottom)
                        assertTrue(metadata.bottom <= footer.top)
                        assertTrue("Progress stays outside the right transport controls", seek.right < controlRect.left)
                        assertEquals(View.VISIBLE, content.lyrics.visibility)
                        val active = field(activity, "detailActiveLine") as Int
                        content.lyrics.lines.getChildAt(active)?.let { line ->
                            assertTrue("Current lyrics should be centered after measuring the viewport",
                                abs(line.top + line.height / 2 - content.lyrics.scrollY - content.lyrics.height / 2) <= 2 * density)
                        }
                    } else {
                        val elapsed = rect(content, content.elapsed)
                        val duration = rect(content, content.duration)
                        assertTrue(seek.width() > width * .9f)
                        assertTrue(elapsed.left < duration.left)
                        assertTrue(controlRect.top >= elapsed.bottom)
                        assertTrue(rect(content, tools).top >= controlRect.bottom)
                        assertEquals(View.GONE, content.metadata.visibility)
                        assertEquals(View.GONE, content.lyrics.visibility)
                        assertTrue(content.title.textSize > content.artist.textSize)
                    }
                    snapshot("${layout.widthDp}x${layout.heightDp}-cover", content)
                    content.pageToggle.performClick()
                    assertEquals(if (layout.splitPlayer) View.GONE else View.VISIBLE, content.lyrics.visibility)
                    if (!layout.splitPlayer) {
                        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        content.layout(0, 0, width, height)
                        snapshot("${layout.widthDp}x${layout.heightDp}-lyrics", content)
                    }
                    activity.closePlayerDetails()
                    activity.supportFragmentManager.executePendingTransactions()
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                activity.closePlayerDetails()
                adaptiveField.set(activity, original)
                activity.finish()
            }
        }
    }
}
