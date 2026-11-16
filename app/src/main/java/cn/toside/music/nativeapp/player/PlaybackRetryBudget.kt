package cn.toside.music.nativeapp.player

import java.util.concurrent.atomic.AtomicInteger

/** One budget for the entire play request, including URL refreshes and resumed HTTP loads. */
internal class PlaybackRetryBudget(private val maximum: Int = 3) {
    private val retries = AtomicInteger()
    val used get() = retries.get()
    fun nextDelayMs(): Long? {
        while (true) {
            val previous = retries.get()
            if (previous >= maximum) return null
            if (retries.compareAndSet(previous, previous + 1)) return 500L * (previous + 1)
        }
    }
}
