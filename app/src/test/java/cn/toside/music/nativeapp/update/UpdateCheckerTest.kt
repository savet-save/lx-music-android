package cn.toside.music.nativeapp.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class UpdateCheckerTest {
    private class MemoryStore : UpdateCheckStore {
        var history = UpdateCheckHistory()
        override fun read() = history
        override fun write(history: UpdateCheckHistory) { this.history = history }
    }
    private class Fixture : AutoCloseable {
        var now = 1_800_000_000_000L
        val release = NativeRelease("1.0.0", "https://example.org/release", "notes")
        val store = MemoryStore()
        val published = mutableListOf<NativeRelease?>()
        var requests = 0
        var fetch: suspend () -> NativeRelease? = { release }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val checker = UpdateChecker(scope, store, { requests++; fetch() }, { published.add(it) }, { now })
        override fun close() { scope.cancel() }
    }
    private fun scenario(block: suspend Fixture.() -> Unit): Unit = runBlocking {
        withTimeout(5_000) { Fixture().use { block(it) } }
    }
    private fun assertFailure(expected: Exception, actual: Throwable?) {
        assertEquals(expected.javaClass, actual?.javaClass)
        assertEquals(expected.message, actual?.message)
    }

    @Test fun successfulChecksWaitTwentyFourHoursFromCompletion() = scenario {
        fetch = { now += 20; release }
        assertEquals(release, checker.check()?.release)
        assertEquals(now, store.history.lastSuccessAt)
        assertNull(checker.check())
        now += UpdateChecker.SUCCESS_INTERVAL_MS - 1
        assertNull(checker.check())
        now++
        assertEquals(release, checker.check()?.release)
        assertEquals(2, requests)
    }

    @Test fun failureKeepsCachedReleaseAndRetriesAfterFiveMinutesInsteadOfTwentyFourHours() = scenario {
        checker.check()
        val previousSuccess = now
        now += UpdateChecker.SUCCESS_INTERVAL_MS
        val failure = IOException("offline")
        fetch = { throw failure }
        assertFailure(failure, runCatching { checker.check() }.exceptionOrNull())
        assertEquals(UpdateCheckHistory(previousSuccess, now), store.history)
        assertEquals(listOf(release), published)
        assertNull(checker.check())
        now += UpdateChecker.FAILURE_RETRY_INTERVAL_MS - 1
        assertNull(checker.check())
        now++
        fetch = { release }
        assertEquals(release, checker.check()?.release)
        assertEquals(UpdateCheckHistory(now, 0), store.history)
        assertEquals(3, requests)
    }

    @Test fun manualCheckBypassesFailureBackoffAndAlsoRefreshesAutomaticHistory() = scenario {
        fetch = { throw IOException("offline") }
        assertTrue(runCatching { checker.check() }.isFailure)
        fetch = { release }
        assertEquals(release, checker.check(manual = true)?.release)
        assertNull(checker.check())
        assertEquals(2, requests)
        assertEquals(UpdateCheckHistory(now, 0), store.history)
    }

    @Test fun failedManualCheckUsesShortRetryEvenAfterARecentSuccessfulCheck() = scenario {
        checker.check()
        now++
        fetch = { throw IOException("manual check failed") }
        assertTrue(runCatching { checker.check(manual = true) }.isFailure)
        now += UpdateChecker.FAILURE_RETRY_INTERVAL_MS
        fetch = { release }
        assertEquals(release, checker.check()?.release)
        assertEquals(3, requests)
    }

    @Test fun automaticAndManualCallersShareOneRequestInEitherOrder() = runBlocking {
        withTimeout(5_000) {
            for (manualFirst in listOf(false, true)) Fixture().use { fixture ->
                val response = CompletableDeferred<Unit>()
                fixture.fetch = { response.await(); fixture.release }
                if (manualFirst) fixture.store.history = UpdateCheckHistory(fixture.now)
                val first = async(start = CoroutineStart.UNDISPATCHED) { fixture.checker.check(manualFirst) }
                val manual = async(start = CoroutineStart.UNDISPATCHED) { fixture.checker.check(manual = true) }
                val automatic = async(start = CoroutineStart.UNDISPATCHED) { fixture.checker.check() }
                assertEquals(1, fixture.requests)
                response.complete(Unit)
                val result = first.await()
                assertEquals(fixture.release, result?.release)
                assertSame(result, manual.await())
                assertSame(result, automatic.await())
                assertEquals(listOf(fixture.release), fixture.published)
            }
        }
    }

    @Test fun sharedFailureReachesEveryWaiterAndDoesNotLeaveAStuckRequest() = runBlocking {
        withTimeout(5_000) { Fixture().use { fixture ->
            val response = CompletableDeferred<Unit>()
            val failure = IOException("offline")
            fixture.fetch = { response.await(); throw failure }
            val automatic = async(start = CoroutineStart.UNDISPATCHED) { runCatching { fixture.checker.check() } }
            val manual = async(start = CoroutineStart.UNDISPATCHED) { runCatching { fixture.checker.check(manual = true) } }
            assertEquals(1, fixture.requests)
            response.complete(Unit)
            assertFailure(failure, automatic.await().exceptionOrNull())
            assertFailure(failure, manual.await().exceptionOrNull())
            fixture.fetch = { fixture.release }
            assertEquals(fixture.release, fixture.checker.check(manual = true)?.release)
            assertEquals(2, fixture.requests)
        } }
    }

    @Test fun cancellingAUiWaiterDoesNotCancelTheRequestUsedByAnotherPage() = runBlocking {
        withTimeout(5_000) { Fixture().use { fixture ->
            val response = CompletableDeferred<Unit>()
            fixture.fetch = { response.await(); fixture.release }
            val firstPage = async(start = CoroutineStart.UNDISPATCHED) { fixture.checker.check() }
            val nextPage = async(start = CoroutineStart.UNDISPATCHED) { fixture.checker.check(manual = true) }
            firstPage.cancelAndJoin()
            response.complete(Unit)
            assertEquals(fixture.release, nextPage.await()?.release)
            assertEquals(1, fixture.requests)
            assertEquals(listOf(fixture.release), fixture.published)
        } }
    }

    @Test fun resultAndSuccessTimeAreSavedEvenWhenTheOnlyUiWaiterIsDestroyed() = runBlocking {
        withTimeout(5_000) { Fixture().use { fixture ->
            val response = CompletableDeferred<Unit>()
            val completed = CompletableDeferred<Unit>()
            fixture.fetch = { response.await(); fixture.release.also { completed.complete(Unit) } }
            val page = async(start = CoroutineStart.UNDISPATCHED) { fixture.checker.check() }
            page.cancelAndJoin()
            response.complete(Unit)
            completed.await()
            assertEquals(listOf(fixture.release), fixture.published)
            assertEquals(UpdateCheckHistory(fixture.now, 0), fixture.store.history)
            assertNull(fixture.checker.check())
            assertEquals(1, fixture.requests)
        } }
    }

    @Test fun cancellationDoesNotConsumeEitherRetryInterval() = scenario {
        fetch = { throw CancellationException("application shutdown") }
        assertTrue(runCatching { checker.check() }.exceptionOrNull() is CancellationException)
        assertEquals(UpdateCheckHistory(), store.history)
        fetch = { release }
        assertEquals(release, checker.check()?.release)
        assertEquals(2, requests)
    }

    @Test fun emptyResponseIsSuccessfulAndClearsTheCachedNotice() = scenario {
        fetch = { null }
        val result = checker.check()
        assertNotNull(result)
        assertNull(result?.release)
        assertEquals(listOf<NativeRelease?>(null), published)
        assertEquals(UpdateCheckHistory(now, 0), store.history)
        assertNull(checker.check())
        assertEquals(1, requests)
    }

    @Test fun clockRollbackDoesNotPreventFutureChecks() = scenario {
        checker.check()
        now--
        assertEquals(release, checker.check()?.release)
        assertEquals(2, requests)
    }
}
