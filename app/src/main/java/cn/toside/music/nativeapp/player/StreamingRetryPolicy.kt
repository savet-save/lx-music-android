@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package cn.toside.music.nativeapp.player

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import androidx.media3.exoplayer.upstream.Loader
import java.io.FileNotFoundException

/** One initial load plus three retries, shared by all loads of this playback attempt. */
internal class StreamingRetryPolicy(private val budget: PlaybackRetryBudget = PlaybackRetryBudget()) : DefaultLoadErrorHandlingPolicy(MAX_RETRIES) {
    @Volatile var exhausted = false
        private set
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
        val error = loadErrorInfo.exception
        if (error is ParserException || error is FileNotFoundException ||
            error is HttpDataSource.CleartextNotPermittedException || error is Loader.UnexpectedLoaderException) return C.TIME_UNSET
        // Refresh signed/expired URLs or switch CDN instead of repeating a rejected address.
        if (error is HttpDataSource.InvalidResponseCodeException && error.responseCode in setOf(401, 403, 404, 410)) return C.TIME_UNSET
        return budget.nextDelayMs() ?: run { exhausted = true; C.TIME_UNSET }
    }
    companion object { const val MAX_RETRIES = 3 }
}
