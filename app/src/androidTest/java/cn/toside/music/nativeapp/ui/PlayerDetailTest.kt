package cn.toside.music.nativeapp.ui

import android.app.Dialog
import android.widget.TextView
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerDetailTest {
    @Test fun opensAndClosesPlayerDetails() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val serviceField = MainActivity::class.java.getDeclaredField("service").apply { isAccessible = true }
            repeat(50) {
                if (serviceField.get(activity) != null) return@repeat
                SystemClock.sleep(100)
            }
            assertNotNull(serviceField.get(activity))
            val cover = find(activity.window.decorView, "播放详情")
            assertNotNull(cover)
            instrumentation.runOnMainSync { cover!!.performClick() }
            instrumentation.waitForIdleSync()
            val dialogField = MainActivity::class.java.getDeclaredField("playerDialog").apply { isAccessible = true }
            val dialog = dialogField.get(activity) as? Dialog
            assertTrue(dialog?.isShowing == true)
            val toggleField = MainActivity::class.java.getDeclaredField("detailPageToggle").apply { isAccessible = true }
            val toggle = toggleField.get(activity) as View
            instrumentation.runOnMainSync { toggle.performClick() }
            val scrollField = MainActivity::class.java.getDeclaredField("detailScroll").apply { isAccessible = true }
            assertEquals(View.VISIBLE, (scrollField.get(activity) as View).visibility)
            instrumentation.runOnMainSync { dialog?.dismiss() }
            val openSync = MainActivity::class.java.getDeclaredMethod("showSyncSettings").apply { isAccessible = true }
            instrumentation.runOnMainSync { openSync.invoke(activity) }
            instrumentation.waitForIdleSync()
            val syncPage = activity.settingsFragment()!!.requireView()
            assertTrue(syncPage.isShown)
            assertEquals("连接", syncPage.findViewWithTag<TextView>("sync-connect").text.toString())
            instrumentation.runOnMainSync { activity.closeSettings() }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    private fun find(view: View, description: String): View? {
        if (view.contentDescription == description) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            find(view.getChildAt(index), description)?.let { return it }
        }
        return null
    }
}
