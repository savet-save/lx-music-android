package cn.toside.music.nativeapp.online

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class CommentsRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val original = Song(1, Uri.EMPTY, "Monsters", "Katie Sky", 217000, "tx", "{}")
    private fun page(id: String, more: Boolean = false) = CommentPage(listOf(MusicComment(id, "name", "text")), 40, more)
    @Test fun unavailableOriginalFallsBackAndPaginationAndRepliesStayOnTheResolvedPlatform() = runBlocking {
        val calls = mutableListOf<String>(); var searches = 0
        val read: ReadComments = { song, _, number, reply ->
            calls.add("${song.source}:$number:$reply")
            if (song.source in setOf("tx", "kw")) throw IOException("unavailable")
            page("$number", true)
        }
        val repository = CommentsRepository(context, original, { read }, {
            searches++; listOf(original.copy(source = "kw"), original.copy(source = "bili"), original.copy(source = "wy"))
        })
        assertEquals("wy", repository.load(true, 1).song.source)
        assertEquals("wy", repository.load(true, 2).song.source)
        assertEquals("wy", repository.load(false, 1, "root").song.source)
        assertEquals(listOf("tx:1:null", "kw:1:null", "wy:1:null", "wy:2:null", "wy:1:root"), calls)
        assertEquals(1, searches)
    }
    @Test fun emptyOriginalAlsoTriesOtherMatchingMusicPlatforms() = runBlocking {
        val repository = CommentsRepository(context, original, {{ song, _, _, _ ->
            if (song.source == "tx") CommentPage(emptyList(), 0, false) else page("fallback")
        }}, { listOf(original.copy(source = "kw")) })
        val result = repository.load(true, 1)
        assertEquals("kw", result.song.source); assertEquals("fallback", result.page.comments.single().id)
    }
    @Test fun noAvailableCommentsKeepAnEmptyResultInsteadOfTreatingItAsAnError() = runBlocking {
        val repository = CommentsRepository(context, original, {{ song, _, _, _ ->
            if (song.source == "tx") CommentPage(emptyList(), 0, false) else throw IOException("unavailable")
        }}, { listOf(original.copy(source = "kw")) })
        val result = repository.load(true, 1)
        assertEquals("tx", result.song.source); assertTrue(result.page.comments.isEmpty())
    }
    @Test fun bilibiliNeverUsesOtherPlatformCommentsEvenWhenEmptyOrClosed() = runBlocking {
        for (closed in listOf(false, true)) {
            val repository = CommentsRepository(context, original.copy(source = "bili"), {{ _, _, _, _ ->
                if (closed) throw IOException("closed") else CommentPage(emptyList(), 0, false)
            }}, { error("Bilibili must not be matched to music platforms") })
            if (closed) {
                try { repository.load(true, 1); fail("Expected closed-section error") } catch (_: IOException) { }
            } else assertTrue(repository.load(true, 1).page.comments.isEmpty())
        }
    }
    @Test fun aFailedNextPageRetriesTheSameProviderAndDoesNotMixLists() = runBlocking {
        var attempts = 0; var searches = 0
        val calls = mutableListOf<String>()
        val repository = CommentsRepository(context, original, {{ song, _, pageNumber, _ ->
            calls.add("${song.source}:$pageNumber")
            if (song.source == "tx") throw IOException("original")
            if (pageNumber == 2 && attempts++ == 0) throw IOException("retry")
            page("$pageNumber", true)
        }}, { searches++; listOf(original.copy(source = "kw")) })
        repository.load(true, 1)
        try { repository.load(true, 2); fail("Expected page failure") } catch (_: IOException) { }
        assertEquals("kw", repository.load(true, 2).song.source)
        assertEquals(1, searches); assertEquals(listOf("tx:1", "kw:1", "kw:2", "kw:2"), calls)
    }
    @Test fun tabChangesKeepTheProviderAndRefreshRetriesTheOriginal() = runBlocking {
        val calls = mutableListOf<String>(); var originalAvailable = false
        val repository = CommentsRepository(context, original, {{ song, hot, _, _ ->
            calls.add("${song.source}:$hot")
            if (song.source == "tx" && !originalAvailable) throw IOException("original")
            page(song.source)
        }}, { listOf(original.copy(source = "wy")) })
        assertEquals("wy", repository.load(true, 1).song.source)
        repository.restart(original = false)
        assertEquals("wy", repository.load(false, 1).song.source)
        originalAvailable = true; repository.restart(original = true)
        assertEquals("tx", repository.load(false, 1).song.source)
        assertEquals(listOf("tx:true", "wy:true", "wy:false", "tx:false"), calls)
    }
    @Test fun cancellationDoesNotTriggerPlatformFallback() = runBlocking {
        val repository = CommentsRepository(context, original, {{ _, _, _, _ -> throw CancellationException("cancelled") }}, { error("Must not match after cancellation") })
        try { repository.load(true, 1); fail("Expected cancellation") } catch (_: CancellationException) { }
    }
}
