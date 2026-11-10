package cn.toside.music.nativeapp.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.ListView
import android.widget.ScrollView
import android.net.Uri
import cn.toside.music.nativeapp.data.Song
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.player.PlaybackService
import cn.toside.music.nativeapp.source.SourceHttpFixture
import cn.toside.music.nativeapp.update.NativeRelease
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsFragmentTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val noActions = SettingsActions({}, { _, _, _ -> }, {})
    private fun field(activity: MainActivity, name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun invoke(activity: MainActivity, method: String) = MainActivity::class.java.getDeclaredMethod(method).apply { isAccessible = true }.invoke(activity)
    private fun settings(activity: MainActivity): SettingsFragment {
        var fragment: SettingsFragment? = null
        await {
            fragment = activity.settingsFragment() as? SettingsFragment
            fragment?.isResumed == true && fragment?.view != null
        }
        return fragment!!
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun start(): MainActivity {
        var activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        await {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()?.let { activity = it }
            !activity.isDestroyed && field(activity, "service") != null
        }
        return activity
    }
    private fun await(description: (() -> String)? = null, check: () -> Boolean) {
        repeat(150) {
            var done = false
            instrumentation.runOnMainSync { done = check() }
            if (done) return
            SystemClock.sleep(50)
        }
        throw AssertionError(description?.invoke() ?: "Timed out waiting for settings")
    }
    private fun measure(view: View, width: Int, height: Int) {
        val density = view.resources.displayMetrics.density
        val w = (width * density).toInt(); val h = (height * density).toInt()
        repeat(2) { view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); view.layout(0, 0, w, h) }
    }
    private fun capture(name: String, view: View) {
        if (InstrumentationRegistry.getArguments().getString("adaptiveScreenshots") != "true") return
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = File(output?.let(::File) ?: instrumentation.targetContext.getExternalFilesDir(null), "settings-validation").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun categoriesAndControlsFitPhoneTabletAndShortWindows() {
        val activity = start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            listOf(AdaptiveLayout(360, 800), AdaptiveLayout(600, 800), AdaptiveLayout(1024, 768), AdaptiveLayout(640, 360)).forEach { layout ->
                lateinit var root: View
                instrumentation.runOnMainSync {
                    val page = SettingsPage(activity, scope, { field(activity, "service") as? PlaybackService }, noActions, layout) {}
                    root = page.createContent()
                }
                listOf("basic", "playback", "sources", "list", "theme", "cache", "sync", "about", "desktop-lyrics", "diagnostics").forEach { category ->
                    instrumentation.runOnMainSync {
                        descendants(root).first { it.tag == "settings-category-$category" }.performClick()
                    }
                    if (category == "sources") await { (descendants(root).first { it.tag == "settings-source-list" } as ViewGroup).childCount > 0 }
                    instrumentation.runOnMainSync {
                        measure(root, layout.widthDp, layout.heightDp)
                        assertEquals(layout.expanded, descendants(root).any { it.tag == "settings-sidebar" })
                        assertEquals(!layout.expanded, descendants(root).any { it.tag == "settings-tabs" })
                        val content = descendants(root).first { it.tag == "settings-content" } as ViewGroup
                        assertTrue(content.height > 150 * activity.resources.displayMetrics.density)
                        descendants(content).filter { it.visibility == View.VISIBLE }.forEach { child ->
                            val parent = child.parent as? ViewGroup
                            if (parent != null) assertTrue("$category ${child.tag} must fit parent", child.left >= 0 && child.right <= parent.width)
                        }
                        if (category == "sources") {
                            val input = descendants(root).filterIsInstance<EditText>().single()
                            assertTrue(input.width > 150 * activity.resources.displayMetrics.density)
                            assertEquals(1, input.maxLines)
                        }
                        if (category in listOf("basic", "sources")) capture("${layout.widthDp}x${layout.heightDp}-$category", root)
                    }
                }
            }
        } finally { scope.cancel(); instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun categoryAndSourceDraftSurviveRecreation() {
        var activity = start()
        try {
            instrumentation.runOnMainSync { invoke(activity, "showSources") }
            var fragment = settings(activity)
            instrumentation.runOnMainSync {
                (descendants(fragment.requireView()).first { it.tag == "settings-source-url" } as EditText).setText("https://example.org/draft.js")
            }
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            instrumentation.runOnMainSync { activity.recreate() }
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as MainActivity
            instrumentation.removeMonitor(monitor)
            fragment = settings(activity)
            instrumentation.runOnMainSync {
                val root = fragment.requireView()
                assertTrue(descendants(root).first { it.tag == "settings-category-sources" }.isSelected)
                assertEquals("https://example.org/draft.js", (descendants(root).first { it.tag == "settings-source-url" } as EditText).text.toString())
                assertEquals("sources", fragment.savePageState()?.getString("category"))
                activity.closeSettings()
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun syncAddressSurvivesRecreationAndCodeIsNotSaved() {
        var activity = start()
        try {
            instrumentation.runOnMainSync { activity.openSettings("sync") }
            val page = settings(activity).requireView()
            instrumentation.runOnMainSync {
                page.findViewWithTag<EditText>("sync-address").setText("http://192.0.2.1:9527")
                page.findViewWithTag<EditText>("sync-code").setText("temporary-code")
            }
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            instrumentation.runOnMainSync { activity.recreate() }
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as MainActivity
            instrumentation.removeMonitor(monitor)
            val restored = settings(activity).requireView()
            instrumentation.runOnMainSync {
                assertEquals("http://192.0.2.1:9527", restored.findViewWithTag<EditText>("sync-address").text.toString())
                assertEquals("", restored.findViewWithTag<EditText>("sync-code").text.toString())
                assertSame(activity.window.decorView.windowToken, restored.windowToken)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun playbackControlsReturnWhenTheServiceRebindsAfterRecreation() {
        var activity = start()
        try {
            instrumentation.runOnMainSync { activity.openSettings("playback") }
            await { activity.settingsFragment()?.view?.findViewWithTag<View>("playback-settings") != null }
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            instrumentation.runOnMainSync { activity.recreate() }
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as MainActivity
            instrumentation.removeMonitor(monitor)
            await { activity.settingsFragment()?.view?.findViewWithTag<View>("playback-settings") != null }
            assertEquals("playback", settings(activity).savePageState()?.getString("category"))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun updateNotesAndLicenseExpandInsideTheSettingsPage() {
        val preferences = instrumentation.targetContext.getSharedPreferences("app-settings", 0)
        val ignored = preferences.getString("version.ignored", null)
        val previous = NativeUpdateNotice.release.value
        val activity = start()
        try {
            instrumentation.runOnMainSync {
                preferences.edit().remove("version.ignored").commit()
                NativeUpdateNotice.release.value = NativeRelease("999.0.0", "https://example.org/release", """
                    ## 更新记录
                    - 减少设置弹窗并更新版本到999.0.0 ([abcdef0](https://example.org/commit/abcdef0))
                    - 修复同步状态显示。
                    [完整改动](https://example.org/compare)
                    ## APK SHA-256
                    | app.apk | ${"a".repeat(64)} |
                """.trimIndent(), "https://example.org/update.apk")
                activity.openSettings("about")
            }
            val page = settings(activity).requireView()
            await {
                val notes = page.findViewWithTag<TextView>("update-notes")
                notes.text.toString() == "• 减少设置弹窗\n• 修复同步状态显示。" && notes.lineCount >= 2 && page.findViewWithTag<View>("update-download").isEnabled
            }
            instrumentation.runOnMainSync {
                measure(page, 600, 360)
                val notes = page.findViewWithTag<TextView>("update-notes")
                assertTrue("Every update line must fit the text view", notes.height - notes.compoundPaddingTop - notes.compoundPaddingBottom >= notes.layout.getLineBottom(notes.lineCount - 1))
                val scroll = page.findViewWithTag<ScrollView>("settings-content")
                scroll.scrollTo(0, notes.top)
                assertSame(activity.window.decorView.windowToken, page.windowToken)
                assertTrue(page.findViewWithTag<View>("update-download").isEnabled)
                assertEquals(activity.getString(R.string.update_view_full_notes), page.findViewWithTag<TextView>("update-release-page").text.toString())
                assertTrue(page.findViewWithTag<TextView>("about-version").lineCount >= 3)
                capture("update-summary", page)
                page.findViewWithTag<View>("update-ignore").performClick()
                assertEquals("999.0.0", preferences.getString("version.ignored", null))
                assertFalse(page.findViewWithTag<View>("update-ignore").isEnabled)
                assertFalse((page.findViewWithTag<TextView>("settings-category-about")).text.contains("•"))
                descendants(page).filterIsInstance<TextView>().first { it.text.toString() == activity.getString(cn.toside.music.nativeapp.R.string.ui_038ad816c53f) }.performClick()
                assertEquals(View.VISIBLE, page.findViewWithTag<View>("about-license").visibility)
                assertTrue(page.findViewWithTag<TextView>("about-license").text.contains("Apache License"))
                measure(page, 600, 360)
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                assertTrue("The outer page must scroll through expanded text", scroll.scrollY > 0)
                assertTrue("The end of the content must be reachable", scroll.getChildAt(0).bottom <= scroll.scrollY + scroll.height - scroll.paddingBottom)
            }
            var openedRelease: Intent? = null
            val linkMonitor = object : android.app.Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult? {
                    if (intent.action != Intent.ACTION_VIEW) return null
                    openedRelease = intent
                    return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
                }
            }
            instrumentation.addMonitor(linkMonitor)
            try {
                instrumentation.runOnMainSync { page.findViewWithTag<View>("update-release-page").performClick() }
                assertEquals("https://example.org/release", openedRelease?.dataString)
            } finally { instrumentation.removeMonitor(linkMonitor) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await { activity.settingsFragment() == null }
        } finally {
            instrumentation.runOnMainSync { NativeUpdateNotice.release.value = previous; activity.finish() }
            preferences.edit().apply { if (ignored == null) remove("version.ignored") else putString("version.ignored", ignored) }.commit()
        }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun qualityPopupSelectsWithoutLeavingSettingsAndBackOnlyClosesThePopup() {
        val preferences = instrumentation.targetContext.getSharedPreferences("player", 0)
        val previous = preferences.getString("quality", null)
        val activity = start()
        fun menu() = WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<ListView>()
            .firstOrNull { it.tag == "settings-choice-menu" && it.isShown }
        try {
            instrumentation.runOnMainSync { activity.openSettings("playback") }
            val page = settings(activity).requireView()
            await { page.findViewWithTag<View>("settings-playback-quality").isLaidOut }
            instrumentation.runOnMainSync { page.findViewWithTag<View>("settings-playback-quality").performClick() }
            await { menu()?.childCount?.let { it > 0 } == true }
            val next = if (previous == "320k") "128k" else "320k"
            instrumentation.runOnMainSync {
                val popup = menu()!!
                val index = listOf("128k", "320k", "flac", "flac24bit").indexOf(next)
                popup.performItemClick(popup.findViewWithTag("settings-choice-$next"), index, index.toLong())
                assertEquals(next, preferences.getString("quality", null))
                assertEquals(R.id.settings_page, activity.navigationController().currentDestination?.id)
                page.findViewWithTag<View>("settings-playback-quality").performClick()
            }
            await { menu()?.rootView?.hasWindowFocus() == true }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await { menu() == null }
            assertEquals(R.id.settings_page, activity.navigationController().currentDestination?.id)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            preferences.edit().apply { if (previous == null) remove("quality") else putString("quality", previous) }.commit()
        }
    }
    @Test fun onlineImportReportsFailureAllowsRetryAndRefreshesEnabledSource() = runBlocking {
        val activity = start()
        val player = field(activity, "service") as PlaybackService
        val before = player.sourceCatalog()
        try {
            SourceHttpFixture().use { server ->
                instrumentation.runOnMainSync { invoke(activity, "showSources") }
                val fragment = settings(activity)
                val root = fragment.requireView()
                await { (descendants(root).first { it.tag == "settings-source-list" } as ViewGroup).childCount > 0 }
                if (before.selected == null) instrumentation.runOnMainSync {
                    assertFalse(descendants(root).filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.settings_selected) })
                }
                fun submit(url: String) { instrumentation.runOnMainSync {
                    (descendants(root).first { it.tag == "settings-source-url" } as EditText).setText(url)
                    descendants(root).first { it.tag == "settings-source-import" }.performClick()
                } }
                submit("${server.address}/missing")
                await { (descendants(root).first { it.tag == "settings-source-status" } as TextView).text.contains("404") }
                instrumentation.runOnMainSync { assertTrue(descendants(root).first { it.tag == "settings-source-import" }.isEnabled) }
                assertEquals(before.selected, player.sourceCatalog().selected)
                submit("${server.address}/script")
                await { (descendants(root).first { it.tag == "settings-source-status" } as TextView).text.toString() == activity.getString(R.string.settings_source_success) }
                await { descendants(root).filterIsInstance<TextView>().any { it.text.toString() == "Settings import fixture" } }
                val after = player.sourceCatalog()
                assertEquals("Settings import fixture", after.entries.first { it.id == after.selected }.name)
                instrumentation.runOnMainSync {
                    assertEquals(1, descendants(root).filterIsInstance<TextView>().count { it.text.startsWith(activity.getString(R.string.settings_selected)) })
                    assertEquals("", (descendants(root).first { it.tag == "settings-source-url" } as EditText).text.toString())
                    activity.closeSettings()
                }
            }
        } finally {
            player.sourceCatalog().entries.filter { entry -> before.entries.none { it.id == entry.id } }.forEach { player.removeSource(it.id) }
            player.selectSource(before.selected)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
    @Test fun pendingImportStaysBusyAcrossCategoriesAndIsCancelledWhenClosed() = runBlocking {
        val activity = start()
        val player = field(activity, "service") as PlaybackService
        val before = player.sourceCatalog()
        val preferences = instrumentation.targetContext.getSharedPreferences("app-settings", 0)
        val savedTheme = listOf("theme.id", "common.isAutoTheme").associateWith { preferences.all[it] }
        try {
            SourceHttpFixture().use { server ->
                instrumentation.runOnMainSync { invoke(activity, "showSources") }
                val fragment = settings(activity)
                val root = fragment.requireView()
                instrumentation.runOnMainSync {
                    (descendants(root).first { it.tag == "settings-source-url" } as EditText).setText("${server.address}/slow")
                    descendants(root).first { it.tag == "settings-source-import" }.performClick()
                    assertFalse(descendants(root).first { it.tag == "settings-source-import" }.isEnabled)
                    assertFalse(descendants(root).first { it.tag == "settings-source-local-import" }.isEnabled)
                    descendants(root).first { it.tag == "settings-category-basic" }.performClick()
                    descendants(root).first { it.tag == "settings-category-sources" }.performClick()
                    assertFalse(descendants(root).first { it.tag == "settings-source-import" }.isEnabled)
                    assertFalse(descendants(root).first { it.tag == "settings-source-url" }.isEnabled)
                }
                val target = if ((field(activity, "palette") as UiPalette).theme.id == "blue") "green" else "blue"
                instrumentation.runOnMainSync { preferences.edit().putString("theme.id", target).putBoolean("common.isAutoTheme", false).commit() }
                await { (field(activity, "palette") as UiPalette).theme.id == target }
                instrumentation.runOnMainSync {
                    assertSame(fragment, activity.settingsFragment())
                    assertFalse(descendants(root).first { it.tag == "settings-source-import" }.isEnabled)
                    assertFalse(descendants(root).first { it.tag == "settings-source-url" }.isEnabled)
                    activity.closeSettings()
                }
                await { activity.settingsFragment() == null }
            }
            assertEquals(before.selected, player.sourceCatalog().selected)
            assertEquals(before.entries.map { it.id }, player.sourceCatalog().entries.map { it.id })
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            preferences.edit().apply { savedTheme.forEach { (key, value) -> when (value) { is String -> putString(key, value); is Boolean -> putBoolean(key, value); else -> remove(key) } } }.commit()
        }
    }
    @Test fun choosingThemesUpdatesBothScreensImmediatelyAndKeepsNavigationState() {
        val preferences = instrumentation.targetContext.getSharedPreferences("app-settings", 0)
        val saved = listOf("theme.id", "theme.darkId", "theme.lightId", "common.isAutoTheme").associateWith { preferences.all[it] }
        val activity = start()
        try {
            lateinit var home: View
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("openSearchPage", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
                val songs = (1L..40L).map { Song(it, Uri.EMPTY, "Song $it", "Artist", 60_000, "kw", "{}") }
                MainActivity::class.java.getDeclaredField("allSongs").apply { isAccessible = true }.set(activity, songs)
                invoke(activity, "filterSongs")
                home = activity.musicPageContent()
                (field(activity, "list") as ListView).setSelectionFromTop(5, 0)
            }
            await { (field(activity, "list") as ListView).firstVisiblePosition == 5 }
            val first = (field(activity, "list") as ListView).firstVisiblePosition
            val player = field(activity, "service")
            instrumentation.runOnMainSync { invoke(activity, "showSettings") }
            val fragment = settings(activity)
            val root = fragment.requireView()
            assertSame(activity.window.decorView.windowToken, root.windowToken)
            assertFalse(home.isShown)
            assertEquals(R.id.settings_page, activity.navigationController().currentDestination?.id)
            instrumentation.runOnMainSync { descendants(root).first { it.tag == "settings-category-theme" }.performClick() }
            val target = if ((field(activity, "palette") as UiPalette).theme.id == "blue") "green" else "blue"
            instrumentation.runOnMainSync {
                preferences.edit().putBoolean("common.isAutoTheme", true).commit()
                descendants(root).first { it.tag == "settings-theme-$target" }.performClick()
            }
            await { (field(activity, "palette") as UiPalette).theme.id == target }
            assertFalse(activity.isDestroyed); assertSame(player, field(activity, "service"))
            assertFalse(preferences.getBoolean("common.isAutoTheme", true))
            assertSame(fragment, settings(activity)); assertEquals("theme", fragment.savePageState()?.getString("category"))
            instrumentation.runOnMainSync {
                val palette = field(activity, "palette") as UiPalette
                val title = descendants(root).filterIsInstance<TextView>().first { it.isAccessibilityHeading && it.text.toString() == activity.getString(R.string.ui_788db1cfec2a) }
                assertEquals(palette.primary, title.currentTextColor)
                val scroll = descendants(root).first { it.tag == "settings-content" } as ScrollView
                scroll.scrollTo(0, 100)
                capture("fragment-theme-light", root)
                descendants(root).first { it.tag == "settings-theme-black" }.performClick()
            }
            await { (field(activity, "palette") as UiPalette).theme.id == "black" }
            await { (descendants(root).first { it.tag == "settings-content" } as ScrollView).scrollY == 100 }
            assertSame(fragment, settings(activity)); assertSame(player, field(activity, "service"))
            instrumentation.runOnMainSync { capture("fragment-theme-dark", root) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await { activity.settingsFragment() == null }
            await({ "Home scroll expected $first; actual ${(field(activity, "list") as ListView).firstVisiblePosition}; pending ${field(activity, "pendingScroll")}; visible ${(field(activity, "list") as ListView).isShown}; count ${(field(activity, "list") as ListView).count}" }) {
                (field(activity, "list") as ListView).isShown && (field(activity, "list") as ListView).firstVisiblePosition == first
            }
            assertEquals("black", (field(activity, "palette") as UiPalette).theme.id)
            assertEquals((field(activity, "palette") as UiPalette).primary, (field(activity, "toggle") as TextView).currentTextColor)
            assertEquals(40, (field(activity, "list") as ListView).adapter.count)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            preferences.edit().apply { saved.forEach { (key, value) -> when (value) { is String -> putString(key, value); is Boolean -> putBoolean(key, value); else -> remove(key) } } }.commit()
        }
    }
    @Test fun languageAndFontChangesRestoreTheOpenFragmentAndSourceDraftImmediately() {
        val preferences = instrumentation.targetContext.getSharedPreferences("app-settings", 0)
        val saved = listOf("common.langId", "common.fontScale").associateWith { preferences.all[it] }
        var activity = start()
        try {
            instrumentation.runOnMainSync { invoke(activity, "showSources") }
            val fragment = settings(activity)
            instrumentation.runOnMainSync {
                (descendants(fragment.requireView()).first { it.tag == "settings-source-url" } as EditText).setText("https://example.org/fragment-draft.js")
            }
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            instrumentation.runOnMainSync { preferences.edit().putString("common.langId", "en").putString("common.fontScale", "1.3").commit() }
            val replacement = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
            instrumentation.removeMonitor(monitor)
            assertNotNull(replacement); activity = replacement as MainActivity
            val restored = settings(activity)
            instrumentation.runOnMainSync {
                assertEquals("Settings", activity.getString(R.string.settings_title))
                assertEquals("sources", restored.savePageState()?.getString("category"))
                assertEquals("https://example.org/fragment-draft.js", (descendants(restored.requireView()).first { it.tag == "settings-source-url" } as EditText).text.toString())
                assertEquals(R.id.settings_page, activity.navigationController().currentDestination?.id)
                activity.closeSettings()
            }
            await { activity.settingsFragment() == null }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            preferences.edit().apply { saved.forEach { (key, value) -> if (value is String) putString(key, value) else remove(key) } }.commit()
        }
    }
    @Test fun darkEnglishLargeFontsAndTogglePersistenceRemainUsable() {
        val preferences = instrumentation.targetContext.getSharedPreferences("app-settings", 0)
        val keys = listOf("common.langId", "common.fontScale", "theme.id", "common.isAutoTheme", "player.startupAutoPlay")
        val saved = keys.associateWith { preferences.all[it] }
        preferences.edit().putString("common.langId", "en").putString("common.fontScale", "1.3").putString("theme.id", "black").putBoolean("common.isAutoTheme", false).commit()
        var activity: MainActivity? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val current = start(); activity = current
            instrumentation.runOnMainSync {
                assertEquals("Settings", current.getString(R.string.settings_title))
                val page = SettingsPage(current, scope, { null }, noActions, AdaptiveLayout(360, 800), Bundle().apply { putString("category", "playback") }) {}
                val root = page.createContent(); measure(root, 360, 800)
                val toggle = descendants(root).filterIsInstance<Switch>().first { it.tag == "settings-player.startupAutoPlay" }
                val old = toggle.isChecked; toggle.performClick()
                assertEquals(!old, preferences.getBoolean("player.startupAutoPlay", false))
                descendants(root).first { it.tag == "settings-category-basic" }.performClick(); measure(root, 360, 800)
                capture("360-dark-english-basic", root)
                descendants(root).first { it.tag == "settings-category-sources" }.performClick(); measure(root, 360, 800)
                val button = descendants(root).first { it.tag == "settings-source-import" } as TextView
                assertEquals("Import from URL", button.text.toString()); assertTrue(button.width > 0)
                capture("360-dark-english-sources", root)
            }
        } finally {
            scope.cancel(); activity?.let { instrumentation.runOnMainSync { it.finish() } }
            preferences.edit().apply { saved.forEach { (key, value) -> when (value) { is String -> putString(key, value); is Boolean -> putBoolean(key, value); else -> remove(key) } } }.commit()
        }
    }
}
