package cn.toside.music.nativeapp.ui

import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.AppPreferences

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PactTest {
    @Test fun firstUseDefersServicesAndDeepLinksUntilAgreementAndDecliningDoesNotAccept() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val preferences = AppPreferences(context)
        val agreed = preferences.bool("common.isAgreePact")
        val startup = preferences.startupAutoPlay
        val service = MainActivity::class.java.getDeclaredField("service").apply { isAccessible = true }
        val pact = MainActivity::class.java.getDeclaredField("pactDialog").apply { isAccessible = true }
        val incoming = MainActivity::class.java.getDeclaredField("incomingHandled").apply { isAccessible = true }
        fun open() = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("lxmusic://player/pause")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var activity: MainActivity? = null
        try {
            preferences.set("common.isAgreePact", false); preferences.set("player.startupAutoPlay", false)
            activity = open()
            val rejected = pact.get(activity) as Dialog
            assertTrue(rejected.isShowing); assertNull(service.get(activity)); assertFalse(incoming.getBoolean(activity))
            val locked = rejected.findViewById<Button>(R.id.pact_accept_button)
            val reject = rejected.findViewById<Button>(R.id.pact_reject_button)
            val scroll = rejected.findViewById<ScrollView>(R.id.pact_scroll)
            val configuration = activity.resources.configuration
            val expanded = minOf(configuration.screenWidthDp, configuration.smallestScreenWidthDp) >= 600
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val actions = locked.parent as LinearLayout
                assertSame(actions, reject.parent)
                assertEquals(if (expanded) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL, actions.orientation)
                assertTrue(scroll.canScrollVertically(1))
                assertTrue(scroll.bottom <= actions.top)
                assertFalse(locked.isEnabled)
                locked.performClick()
                assertTrue(rejected.isShowing)
                assertFalse(preferences.bool("common.isAgreePact"))
                reject.performClick()
            }
            withTimeout(5000) { while (!activity!!.isDestroyed) delay(50) }
            assertFalse(preferences.bool("common.isAgreePact"))
            activity = open(); val accepting = pact.get(activity) as Dialog
            val accept = accepting.findViewById<Button>(R.id.pact_accept_button)
            withTimeout(25_000) { while (!accept.isEnabled) delay(50) }
            instrumentation.runOnMainSync { accept.performClick() }
            withTimeout(10_000) { while (service.get(activity) == null || !incoming.getBoolean(activity)) delay(50) }
            assertTrue(preferences.bool("common.isAgreePact")); assertFalse(accepting.isShowing)
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            instrumentation.runOnMainSync { activity!!.recreate() }
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as MainActivity
            assertNull(pact.get(activity))
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            preferences.set("common.isAgreePact", agreed); preferences.set("player.startupAutoPlay", startup)
        }
    }
}
