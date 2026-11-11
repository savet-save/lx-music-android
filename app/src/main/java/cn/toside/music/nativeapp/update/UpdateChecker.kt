package cn.toside.music.nativeapp.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

internal data class UpdateCheckHistory(val lastSuccessAt: Long = 0, val lastFailureAt: Long = 0)
internal interface UpdateCheckStore {
    fun read(): UpdateCheckHistory
    fun write(history: UpdateCheckHistory)
}
internal data class UpdateCheckResult(val release: NativeRelease?)

/** Requests belong to the application scope; cancelling a UI waiter leaves the shared check running. */
internal class UpdateChecker(
    private val scope: CoroutineScope,
    private val store: UpdateCheckStore,
    private val fetch: suspend () -> NativeRelease?,
    private val onChecked: (NativeRelease?) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var inFlight: Deferred<UpdateCheckResult>? = null

    /** Null means the automatic check is throttled; a checked null release is a successful empty response. */
    suspend fun check(manual: Boolean = false): UpdateCheckResult? {
        val request = mutex.withLock {
            inFlight?.takeUnless { it.isCompleted } ?: run {
                if (!manual && !isDue()) return@withLock null
                scope.async {
                    try {
                        val release = fetch()
                        coroutineContext.ensureActive()
                        onChecked(release)
                        store.write(UpdateCheckHistory(lastSuccessAt = clock()))
                        UpdateCheckResult(release)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        store.write(store.read().copy(lastFailureAt = clock()))
                        throw e
                    }
                }.also { inFlight = it }
            }
        }
        return request?.await()
    }

    private fun isDue(): Boolean {
        val history = store.read()
        val previous = if (history.lastFailureAt > 0) history.lastFailureAt else history.lastSuccessAt
        val interval = if (history.lastFailureAt > 0) FAILURE_RETRY_INTERVAL_MS else SUCCESS_INTERVAL_MS
        return previous <= 0 || clock() - previous !in 0 until interval
    }

    companion object {
        const val SUCCESS_INTERVAL_MS = 24 * 60 * 60 * 1000L
        const val FAILURE_RETRY_INTERVAL_MS = 5 * 60 * 1000L
    }
}
