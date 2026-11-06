package cn.toside.music.nativeapp

import android.app.DownloadManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry

/** CI exercises real loopback transfers without waiting for JobScheduler's batching policy. */
internal class DownloadTestScheduler(private val downloadId: Long) {
    private val enabled = InstrumentationRegistry.getArguments().getString("runDownloadJobs") == "true"
    private var lastAttempt = 0L
    var diagnostics = "automatic scheduling"
        private set

    fun onStatus(status: Int?) {
        if (!enabled || status != DownloadManager.STATUS_PENDING) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAttempt < 1000) return
        lastAttempt = now
        // AOSP DownloadProvider uses the download ID as its JobScheduler job ID.
        require(downloadId in 1..Int.MAX_VALUE.toLong())
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "cmd jobscheduler run -f com.android.providers.downloads $downloadId"
        )
        diagnostics = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
    }
}
