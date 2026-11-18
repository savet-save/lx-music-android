package cn.toside.music.nativeapp.ui

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.ListView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.AppPreferences
import cn.toside.music.nativeapp.source.UserSourceLibrary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class BilibiliSettingsUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun awaitUi(check: () -> Boolean) {
        repeat(200) {
            var ready = false
            main { ready = check() }
            if (ready) return
            SystemClock.sleep(50)
        }
        error("Timed out waiting for UI")
    }
    private fun menu(tag: String): ListView? = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull {
        it.findViewWithTag<ListView>(tag)?.takeIf(View::isShown)
    }
    private fun dismiss(activity: MainActivity, name: String) = main {
        (MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as PlatformPopup).dismiss()
    }
    private fun openSearch(activity: MainActivity) = main {
        MainActivity::class.java.getDeclaredMethod("openSearchPage", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
    }
    private fun showSources(activity: MainActivity) = main {
        MainActivity::class.java.getDeclaredMethod("showSearchSources").apply { isAccessible = true }.invoke(activity)
    }

    @Test fun searchOnlyOffersBilibiliWhenItsBundledSourceIsEnabledAndResetsAfterDisabling() = runBlocking {
        val activity = ForegroundTestActivities.start(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        awaitUi { activity.settingsPlayer() != null }
        val player = requireNotNull(activity.settingsPlayer())
        val before = player.sourceCatalog()
        val library = UserSourceLibrary(context)
        val bili = before.entries.single { library.isBilibili(it.id) }
        try {
            player.setEnabledSources(emptyList())
            openSearch(activity); showSources(activity)
            awaitUi { menu("platform-menu") != null }
            main {
                val list = menu("platform-menu")!!
                assertTrue((0 until list.count).none { list.getItemAtPosition(it) == "bilibili" })
            }
            dismiss(activity, "platformPopup")
            player.setSourceEnabled(bili.id, true)
            showSources(activity)
            awaitUi { menu("platform-menu") != null }
            main {
                val list = menu("platform-menu")!!
                val position = (0 until list.count).single { list.getItemAtPosition(it) == "bilibili" }
                assertTrue(list.adapter.isEnabled(position))
                list.performItemClick(list.getChildAt(position - list.firstVisiblePosition), position, list.adapter.getItemId(position))
                assertEquals("bilibili", activity.window.decorView.findViewWithTag<TextView>("search-source").text.toString())
            }
            player.setSourceEnabled(bili.id, false)
            main { activity.refreshSettingsPreferences() }
            awaitUi { activity.window.decorView.findViewWithTag<TextView>("search-source").text.toString() == activity.getString(R.string.search_all_sources) }
            showSources(activity)
            awaitUi { menu("platform-menu") != null }
            main { assertTrue((0 until menu("platform-menu")!!.count).none { menu("platform-menu")!!.getItemAtPosition(it) == "bilibili" }) }
        } finally {
            dismiss(activity, "platformPopup"); player.setEnabledSources(before.enabled); main { activity.finish() }
        }
    }

    @Test fun lyricChoicePersistsAndOnlyBundledSourcesHaveDisabledDeleteRows() = runBlocking {
        val preferences = AppPreferences(context)
        val oldMode = preferences.string("bilibili.lyricsMode", "match")
        val activity = ForegroundTestActivities.start(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        awaitUi { activity.settingsPlayer() != null }
        val player = requireNotNull(activity.settingsPlayer())
        val before = player.sourceCatalog()
        val library = UserSourceLibrary(context)
        val imported = library.addValidated("/**\n * @name Imported fixture ${System.nanoTime()}\n */\nlx.send(lx.EVENT_NAMES.inited,{sources:{}});")
        try {
            preferences.set("bilibili.lyricsMode", "match")
            main { activity.openSettings("sources") }
            awaitUi { activity.window.decorView.findViewWithTag<View>("settings-bilibili.lyricsMode") != null }
            main {
                activity.window.decorView.findViewWithTag<View>("settings-bilibili.lyricsMode").performClick()
            }
            awaitUi { menu("settings-choice-menu") != null }
            main {
                val list = menu("settings-choice-menu")!!
                assertEquals(activity.getString(R.string.bili_lyrics_subtitle), list.getItemAtPosition(0))
                list.performItemClick(list.getChildAt(0), 0, list.adapter.getItemId(0))
                assertTrue(AppPreferences(context).preferBilibiliSubtitles)
            }
            for (entry in before.entries.filter { library.isBundled(it.id) } + imported) {
                main {
                    val anchor = activity.window.decorView.findViewWithTag<View>("settings-title")
                    activity.settingsActions().sourceActions(anchor, entry) {}
                }
                awaitUi { menu("app-choice-menu") != null }
                main {
                    val list = menu("app-choice-menu")!!
                    val delete = list.count - 1
                    assertEquals(!library.isBundled(entry.id), list.adapter.isEnabled(delete))
                    val row = list.adapter.getView(delete, null, list) as TextView
                    assertEquals(!library.isBundled(entry.id), row.isEnabled)
                    if (library.isBundled(entry.id)) assertEquals(UiPalette(activity, NativeThemes(activity).active()).faint, row.currentTextColor)
                }
                dismiss(activity, "choicesPopup")
            }
        } finally {
            preferences.set("bilibili.lyricsMode", oldMode)
            library.remove(imported.id); player.setEnabledSources(before.enabled); main { activity.finish() }
        }
    }
}
