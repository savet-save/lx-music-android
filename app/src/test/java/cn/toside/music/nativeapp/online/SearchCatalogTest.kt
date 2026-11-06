package cn.toside.music.nativeapp.online

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SearchCatalogTest {
    private data class Item(val source: String, val id: Int, val metadata: String = "$source:$id")

    @Test fun allProvidersStartTogetherAndResultsKeepRankOrderAndIdentity() = runBlocking {
        var started = 0
        val allStarted = CompletableDeferred<Unit>()
        val result = withTimeout(1_000) {
            SearchCatalog.page("query", pageSize = { 3 }, key = { item: Item -> item.source to item.id }) { source, _, page ->
                assertEquals(1, page)
                if (++started == 5) allStarted.complete(Unit)
                allStarted.await()
                delay((5 - SearchCatalog.sources.indexOf(source)) * 5L)
                listOf(Item(source, 1), Item(source, 2), Item(source, 2))
            }
        }
        assertEquals(SearchCatalog.sources.map { Item(it, 1) } + SearchCatalog.sources.map { Item(it, 2) }, result.items)
        assertEquals(SearchCatalog.sources.associateWith { 2 }, result.nextPages)
        assertTrue(result.failedSources.isEmpty())
    }

    @Test fun paginationRetriesFailedPagesAndDoesNotQueryExhaustedProviders() = runBlocking {
        val first = SearchCatalog.page("query", pageSize = { 2 }, key = { item: Item -> item.source to item.id }) { source, _, _ ->
            when (source) {
                "kg" -> throw IOException("offline")
                "kw" -> listOf(Item(source, 1), Item(source, 2))
                else -> emptyList()
            }
        }
        assertEquals(mapOf("kg" to 1, "kw" to 2), first.nextPages)
        assertEquals(setOf("kg"), first.failedSources)
        val requested = mutableListOf<Pair<String, Int>>()
        val second = SearchCatalog.page("query", cursor = first.nextPages, pageSize = { 2 }, key = { item: Item -> item.source to item.id }) { source, _, page ->
            requested += source to page
            if (source == "kg") listOf(Item(source, 1)) else emptyList()
        }
        assertEquals(listOf("kg" to 1, "kw" to 2), requested)
        assertEquals(listOf(Item("kg", 1)), second.items)
        assertFalse(second.hasMore)
    }

    @Test fun aTimedOutProviderDoesNotHideOtherProvidersAndRemainsRetryable() = runBlocking {
        val result = SearchCatalog.page("query", pageSize = { 2 }, key = { item: Item -> item.source to item.id }, timeoutMillis = 30) { source, _, _ ->
            if (source == "kg") { delay(200); emptyList() }
            else if (source == "kw") listOf(Item(source, 1)) else emptyList()
        }
        assertEquals(listOf(Item("kw", 1)), result.items)
        assertEquals(mapOf("kg" to 1), result.nextPages)
        assertEquals(setOf("kg"), result.failedSources)
    }

    @Test fun allFailuresAreReportedInsteadOfBeingAnEmptySuccessfulSearch() = runBlocking {
        try {
            SearchCatalog.page("query", pageSize = { 2 }, key = { item: Item -> item.id }) { _, _, _ -> throw IOException("offline") }
            fail("All-provider failure must be reported")
        } catch (e: SearchUnavailableException) {
            assertEquals(SearchCatalog.sources.toSet(), e.sources)
            assertTrue(e.cause is IOException)
        }
    }

    @Test fun cancellingTheSearchCancelsItsProviderRequests() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var stopped = 0
        val request = async {
            SearchCatalog.page("query", pageSize = { 2 }, key = { item: Item -> item.id }) { _, _, _ ->
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped++ }
            }
        }
        started.await(); request.cancelAndJoin()
        assertTrue(request.isCancelled); assertTrue(stopped > 0)
    }

    @Test fun aSinglePlatformAndAnExhaustedCursorDoNotFanOut() = runBlocking {
        val requested = mutableListOf<String>()
        val result = SearchCatalog.page("query", "tx", pageSize = { 20 }, key = { item: Item -> item.id }) { source, _, _ ->
            requested += source; listOf(Item(source, 1))
        }
        assertEquals(listOf("tx"), requested); assertFalse(result.hasMore)
        val end = SearchCatalog.page("query", "tx", cursor = emptyMap(), pageSize = { 20 }, key = { item: Item -> item.id }) { _, _, _ ->
            error("Exhausted providers must not be queried")
        }
        assertTrue(end.items.isEmpty()); assertFalse(end.hasMore)
    }
}
