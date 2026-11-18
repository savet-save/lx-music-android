package cn.toside.music.nativeapp.ui

import android.app.Activity
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/** Use the same foreground Activity launch path as a user opening an internal settings page. */
internal object ForegroundTestActivities {
    fun start(intent: Intent): Activity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (InstrumentationRegistry.getArguments().getString("foregroundTestHost") != "true") return instrumentation.startActivitySync(intent)
        val packageName = instrumentation.targetContext.packageName
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n $packageName/cn.toside.music.nativeapp.ui.MainActivity"
        )).bufferedReader().use { it.readText() }
        instrumentation.waitForIdleSync()
        if (intent.component?.className == MainActivity::class.java.name) {
            var activity: MainActivity? = null
            instrumentation.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
            }
            val host = requireNotNull(activity) { "Test host did not open" }
            if (intent.action != null || intent.data != null || intent.extras != null) instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }.invoke(host, intent)
            }
            return host
        }
        val monitor = instrumentation.addMonitor(requireNotNull(intent.component).className, null, false)
        try {
            instrumentation.runOnMainSync {
                val host = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().first()
                host.startActivity(Intent(intent).apply { flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv() })
            }
            return requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10000)) { "Internal test page did not open" }
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
