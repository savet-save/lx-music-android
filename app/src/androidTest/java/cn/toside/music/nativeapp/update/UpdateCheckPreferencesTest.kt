package cn.toside.music.nativeapp.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class UpdateCheckPreferencesTest {
    @Test fun legacyAttemptDoesNotBlockChecksAndNewHistorySurvivesRecreation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "update-check-${System.nanoTime()}"
        val preferences = context.getSharedPreferences(name, 0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var now = System.currentTimeMillis()
        try {
            preferences.edit().putString("version.lastCheck", now.toString()).commit()
            val store = UpdateCheckPreferences(context, name)
            assertEquals(UpdateCheckHistory(), store.read())
            var requests = 0
            val first = UpdateChecker(scope, store, { requests++; null }, clock = { now })
            assertNotNull(first.check())
            assertEquals(1, requests)
            assertFalse(preferences.contains("version.lastCheck"))
            assertEquals(UpdateCheckHistory(now, 0), UpdateCheckPreferences(context, name).read())

            now += UpdateChecker.SUCCESS_INTERVAL_MS
            val failure = IOException("offline")
            val failing = UpdateChecker(scope, UpdateCheckPreferences(context, name), { requests++; throw failure }, clock = { now })
            val error = runCatching { failing.check() }.exceptionOrNull()
            assertTrue(error is IOException)
            assertEquals(failure.message, error?.message)
            val lastFailure = now
            val restored = UpdateChecker(scope, UpdateCheckPreferences(context, name), { requests++; null }, clock = { now })
            assertNull(restored.check())
            assertEquals(2, requests)
            assertEquals(lastFailure, UpdateCheckPreferences(context, name).read().lastFailureAt)
            now += UpdateChecker.FAILURE_RETRY_INTERVAL_MS
            assertNotNull(restored.check())
            assertEquals(3, requests)
            assertEquals(UpdateCheckHistory(now, 0), UpdateCheckPreferences(context, name).read())
        } finally {
            scope.cancel()
            context.deleteSharedPreferences(name)
        }
    }
}
