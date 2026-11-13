package cn.toside.music.nativeapp.source

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UserSourceLibraryTest {
    private suspend fun isolated(test: suspend (android.content.Context, UserSource) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "source-library-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) { override fun getFilesDir(): File = directory }
        val source = UserSource(context)
        try { test(context, source) } finally { source.close(); directory.deleteRecursively() }
    }
    private fun script(name: String) = """
        /**
         * @name $name
         * @version 1.2.3
         * @author test
         */
        lx.on(lx.EVENT_NAMES.request, async () => {
            if (lx.currentScriptInfo.name !== '$name' || lx.currentScriptInfo.version !== '1.2.3') throw new Error('metadata mismatch');
            return 'https://example.org/$name.mp3';
        });
        lx.send(lx.EVENT_NAMES.inited, {sources:{kw:{type:'music',actions:['musicUrl'],qualitys:['128k']}}});
    """.trimIndent()
    @Test fun switchingDisablingAndDeletingPreserveTheOtherValidatedScripts() = runBlocking {
        isolated { _, source ->
            val bundled = source.catalog().entries.map { it.id }
            source.import(script("first")); val first = source.catalog().selected!!
            source.import(script("second")); val second = source.catalog().selected!!
            assertEquals(4, source.catalog().entries.size)
            source.select(first)
            assertEquals("https://example.org/first.mp3", source.musicUrl("kw", JSONObject()))
            try { source.import("throw Error('bad source')"); fail("invalid source must fail") } catch (_: Exception) { }
            assertEquals(first, source.catalog().selected)
            assertEquals("https://example.org/first.mp3", source.musicUrl("kw", JSONObject()))
            source.select(null)
            assertFalse(source.loadSaved())
            try { source.musicUrl("kw", JSONObject()); fail("disabled source must fail") } catch (_: IllegalStateException) { }
            source.remove(first)
            assertEquals(bundled + second, source.catalog().entries.map { it.id })
            source.select(second)
            assertEquals("https://example.org/second.mp3", source.musicUrl("kw", JSONObject()))
            source.remove(second)
            assertEquals(bundled, source.catalog().entries.map { it.id }); assertNull(source.catalog().selected)
            bundled.forEach { source.remove(it) }
            assertTrue(source.catalog().entries.isEmpty()); assertNull(source.catalog().selected)
        }
    }
    @Test fun legacySingleScriptMigratesWithoutChangingItsContentOrMetadata() = runBlocking {
        isolated { context, source ->
            val raw = script("legacy")
            File(context.filesDir, "user-source.js").writeText(raw)
            assertTrue(source.loadSaved())
            val catalog = source.catalog()
            val entry = catalog.entries.single { it.id == catalog.selected }
            assertEquals(raw, entry.script); assertEquals("legacy", entry.name); assertEquals("1.2.3", entry.version)
            assertEquals(3, catalog.entries.size)
            assertEquals("https://example.org/legacy.mp3", source.musicUrl("kw", JSONObject()))
        }
    }
    @Test fun freshLibrarySelectsThePackagedScriptAndRemembersDisableAndRemoval() = runBlocking {
        isolated { context, source ->
            val raw = context.assets.open(UserSourceLibrary.BUNDLED_ASSET).bufferedReader().use { it.readText() }
            val catalog = source.catalog()
            val bundled = catalog.entries.single { it.name == "野花🌷" }
            val kuwo = catalog.entries.single { it.name.startsWith("OIAPI 酷我") }
            assertEquals(2, catalog.entries.size)
            assertEquals("野花🌷", bundled.name); assertEquals("1", bundled.version)
            assertEquals(raw, bundled.script); assertEquals(bundled.id, catalog.selected)
            assertEquals(catalog, UserSourceLibrary(context).read())
            source.select(null)
            assertNull(UserSourceLibrary(context).read().selected)
            assertFalse(source.loadSaved())
            source.remove(bundled.id)
            assertEquals(listOf(kuwo), UserSourceLibrary(context).read().entries)
            source.remove(kuwo.id)
            assertEquals(UserSourceCatalog(null, emptyList()), UserSourceLibrary(context).read())
            assertEquals(UserSourceCatalog(null, emptyList()), UserSourceLibrary(context).read())
        }
    }
    @Test fun existingCatalogAddsBundledSourceOnceAndPreservesSelectionAndUpdatePreferences() = runBlocking {
        isolated { context, source ->
            val raw = script("existing")
            val id = UserSourceLibrary.metadata(raw).id
            File(context.filesDir, "user-sources.json").writeText(JSONObject().put("version", 1).put("selected", id)
                .put("sources", JSONArray().put(JSONObject().put("script", raw).put("allowUpdateAlert", false))).toString())
            val catalog = source.catalog()
            assertEquals(id, catalog.selected); assertEquals(3, catalog.entries.size)
            assertEquals(raw, catalog.entries.first().script); assertFalse(catalog.entries.first().allowUpdateAlert)
            assertEquals("野花🌷", catalog.entries[1].name)
            assertTrue(catalog.entries.last().name.startsWith("OIAPI 酷我"))
            assertEquals(catalog, UserSourceLibrary(context).read())
            assertTrue(source.loadSaved())
            assertEquals("https://example.org/existing.mp3", source.musicUrl("kw", JSONObject()))
        }
    }
    @Test fun disabledAndEmptyExistingCatalogsStayDisabledDuringBundledMigration() = runBlocking {
        isolated { context, source ->
            val target = File(context.filesDir, "user-sources.json")
            for (scripts in listOf(listOf(script("disabled")), emptyList())) {
                target.writeText(JSONObject().put("version", 1).put("selected", JSONObject.NULL)
                    .put("sources", JSONArray().apply { scripts.forEach { put(JSONObject().put("script", it)) } }).toString())
                val catalog = source.catalog()
                assertNull(catalog.selected); assertEquals(scripts.size + 2, catalog.entries.size)
                assertTrue(catalog.entries.any { it.name == "野花🌷" })
                assertTrue(catalog.entries.last().name.startsWith("OIAPI 酷我"))
                assertFalse(source.loadSaved())
                assertEquals(catalog, UserSourceLibrary(context).read())
            }
        }
    }
    @Test fun alreadyImportedBundledScriptIsNotDuplicatedOrReenabled() = runBlocking {
        isolated { context, source ->
            val raw = context.assets.open(UserSourceLibrary.BUNDLED_ASSET).bufferedReader().use { it.readText() }
            File(context.filesDir, "user-sources.json").writeText(JSONObject().put("version", 1).put("selected", JSONObject.NULL)
                .put("sources", JSONArray().put(JSONObject().put("script", raw).put("allowUpdateAlert", false))).toString())
            val catalog = source.catalog()
            assertEquals(2, catalog.entries.size)
            assertEquals(raw, catalog.entries.first().script); assertFalse(catalog.entries.first().allowUpdateAlert)
            assertNull(catalog.selected)
        }
    }
    @Test fun versionTwoAddsKuwoOnceWithoutRestoringRemovedFlowerOrChangingSelection() = runBlocking {
        isolated { context, source ->
            val raw = script("existing-v2")
            val id = UserSourceLibrary.metadata(raw).id
            for (selected in listOf(id, null)) {
                File(context.filesDir, "user-sources.json").writeText(JSONObject().put("version", 2)
                    .put("selected", selected ?: JSONObject.NULL)
                    .put("sources", JSONArray().put(JSONObject().put("script", raw).put("allowUpdateAlert", false))).toString())
                val catalog = source.catalog()
                assertEquals(selected, catalog.selected)
                assertEquals(2, catalog.entries.size)
                assertFalse(catalog.entries.first().allowUpdateAlert)
                assertFalse(catalog.entries.any { it.name == "野花🌷" })
                val kuwo = catalog.entries.single { it.name.startsWith("OIAPI 酷我") }
                assertEquals(catalog, UserSourceLibrary(context).read())
                source.remove(kuwo.id)
                assertEquals(listOf(id), UserSourceLibrary(context).read().entries.map { it.id })
            }
        }
    }
    @Test fun alreadyImportedKuwoKeepsItsPreferencesAndIsNotDuplicatedDuringMigration() = runBlocking {
        isolated { context, source ->
            val raw = context.assets.open(UserSourceLibrary.BUNDLED_KUWO_ASSET).bufferedReader().use { it.readText() }
            File(context.filesDir, "user-sources.json").writeText(JSONObject().put("version", 2).put("selected", JSONObject.NULL)
                .put("sources", JSONArray().put(JSONObject().put("script", raw).put("allowUpdateAlert", false))).toString())
            val catalog = source.catalog()
            assertEquals(raw, catalog.entries.single().script)
            assertFalse(catalog.entries.single().allowUpdateAlert)
            assertNull(catalog.selected)
            source.select(catalog.entries.single().id)
            assertTrue(source.supports("kw", "musicUrl"))
            assertFalse(source.supports("wy", "musicUrl"))
            assertEquals(listOf("320k", "128k"), source.playbackQualities("kw", JSONObject()
                .put("_types", JSONObject().put("320k", JSONObject())), "320k"))
        }
    }
    @Test fun bundledKuwoResolvesTheRequested320kUsingTheNativeRuntime() = runBlocking {
        assumeTrue("Pass liveKuwoSource=true to check the public Kuwo API",
            InstrumentationRegistry.getArguments().getString("liveKuwoSource") == "true")
        isolated { _, source ->
            source.select(source.catalog().entries.single { it.name.startsWith("OIAPI 酷我") }.id)
            val info = JSONObject().put("songmid", "442087398").put("name", "茶花开了").put("singer", "王睿卓")
                .put("_types", JSONObject().put("320k", JSONObject()))
            val url = source.musicUrl("kw", info, "320k", exactQuality = true)
            assertTrue(url.startsWith("https://") || url.startsWith("http://"))
            assertTrue(java.net.URI(url).host.endsWith(".kuwo.cn"))
        }
    }
    @Test fun bundledScriptInitializesWithTheNativeRuntime() = runBlocking {
        assumeTrue("Pass liveBuiltinSource=true to check the bundled source configuration services",
            InstrumentationRegistry.getArguments().getString("liveBuiltinSource") == "true")
        isolated { _, source ->
            assertTrue(source.loadSaved())
            for (platform in listOf("kw", "kg", "tx", "wy", "mg")) {
                assertTrue("Bundled source should support $platform music URLs", source.supports(platform, "musicUrl"))
            }
        }
    }
    @Test fun localLyricsAndScriptUpdatePreferencesWorkThroughTheExistingScriptProtocol() = runBlocking {
        isolated { _, source ->
            source.import("""
                /**
                 * @name Local lyrics
                 */
                lx.on(lx.EVENT_NAMES.request, async ({action}) => {
                    if(action === 'lyric') return {lyric:'[00:01.00]Hello',tlyric:'[00:01.00]你好',rlyric:'[00:01.00]ni hao'};
                    return 'https://example.org/cover.png';
                });
                lx.send(lx.EVENT_NAMES.inited,{sources:{local:{type:'music',actions:['lyric','pic'],qualitys:[]}}});
                lx.send(lx.EVENT_NAMES.updateAlert,{log:'A new version',updateUrl:'https://example.org/update'});
            """.trimIndent())
            assertTrue(source.supports("local", "lyric")); assertTrue(source.supports("local", "pic"))
            val data = source.sourceRequest("local", "lyric", JSONObject()).getJSONObject("data")
            assertEquals("[00:01.00]你好", data.getString("tlyric"))
            val alert = withTimeout(3000) { source.updates.first { it != null } }!!
            assertEquals("A new version", alert.log)
            source.allowUpdates(alert.id, false)
            assertNull(source.updates.value)
            source.select(alert.id)
            assertNull(withTimeoutOrNull(250) { source.updates.first { it != null } })
        }
    }
}
