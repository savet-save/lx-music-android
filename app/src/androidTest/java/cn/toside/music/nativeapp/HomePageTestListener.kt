package cn.toside.music.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.Description
import org.junit.runner.notification.RunListener

/** Page history belongs to each test; keep navigation in one test from changing the next test's start page. */
class HomePageTestListener : RunListener() {
    private val preferences get() = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("app-settings", 0)
    private var previous: String? = null
    override fun testStarted(description: Description) {
        previous = preferences.all["home.lastPage"] as? String
        preferences.edit().remove("home.lastPage").commit()
    }
    override fun testFinished(description: Description) {
        preferences.edit().apply { previous?.let { putString("home.lastPage", it) } ?: remove("home.lastPage") }.commit()
    }
}
