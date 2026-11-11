package cn.toside.music.nativeapp.data

import android.app.DownloadManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Durable, short background work; lyric failures never turn a completed audio task into a failure. */
class DownloadLyricsJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            var retry = false
            try {
                val downloads = Downloads(applicationContext)
                for (task in downloads.pendingLyrics()) retry = downloads.saveLyrics(task.key) || retry
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { retry = true }
            jobFinished(params, retry)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        private const val ID = 0x4c584c52
        fun schedule(context: Context) { runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            scheduler.schedule(JobInfo.Builder(ID, ComponentName(context, DownloadLyricsJob::class.java))
                .setMinimumLatency(0).setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        } }
    }
}

class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id >= 0 && Downloads(context).ownsSystemId(id)) DownloadLyricsJob.schedule(context)
    }
}
