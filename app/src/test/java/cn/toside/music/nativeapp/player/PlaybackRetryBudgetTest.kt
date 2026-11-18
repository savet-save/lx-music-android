package cn.toside.music.nativeapp.player

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class PlaybackRetryBudgetTest {
    @Test fun urlRefreshesAndHttpResumesShareExactlyThreeRetries() {
        val request = PlaybackRetryBudget()
        assertNotNull(request.nextDelayMs()) // Refresh a rejected URL.
        assertNotNull(request.nextDelayMs()) // Resume a partially received response.
        assertNotNull(request.nextDelayMs()) // Refresh again after another rejection.
        assertNull(request.nextDelayMs())
        assertEquals(3, request.used)
        assertNull(request.nextDelayMs())
        assertNotNull(PlaybackRetryBudget().nextDelayMs()) // A new play request gets a fresh budget.
    }
    @Test fun simultaneousFailuresCannotExceedTheBudget() {
        val request = PlaybackRetryBudget()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val accepted = executor.invokeAll((1..16).map { Callable { request.nextDelayMs() != null } }).count { it.get() }
            assertEquals(3, accepted)
            assertEquals(3, request.used)
        } finally { executor.shutdownNow() }
    }
    @Test fun rejectedPrimaryUsesTheBackupEvenWhenThePrimarySignatureChanges() {
        val urls = PlaybackAddresses()
        urls.failed("https://primary.example/audio?old", true)
        assertEquals("https://backup.example/audio", urls.choose("https://primary.example/audio?new", listOf("https://backup.example/audio")))
        urls.failed("https://backup.example/audio", true)
        assertEquals("https://primary.example/audio?new", urls.choose("https://primary.example/audio?new", listOf("https://backup.example/audio")))
    }
    @Test fun backupOnTheSameHostIsStillTriedBeforeRepeatingTheFailedUrl() {
        val urls = PlaybackAddresses()
        urls.failed("https://cdn.example/primary", true)
        assertEquals("https://cdn.example/backup", urls.choose("https://cdn.example/primary", listOf("https://cdn.example/backup")))
    }
}
