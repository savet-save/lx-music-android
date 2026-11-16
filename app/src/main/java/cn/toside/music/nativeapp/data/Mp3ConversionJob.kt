package cn.toside.music.nativeapp.data

import android.app.job.*
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*

/** Persisted download history is the queue; conversions resume if the process is stopped. */
class Mp3ConversionJob : JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var work:Job?=null
    override fun onStartJob(params:JobParameters):Boolean {
        work=scope.launch {
            var retry=false
            try { val downloads=Downloads(applicationContext); downloads.pendingConversions().forEach { downloads.convertMp3(it) } }
            catch(e:CancellationException) { throw e }
            catch(_:Exception) { retry=true }
            jobFinished(params,retry)
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean { work?.cancel();return true }
    override fun onDestroy() { scope.cancel();super.onDestroy() }
    companion object {
        private const val ID=0x4c584d50
        fun schedule(context:Context) { runCatching {
            context.getSystemService(JobScheduler::class.java)?.schedule(JobInfo.Builder(ID,ComponentName(context,Mp3ConversionJob::class.java))
                .setMinimumLatency(1000).setBackoffCriteria(30_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        } }
    }
}
