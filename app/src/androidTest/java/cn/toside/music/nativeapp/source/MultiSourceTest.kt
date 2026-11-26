package cn.toside.music.nativeapp.source

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.data.Song
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MultiSourceTest {
    private suspend fun isolated(test: suspend (Context, UserSources, UserSourceLibrary) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(target.cacheDir, "multi-source-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) { override fun getFilesDir() = folder }
        val library = UserSourceLibrary(context)
        library.select(null)
        val sources = UserSources(context)
        try { test(context, sources, library) } finally { sources.close(); folder.deleteRecursively() }
    }
    private fun script(name: String, body: String, qualities: String = "'128k','320k'", platform: String = "kw") = """
        /**
         * @name $name
         */
        let calls = 0;
        lx.on(lx.EVENT_NAMES.request, async ({info}) => { calls++; $body });
        lx.send(lx.EVENT_NAMES.inited,{sources:{$platform:{type:'music',actions:['musicUrl'],qualitys:[$qualities]}}});
    """.trimIndent()
    private val song = Song(42, Uri.EMPTY, "Example", "Artist", 10000, "kw", """{"songmid":42,"_types":{"128k":{},"320k":{}}}""")
    private fun id(script: String) = UserSourceLibrary.metadata(script).id

    @Test fun enableLimitOrderDisableRemovalAndRestartArePersisted() = runBlocking {
        isolated { context, sources, _ ->
            val scripts = (1..4).map { script("source $it", "return 'https://example.org/$it.mp3';") }
            scripts.take(3).forEach { assertTrue(sources.import(it).getBoolean("enabled")) }
            assertFalse(sources.import(scripts[3]).getBoolean("enabled"))
            val ids = scripts.map(::id)
            sources.setEnabledOrder(ids.take(3))
            sources.move(ids[2], -1)
            assertEquals(listOf(ids[0], ids[2], ids[1]), sources.catalog().enabled)
            try { sources.setEnabled(ids[3], true); fail("A fourth source must not be enabled") } catch (_: IllegalArgumentException) { }
            sources.setEnabled(ids[0], false)
            sources.setEnabled(ids[3], true)
            assertEquals(listOf(ids[2], ids[1], ids[3]), sources.catalog().enabled)
            sources.remove(ids[2])
            val restored = UserSourceLibrary(context).read()
            assertEquals(listOf(ids[1], ids[3]), restored.enabled)
            assertTrue(restored.disabledEntries.any { it.id == ids[0] })
            assertFalse(restored.entries.any { it.id == ids[2] })
        }
    }
    @Test fun versionThreeKeepsOnlyItsPreviouslySelectedSourceEnabled() = runBlocking {
        isolated { context, _, library ->
            val scripts = (1..3).map { script("old $it", "return 'https://example.org/$it.mp3';") }
            for (selected in listOf(id(scripts[1]), null)) {
                File(context.filesDir, "user-sources.json").writeText(JSONObject().put("version", 3)
                    .put("selected", selected ?: JSONObject.NULL).put("sources", JSONArray().apply {
                        scripts.forEach { put(JSONObject().put("script", it).put("allowUpdateAlert", false)) }
                    }).toString())
                val data = library.read()
                assertEquals(listOfNotNull(selected), data.enabled)
                assertTrue(data.entries.filter { it.id in scripts.map(::id) }.all { !it.allowUpdateAlert })
                assertFalse(data.enabledEntries.any { it.name == "B站音源" })
                assertEquals(data, UserSourceLibrary(context).read())
            }
        }
    }
    @Test fun versionFourAddsBilibiliDisabledAndPreservesTheThreeSourceOrder() = runBlocking {
        isolated { context, _, library ->
            val scripts=(1..3).map { script("v4 $it","return 'https://example.org/$it.mp3';") }
            val enabled=scripts.map(::id).reversed()
            File(context.filesDir,"user-sources.json").writeText(JSONObject().put("version",4).put("selected",enabled[0]).put("enabled",JSONArray(enabled))
                .put("sources",JSONArray().apply { scripts.forEach { put(JSONObject().put("script",it).put("allowUpdateAlert",false)) } }).toString())
            val migrated=library.read()
            assertEquals(enabled,migrated.enabled)
            val bili=migrated.disabledEntries.single { it.name=="B站音源" }
            assertEquals(4,migrated.entries.size)
            library.remove(bili.id)
            assertEquals(enabled,library.read().enabled)
            assertEquals(3,library.read().entries.size)
        }
    }
    @Test fun refreshingSelectedAddressRetainsItsPlatformAndQualityBeforeFallingBack() = runBlocking {
        isolated { _, sources, _ ->
            val first = script("address refresh", "if (info.type !== '320k' || info.musicInfo.songmid !== 82) throw Error('wrong recording');\n" +
                "if (calls === 1) return 'https://example.org/old.mp3'; throw Error('expired');", platform = "tx")
            val second = script("fallback", "return 'https://example.org/backup.mp3';", platform = "tx")
            listOf(first, second).forEach { sources.import(it) }
            sources.setEnabledOrder(listOf(id(first), id(second)))
            val match = song.copy(id = 82, source = "tx", sourceInfo = """{"songmid":82}""")
            SourceAudioResolver(sources, { _, _ -> listOf(match) }, true).Playback(song, listOf("320k"), this).use { search ->
                assertEquals("https://example.org/old.mp3", search.next().url)
                assertNull(search.refresh())
                assertEquals(id(second), search.next().sourceId)
                try { search.next(); fail("No untried source remains") }
                catch (e: SourceResolutionException) { assertEquals(listOf(id(first), id(second)), e.attemptedSources) }
            }
        }
    }

    @Test fun requestAndBadLinkFailuresAdvanceInTheConfiguredOrder() = runBlocking {
        isolated { _, sources, _ ->
            val a = script("unavailable", "throw Error('unavailable');")
            val b = script("bad link", "return 'https://example.org/bad.mp3';")
            val c = script("working", "return 'https://example.org/good.mp3';")
            listOf(a,b,c).forEach { sources.import(it) }
            sources.setEnabledOrder(listOf(a,b,c).map(::id))
            val checked = mutableListOf<String>()
            val result = SourceAudioResolver(sources, { _, _ -> emptyList() }, false).resolve(song, "320k") { url ->
                checked.add(url)
                check(!url.endsWith("bad.mp3")) { "HTTP 403" }
            }
            assertEquals(id(c), result.sourceId)
            assertEquals(listOf(a,b,c).map(::id), result.attemptedSources)
            assertEquals(listOf("https://example.org/bad.mp3", "https://example.org/good.mp3"), checked)
            assertEquals("320k", result.quality)
        }
    }
    @Test fun unsupported320SkipsToTheNextSourceWithoutReturning128() = runBlocking {
        isolated { _, sources, _ ->
            val low = script("128 only", "return 'https://example.org/128.mp3';", "'128k'")
            val high = script("320", "if(info.type!=='320k') throw Error('quality changed'); return 'https://example.org/320.mp3';")
            listOf(low,high).forEach { sources.import(it) }
            sources.setEnabledOrder(listOf(id(low),id(high)))
            val result = SourceAudioResolver(sources, { _, _ -> error("128-only script must not trigger platform matching") }, true).resolve(song, "320k")
            assertEquals("https://example.org/320.mp3", result.url)
            assertEquals(id(high), result.sourceId)
        }
    }
    @Test fun disabledSourcesAreSkippedAndExhaustionDoesNotLoop() = runBlocking {
        isolated { _, sources, _ ->
            val bad = script("bad", "throw Error('HTTP 429');")
            val disabled = script("disabled", "return 'https://example.org/disabled.mp3';")
            sources.import(bad); sources.import(disabled)
            sources.setEnabledOrder(listOf(id(bad)))
            val resolver = SourceAudioResolver(sources, { _, _ -> emptyList() }, false)
            try { resolver.resolve(song, "320k"); fail("All active sources fail") }
            catch (e: SourceResolutionException) { assertEquals(listOf(id(bad)), e.attemptedSources) }
            sources.setEnabled(id(disabled), true)
            val result = resolver.resolve(song, "320k")
            assertEquals(id(disabled), result.sourceId)
            try { resolver.resolve(song, "320k", result.attemptedSources.toSet()); fail("No untried sources remain") }
            catch (e: SourceResolutionException) { assertEquals(result.attemptedSources, e.attemptedSources) }
        }
    }
    @Test fun malformedAudioAddressDoesNotPreventTryingTheNextSource() = runBlocking {
        isolated { _, sources, _ ->
            val bad = script("malformed URL", "return 'http:///missing-host.mp3';")
            val good = script("valid URL", "return 'https://example.org/320.mp3';")
            sources.import(bad); sources.import(good); sources.setEnabledOrder(listOf(id(bad), id(good)))
            val result = SourceAudioResolver(sources, { _, _ -> emptyList() }, false).resolve(song, "320k")
            assertEquals(id(good), result.sourceId)
            assertEquals(listOf(id(bad), id(good)), result.attemptedSources)
        }
    }
    @Test fun playbackTriesSamePlatformInQualityAndSourceOrderBeforeMatchingOnce() = runBlocking {
        isolated { _, sources, _ ->
            val other = script("other platform", "if(calls !== 1) throw Error('duplicate'); return 'https://example.org/tx.mp3';", "'320k'", "tx")
            val low = script("low only", "if(calls !== 1) throw Error('duplicate'); return 'https://example.org/low.mp3';", "'128k'")
            val direct = script("both qualities", "if(calls > 2) throw Error('duplicate'); return 'https://example.org/' + info.type + '.mp3';")
            val scripts = listOf(other, low, direct)
            scripts.forEach { sources.import(it) }
            sources.setEnabledOrder(scripts.map(::id))
            var searches = 0
            val resolver = SourceAudioResolver(sources, { _, platforms ->
                assertEquals(setOf("tx"), platforms)
                searches++
                listOf(song.copy(source = "tx"))
            }, true)
            resolver.Playback(song, listOf("320k", "128k"), this).use { search ->
                assertEquals("https://example.org/320k.mp3", search.next().url)
                assertEquals("https://example.org/low.mp3", search.next().url)
                assertEquals("https://example.org/128k.mp3", search.next().url)
                assertEquals(0, searches)
                assertEquals("https://example.org/tx.mp3", search.next().url)
                repeat(2) {
                    try { search.next(); fail("All candidates have already been tried") }
                    catch (e: SourceResolutionException) { assertFalse(e.message.orEmpty().contains("duplicate")) }
                }
            }
            assertEquals(1, searches)
        }
    }

    @Test fun playbackSkipsMatchingWhenEnabledSourcesHaveNoCompatibleAlternative() = runBlocking {
        isolated { _, sources, _ ->
            val direct = script("same platform unavailable", "throw Error('unavailable');")
            val other = script("other platform flac only", "throw Error('must not run');", "'flac'", "tx")
            val scripts = listOf(direct, other)
            scripts.forEach { sources.import(it) }
            sources.setEnabledOrder(scripts.map(::id))
            SourceAudioResolver(sources, { _, _ -> fail("No other platform supports either bitrate"); emptyList() }, true)
                .Playback(song, listOf("320k", "128k"), this).use { search ->
                    try { search.next(); fail("All compatible candidates fail") }
                    catch (e: SourceResolutionException) { assertEquals(listOf(id(direct)), e.attemptedSources) }
                }
        }
    }

    @Test fun slowPreferredSourceLetsTheNextSourceStartAndReusesItsPendingResult() = runBlocking {
        isolated { _, sources, _ ->
            val preferred = script("slow preferred", "return await new Promise(resolve => setTimeout(() => resolve('https://example.org/old.mp3?calls=' + calls), 3000));")
            val unsupported = script("other platform", "throw Error('must not request');", platform = "wy")
            val fallback = script("fast fallback", "return 'https://example.org/new.mp3?calls=' + calls;")
            val entries = listOf(preferred, unsupported, fallback)
            entries.forEach { sources.import(it) }
            sources.setEnabledOrder(entries.map(::id))
            SourceAudioResolver(sources, { _, _ -> emptyList() }, false).Playback(song, listOf("320k"), this).use { search ->
                assertEquals("https://example.org/new.mp3?calls=1", withTimeout(2500) { search.next().url })
                assertEquals("https://example.org/old.mp3?calls=1", search.next().url)
            }
        }
    }

    @Test fun scriptStateIsIndependentAndCancellationDoesNotTryTheNextSource() = runBlocking {
        isolated { _, sources, _ ->
            val a = script("counter", "if(calls===1) throw Error('first call fails'); return 'https://example.org/a.mp3';")
            val b = script("other", "return 'https://example.org/b.mp3';")
            sources.import(a); sources.import(b); sources.setEnabledOrder(listOf(id(a), id(b)))
            val resolver = SourceAudioResolver(sources, { _, _ -> emptyList() }, false)
            assertEquals(id(b), resolver.resolve(song, "320k").sourceId)
            assertEquals(id(a), resolver.resolve(song, "320k").sourceId)
            val wait = script("pending", "return await new Promise(()=>{});")
            sources.setEnabled(id(a), false)
            sources.import(wait); sources.setEnabledOrder(listOf(id(wait), id(b)))
            var checked = false
            val result = withTimeoutOrNull(150) { resolver.resolve(song, "320k") { checked = true } }
            assertNull(result)
            assertFalse(checked)
        }
    }
}
