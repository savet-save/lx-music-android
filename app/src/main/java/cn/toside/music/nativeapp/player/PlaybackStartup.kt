package cn.toside.music.nativeapp.player

import android.os.SystemClock
import cn.toside.music.nativeapp.utils.DiagnosticLog

internal class PlaybackStartup(private val log: DiagnosticLog, platform: String, preferred: String) {
    private val started = SystemClock.elapsedRealtime()
    private val id = SystemClock.elapsedRealtimeNanos()
    private var bufferingAt: Long? = null
    private var finished = false

    init { record("start platform=$platform preferred=$preferred") }

    fun record(event: String) = log.record("source", "playback=$id $event", "INFO")

    fun buffer(origin: String, quality: String) {
        if (finished) return
        val now = SystemClock.elapsedRealtime()
        bufferingAt = now
        record("buffer origin=$origin quality=$quality sinceStartMs=${now - started}")
    }

    fun bufferFailed() {
        bufferingAt?.let { record("buffer outcome=failed elapsedMs=${SystemClock.elapsedRealtime() - it}") }
        bufferingAt = null
    }

    fun finish(outcome: String) {
        if (finished) return
        finished = true
        val now = SystemClock.elapsedRealtime()
        val bufferMs = bufferingAt?.let { now - it }
        record("startup outcome=$outcome totalMs=${now - started} bufferMs=${bufferMs ?: 0}")
    }
}
