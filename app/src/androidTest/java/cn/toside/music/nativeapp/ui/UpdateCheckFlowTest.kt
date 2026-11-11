package cn.toside.music.nativeapp.ui

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.update.NativeRelease
import cn.toside.music.nativeapp.update.UpdateChecker
import cn.toside.music.nativeapp.update.UpdateCheckPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class UpdateCheckFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun awaitUi(check: () -> Boolean) {
        repeat(150) {
            var ready = false
            main { ready = check() }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for shared startup/about update check")
    }

    @Test fun aboutPageJoinsStartupRequestAndReceivesTheSharedResult() = runBlocking {
        val context = instrumentation.targetContext
        val settings = context.getSharedPreferences("app-settings", 0)
        val previousAutoCheck = settings.all["version.autoCheck"] as? Boolean
        val cache = context.getSharedPreferences("native-updates", 0)
        val previousCache = cache.getString("release-notice", null)
        val previousNotice = NativeUpdateNotice.release.value
        val checkerField = NativeUpdateNotice::class.java.getDeclaredField("checker").apply { isAccessible = true }
        val previousChecker = checkerField.get(NativeUpdateNotice)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val response = CompletableDeferred<NativeRelease?>()
        val requests = AtomicInteger()
        val name = "update-flow-${System.nanoTime()}"
        val history = UpdateCheckPreferences(context, name)
        val checker = UpdateChecker(scope, history, { requests.incrementAndGet(); response.await() }, { NativeUpdateNotice.remember(context, it) })
        var activity: MainActivity? = null
        try {
            main {
                checkerField.set(NativeUpdateNotice, checker)
                NativeUpdateNotice.remember(context, null)
            }
            settings.edit().putBoolean("version.autoCheck", true).commit()
            val current = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = current
            awaitUi { current.settingsPlayer() != null && requests.get() == 1 }
            main { current.openSettings("about") }
            fun page() = current.settingsFragment()?.view
            awaitUi { page()?.findViewWithTag<View>("update-check")?.isShown == true }
            main { assertTrue(page()!!.findViewWithTag<View>("update-check").performClick()) }
            awaitUi { page()?.findViewWithTag<View>("update-check")?.isEnabled == false }
            assertEquals(1, requests.get())
            val release = NativeRelease("999.0.0", "https://example.org/release", "- Shared update result")
            response.complete(release)
            awaitUi {
                page()?.findViewWithTag<View>("update-check")?.isEnabled == true &&
                    page()?.findViewWithTag<TextView>("update-status")?.text?.contains("999.0.0") == true
            }
            assertEquals(1, requests.get())
            assertEquals(release, NativeUpdateNotice.release.value)
            assertTrue(history.read().lastSuccessAt > 0)
            assertEquals(0L, history.read().lastFailureAt)
        } finally {
            main { activity?.finish() }
            scope.cancel()
            main {
                checkerField.set(NativeUpdateNotice, previousChecker)
                NativeUpdateNotice.release.value = previousNotice
            }
            settings.edit().apply {
                previousAutoCheck?.let { putBoolean("version.autoCheck", it) } ?: remove("version.autoCheck")
            }.commit()
            cache.edit().apply {
                previousCache?.let { putString("release-notice", it) } ?: remove("release-notice")
            }.commit()
            context.deleteSharedPreferences(name)
        }
    }
}
