package cn.toside.music.nativeapp.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import cn.toside.music.nativeapp.player.SongMatcher
import cn.toside.music.nativeapp.source.SourceAudioResolver
import cn.toside.music.nativeapp.source.UserSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Continue failed system transfers using remaining enabled scripts, including after app restart. */
class DownloadFallbackJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            val sources = UserSources(applicationContext)
            var retry = false
            try {
                val matcher = SongMatcher(applicationContext, scope)
                val resolver = SourceAudioResolver(sources, matcher::find, AppPreferences(applicationContext).bool("player.autoSwitchSource", true))
                val downloads = Downloads(applicationContext, scheduleFallback = {})
                for (key in downloads.pendingSourceRetries()) {
                    try { downloads.retryNextSource(key) { song, quality, excluded -> resolver.resolve(song, quality, excluded) } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* This task is exhausted; continue independent tasks. */ }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { retry = true }
            finally { sources.close() }
            jobFinished(params, retry)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        private const val ID = 0x4c585346
        fun schedule(context: Context) { runCatching {
            context.getSystemService(JobScheduler::class.java)?.schedule(
                JobInfo.Builder(ID, ComponentName(context, DownloadFallbackJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(1000)
                    .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        } }
    }
}
