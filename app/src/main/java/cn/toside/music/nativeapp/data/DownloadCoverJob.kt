package cn.toside.music.nativeapp.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Artwork is independent of lyric matching and never changes a completed audio task's status. */
class DownloadCoverJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            var retry = false
            try {
                val downloads = Downloads(applicationContext)
                downloads.pendingCovers().forEach { retry = downloads.saveCover(it.key) || retry }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { retry = true }
            jobFinished(params, retry)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        const val ACTION_UPDATED = "cn.toside.music.nativeapp.DOWNLOAD_COVER_UPDATED"
        private const val ID = 0x4c584356
        fun schedule(context: Context) { runCatching {
            context.getSystemService(JobScheduler::class.java)?.schedule(JobInfo.Builder(ID, ComponentName(context, DownloadCoverJob::class.java))
                .setMinimumLatency(0).setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        } }
    }
}
