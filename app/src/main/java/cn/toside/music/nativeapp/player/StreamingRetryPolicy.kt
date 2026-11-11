@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package cn.toside.music.nativeapp.player

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import androidx.media3.exoplayer.upstream.Loader
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger

/** One initial load plus three retries, shared by all loads of this playback attempt. */
internal class StreamingRetryPolicy : DefaultLoadErrorHandlingPolicy(MAX_RETRIES) {
    private val failures = AtomicInteger()
    @Volatile var exhausted = false
        private set
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
        val error = loadErrorInfo.exception
        if (error is ParserException || error is FileNotFoundException ||
            error is HttpDataSource.CleartextNotPermittedException || error is Loader.UnexpectedLoaderException) return C.TIME_UNSET
        val failure = failures.incrementAndGet()
        if (failure > MAX_RETRIES) { exhausted = true; return C.TIME_UNSET }
        return 500L * failure
    }
    companion object { const val MAX_RETRIES = 3 }
}
