package cn.toside.music.nativeapp.ui

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.online.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class CommentsViewModelTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val song = Song(1, Uri.EMPTY, "Monsters", "Katie Sky", 217000, "kw", "{}")
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private suspend fun await(check: () -> Boolean) = withTimeout(10000) { while (!check()) delay(30) }
    private fun store(model: CommentsViewModel) = ViewModelStore().also { it.put("comments", model) }

    @Test fun switchingTabsCancelsAndDiscardsLateResultsAndSavesTheMode() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val saved = SavedStateHandle()
        val repository = CommentsRepository(instrumentation.targetContext, song, {{ _, hot, _, _ ->
            if (hot) { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
            CommentPage(listOf(MusicComment(if (hot) "old" else "latest", "name", "text")), 1, false)
        }}, { emptyList() })
        lateinit var model: CommentsViewModel
        main { model = CommentsViewModel(saved, song, null, repository); model.ensureLoaded() }
        val owner = store(model)
        try {
            withTimeout(10000) { entered.await() }
            main { model.select(false) }
            await { model.state.value.items.firstOrNull()?.id == "latest" }
            release.complete(Unit); delay(100)
            assertFalse(model.state.value.hot); assertEquals(false, saved.get<Boolean>("hot"))
            assertEquals("latest", model.state.value.items.single().id)
        } finally { release.complete(Unit); main { owner.clear() } }
    }
    @Test fun paginationFailureRetainsRowsAndRetryUsesTheSamePageAndClearsTheError() = runBlocking {
        var failure = true
        val pages = mutableListOf<Int>()
        val repository = CommentsRepository(instrumentation.targetContext, song, {{ _, _, page, _ ->
            pages.add(page)
            if (page == 2 && failure) { failure = false; throw IOException("temporary") }
            CommentPage(listOf(MusicComment("$page", "name", "text")), 2, page == 1)
        }}, { emptyList() })
        lateinit var model: CommentsViewModel
        main { model = CommentsViewModel(SavedStateHandle(), song, null, repository); model.ensureLoaded() }
        val owner = store(model)
        try {
            await { model.state.value.page == 1 }
            main { model.loadMore() }
            await { model.state.value.error != null }
            assertEquals(listOf("1"), model.state.value.items.map { it.id })
            main { model.loadMore() }
            await { model.state.value.page == 2 }
            assertNull(model.state.value.error); assertFalse(model.state.value.hasMore)
            assertEquals(listOf("1", "2"), model.state.value.items.map { it.id })
            assertEquals(listOf(1, 2, 2), pages)
        } finally { main { owner.clear() } }
    }
    @Test fun duplicateLoadsAreIgnoredAndRefreshReplacesTheList() = runBlocking {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val repository = CommentsRepository(instrumentation.targetContext, song, {{ _, _, _, _ ->
            val id = ++calls
            if (id == 1) gate.await()
            CommentPage(listOf(MusicComment("$id", "name", "text")), 1, false)
        }}, { emptyList() })
        lateinit var model: CommentsViewModel
        main { model = CommentsViewModel(SavedStateHandle(), song, null, repository); model.ensureLoaded(); model.ensureLoaded(); model.loadMore() }
        val owner = store(model)
        try {
            gate.complete(Unit); await { model.state.value.page == 1 }
            assertEquals(1, calls)
            main { model.ensureLoaded() }; assertEquals(1, calls)
            main { model.refresh() }; await { model.state.value.items.firstOrNull()?.id == "2" }
            assertEquals(2, calls); assertEquals(1, model.state.value.page)
        } finally { gate.complete(Unit); main { owner.clear() } }
    }
    @Test fun switchingProviderClearsThePreviousProvidersTabCount() = runBlocking {
        val alternative = song.copy(source = "kg")
        val repository = CommentsRepository(instrumentation.targetContext, song, {{ current, hot, _, _ ->
            if (!hot && current.source == song.source) throw IOException("closed")
            CommentPage(listOf(MusicComment(current.source, "name", "text")), if (hot) 344 else 12, false)
        }}, { listOf(alternative) })
        lateinit var model: CommentsViewModel
        main { model = CommentsViewModel(SavedStateHandle(), song, null, repository); model.ensureLoaded() }
        val owner = store(model)
        try {
            await { model.state.value.page == 1 }
            assertEquals(344L, model.state.value.totals[true])
            main { model.select(false) }
            await { model.state.value.source.source == "kg" }
            assertNull(model.state.value.totals[true]); assertEquals(12L, model.state.value.totals[false])
        } finally { main { owner.clear() } }
    }
}
