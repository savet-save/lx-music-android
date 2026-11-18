package cn.toside.music.nativeapp.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.player.PlaybackService
import cn.toside.music.nativeapp.source.UserSourceLibrary
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MultiSourceSettingsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private suspend fun waitFor(check: suspend () -> Boolean) = withTimeout(15_000) { while (!check()) delay(50) }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun groupsLimitTogglesAndOrderingWorkOnPhoneAndTablet() = runBlocking {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val field = MainActivity::class.java.getDeclaredField("service").apply { isAccessible = true }
        waitFor { field.get(activity) != null }
        val player = field.get(activity) as PlaybackService
        val before = player.sourceCatalog()
        val added = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            repeat(4) { index ->
                val script = "/**\n * @name 测试音源 ${'A' + index}\n */\n" +
                    "// fixture ${System.nanoTime()}\nlx.send(lx.EVENT_NAMES.inited,{sources:{}});"
                player.importSource(script); added.add(UserSourceLibrary.metadata(script).id)
            }
            player.setEnabledSources(added.take(3))
            for (layout in listOf(AdaptiveLayout(360, 800), AdaptiveLayout(1024, 768))) {
                lateinit var root: View
                main {
                    root = SettingsPage(activity, scope, { player }, SettingsActions({}, { _, _, _ -> }, {}), layout,
                        Bundle().apply { putString("category", "sources") }) {}.createContent()
                }
                waitFor { var ready = false; main { ready = root.findViewWithTag<View>("source-row-${added[0]}") != null }; ready }
                main {
                    val density = root.resources.displayMetrics.density
                    repeat(2) {
                        root.measure(View.MeasureSpec.makeMeasureSpec((layout.widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec((layout.heightDp * density).toInt(), View.MeasureSpec.EXACTLY))
                        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                    }
                    assertNotNull(root.findViewWithTag<View>("sources-enabled-heading"))
                    assertNotNull(root.findViewWithTag<View>("sources-disabled-heading"))
                    val off = root.findViewWithTag<Switch>("source-enabled-${added[3]}")
                    assertFalse(off.isChecked); assertFalse(off.isEnabled)
                    descendants(root.findViewWithTag("settings-source-list")).forEach { child ->
                        val parent = child.parent as? ViewGroup
                        if (parent != null) assertTrue("${child.tag} fits ${layout.widthDp}", child.left >= 0 && child.right <= parent.width)
                    }
                    if (InstrumentationRegistry.getArguments().getString("adaptiveScreenshots") == "true") {
                        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
                        val folder = File(activity.getExternalFilesDir(null), "multi-source-validation").apply { mkdirs() }
                        File(folder, "${layout.widthDp}-sources.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
                if (layout.widthDp == 360) {
                    main { root.findViewWithTag<View>("source-move-${added[1]}--1").performClick() }
                    waitFor { player.sourceCatalog().enabled == listOf(added[1], added[0], added[2]) }
                    waitFor { var ready = false; main { ready = !root.findViewWithTag<View>("source-move-${added[1]}--1").isEnabled }; ready }
                    main { root.findViewWithTag<Switch>("source-enabled-${added[2]}").performClick() }
                    waitFor { var ready = false; main { ready = root.findViewWithTag<Switch>("source-enabled-${added[3]}").isEnabled }; ready }
                    main { root.findViewWithTag<Switch>("source-enabled-${added[3]}").performClick() }
                    waitFor { player.sourceCatalog().enabled == listOf(added[1], added[0], added[3]) }
                    player.setEnabledSources(added.take(3))
                }
            }
        } finally {
            scope.cancel()
            added.forEach { player.removeSource(it) }
            player.setEnabledSources(before.enabled)
            main { activity.finish() }
        }
    }
}
