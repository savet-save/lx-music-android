package cn.toside.music.nativeapp.utils

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ToastUtilsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun observe(test: (String, LinkedBlockingQueue<String>) -> Unit) {
        val prefix = "toast-${System.nanoTime()}"
        val shown = LinkedBlockingQueue<String>()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val deadline = SystemClock.elapsedRealtime() + 6000
            var focused = false
            while (!focused && SystemClock.elapsedRealtime() < deadline) { main { focused = activity.hasWindowFocus() }; if (!focused) SystemClock.sleep(40) }
            assertTrue("Activity must be foreground for toast validation", focused)
            instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
                if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                    event.text.map(CharSequence::toString).filter { it.startsWith(prefix) }.forEach(shown::offer)
                }
            }
            ToastUtils.cancel(); instrumentation.waitForIdleSync()
            test(prefix, shown)
        } finally {
            ToastUtils.cancel(); instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.setOnAccessibilityEventListener(null)
            main { activity.finish() }
        }
    }

    @Test fun mainAndWorkerBurstsDisplayOnlyTheLatestQueuedMessage() = observe { prefix, shown ->
        main { repeat(50) { ToastUtils.show(context, "$prefix-main-$it") } }
        assertEquals("$prefix-main-49", shown.poll(5, TimeUnit.SECONDS))
        assertNull(shown.poll(250, TimeUnit.MILLISECONDS))
        main {
            val worker = Thread { repeat(50) { ToastUtils.show(context, "$prefix-worker-$it") } }
            worker.start(); worker.join(2000)
            assertFalse("Worker calls must not wait for the main thread", worker.isAlive)
        }
        assertEquals("$prefix-worker-49", shown.poll(5, TimeUnit.SECONDS))
        assertNull(shown.poll(400, TimeUnit.MILLISECONDS))
    }

    @Test fun newMessageReplacesAVisibleLongToastAndCancelDiscardsPendingMessages() = observe { prefix, shown ->
        ToastUtils.show(context, "$prefix-visible", ToastUtils.LONG)
        assertEquals("$prefix-visible", shown.poll(5, TimeUnit.SECONDS))
        val start = SystemClock.elapsedRealtime()
        val worker = Thread { ToastUtils.show(context, "$prefix-replacement") }
        worker.start(); worker.join(2000)
        assertEquals("$prefix-replacement", shown.poll(2500, TimeUnit.MILLISECONDS))
        assertTrue("Replacement must not wait for the previous long toast to expire", SystemClock.elapsedRealtime() - start < 2500)
        main { ToastUtils.show(context, "$prefix-discarded"); ToastUtils.cancel() }
        assertNull(shown.poll(600, TimeUnit.MILLISECONDS))
        ToastUtils.show(context, "$prefix-after-cancel")
        assertEquals("$prefix-after-cancel", shown.poll(5, TimeUnit.SECONDS))
    }
}
