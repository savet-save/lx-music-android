package cn.toside.music.nativeapp.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhoneNavigationDrawerTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun field(activity: MainActivity, name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun invoke(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun root(activity: MainActivity) = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
    private fun start() = (instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity).also { instrumentation.waitForIdleSync() }
    private fun measure(view: View, width: Int, height: Int) {
        repeat(2) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
        }
    }
    private fun touch(root: View, fromX: Float, fromY: Float, toX: Float = fromX, toY: Float = fromY) {
        val time = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            val event = MotionEvent.obtain(time, time + index * 30, action, if (index == 0) fromX else toX, if (index == 0) fromY else toY, 0)
            root.dispatchTouchEvent(event); event.recycle()
        }
    }
    private fun snapshot(name: String, view: View) {
        if (InstrumentationRegistry.getArguments().getString("navigationScreenshots") != "true") return
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "navigation-validation").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun drawerUsesSelectedSideBlocksPageSwipesAndLeavesMiniPlayerInteractive() {
        val preferences = instrumentation.targetContext.getSharedPreferences("app-settings", 0)
        val keys = listOf("common.drawerLayoutPosition", "common.homePageScroll", "search.isShowHotSearch")
        val saved = keys.associateWith { preferences.all[it] }
        preferences.edit().putBoolean("common.homePageScroll", true).putBoolean("search.isShowHotSearch", false).commit()
        val activity = start()
        try {
            listOf(false, true).forEach { right ->
                preferences.edit().putString("common.drawerLayoutPosition", if (right) "right" else "left").commit()
                var playerClicks = 0
                instrumentation.runOnMainSync {
                    MainActivity::class.java.getDeclaredField("uiLayout").apply { isAccessible = true }.set(activity, AdaptiveLayout(360, 800))
                    invoke(activity, "buildUi")
                    val root = root(activity); val density = activity.resources.displayMetrics.density
                    val width = (360 * density).toInt(); val height = (800 * density).toInt()
                    measure(root, width, height)
                    val drawer = field(activity, "navigationDrawer") as HomeDrawerLayout
                    drawer.open(false); measure(root, width, height)
                    val panel = root.findViewWithTag<View>("home-drawer-panel")
                    assertTrue(drawer.isOpen)
                    assertTrue(panel.width < drawer.width); assertEquals(drawer.height, panel.height)
                    assertEquals(if (right) drawer.width - panel.width else 0, panel.left)
                    val miniBar = field(activity, "miniBar") as View
                    assertTrue(drawer.bottom <= miniBar.top)
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, drawer.getChildAt(0).importantForAccessibility)

                    val play = field(activity, "toggle") as View
                    play.setOnClickListener { playerClicks++ }
                    val playPosition = IntArray(2); val rootPosition = IntArray(2)
                    play.getLocationOnScreen(playPosition); root.getLocationOnScreen(rootPosition)
                    touch(root, (playPosition[0] - rootPosition[0] + play.width / 2).toFloat(), (playPosition[1] - rootPosition[1] + play.height / 2).toFloat())
                    snapshot(if (right) "phone-drawer-right" else "phone-drawer-left", root)
                }
                instrumentation.waitForIdleSync()
                assertEquals(1, playerClicks)
                instrumentation.runOnMainSync {
                    val root = root(activity); val density = activity.resources.displayMetrics.density
                    val drawer = field(activity, "navigationDrawer") as HomeDrawerLayout
                    measure(root, (360 * density).toInt(), (800 * density).toInt())
                    val panel = root.findViewWithTag<View>("home-drawer-panel")
                    assertTrue(drawer.isOpen)
                    val oldPage = field(activity, "onlineMode")
                    val y = (drawer.top + panel.height / 3).toFloat()
                    val from = (if (right) panel.left + panel.width / 3 else panel.width * 2 / 3).toFloat()
                    val to = from + (if (right) 100 else -100) * density
                    touch(root, from, y, to, y)
                    assertEquals("An edgeward swipe closes the drawer", View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, drawer.getChildAt(0).importantForAccessibility)
                    drawer.close(false)
                    assertFalse(drawer.isOpen); assertEquals(oldPage, field(activity, "onlineMode"))

                    drawer.open(false)
                    @Suppress("UNCHECKED_CAST") val navigation = field(activity, "navigationItems") as Map<Int, TextView>
                    (navigation.getValue(1).parent as View).performClick()
                    drawer.close(false)
                    assertEquals(true, field(activity, "onlineMode")); assertTrue(navigation.getValue(1).isSelected)
                    drawer.open(false)
                    val scrim = root.findViewWithTag<View>("home-drawer-scrim")
                    scrim.performClick()
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, drawer.getChildAt(0).importantForAccessibility)
                    drawer.open(false)
                    assertTrue("A drawer can reopen during its closing animation", drawer.isOpen)
                    drawer.close(false)
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, drawer.getChildAt(0).importantForAccessibility)
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            preferences.edit().apply { saved.forEach { (key, value) -> when (value) {
                is String -> putString(key, value); is Boolean -> putBoolean(key, value); else -> remove(key)
            } } }.commit()
        }
    }

    @Test fun phoneDrawerHandlesSystemBackAndSurvivesRecreation() {
        assumeTrue(instrumentation.targetContext.resources.configuration.screenWidthDp < 600)
        var activity = start()
        try {
            instrumentation.runOnMainSync { invoke(activity, "showNavigation") }
            SystemClock.sleep(300)
            instrumentation.runOnMainSync { snapshot("phone-drawer-actual", root(activity)) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            SystemClock.sleep(300); instrumentation.waitForIdleSync()
            assertFalse((field(activity, "navigationDrawer") as HomeDrawerLayout).isOpen)
            assertFalse(activity.isFinishing)
            instrumentation.runOnMainSync { invoke(activity, "showNavigation") }
            SystemClock.sleep(300)
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            instrumentation.runOnMainSync { activity.recreate() }
            val recreated = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
            instrumentation.removeMonitor(monitor)
            assertNotNull(recreated); activity = recreated as MainActivity
            instrumentation.waitForIdleSync(); SystemClock.sleep(300)
            assertTrue((field(activity, "navigationDrawer") as HomeDrawerLayout).isOpen)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
