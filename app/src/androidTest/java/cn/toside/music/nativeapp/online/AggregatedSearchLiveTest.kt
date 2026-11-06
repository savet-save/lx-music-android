package cn.toside.music.nativeapp.online

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in public metadata check; no audio is resolved or downloaded. */
@RunWith(AndroidJUnit4::class)
class AggregatedSearchLiveTest {
    @Test fun defaultSongAndPlaylistSearchReturnsRealProviderMetadata() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveProviders") == "true")
        val songs = SearchCatalog().songs("周杰伦")
        assertTrue(songs.items.isNotEmpty())
        songs.items.forEach { song ->
            assertTrue(song.source in SearchCatalog.sources)
            assertEquals(song.source, JSONObject(song.sourceInfo!!).getString("source"))
        }
        Log.i("AggregatedSearch", "songs=${songs.items.size}, sources=${songs.items.map { it.source }.toSet()}, unavailable=${songs.failedSources}")
        val playlists = SearchCatalog().playlists("周杰伦")
        assertTrue(playlists.items.isNotEmpty()); assertTrue(playlists.items.all { it.source in SearchCatalog.sources })
        Log.i("AggregatedSearch", "playlists=${playlists.items.size}, sources=${playlists.items.map { it.source }.toSet()}, unavailable=${playlists.failedSources}")
    }
}
