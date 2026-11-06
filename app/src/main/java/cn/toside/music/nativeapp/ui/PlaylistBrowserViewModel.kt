package cn.toside.music.nativeapp.ui

import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.toside.music.nativeapp.online.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class PlaylistBrowserState(
    val query: PlaylistBrowseQuery, val items: List<OnlinePlaylist> = emptyList(), val page: Int = 0,
    val loading: Boolean = false, val hasMore: Boolean = true, val error: String? = null
)

internal class PlaylistBrowserViewModel(private val saved: SavedStateHandle, private val repository: PlaylistBrowserRepository) : ViewModel() {
    private val mutable = MutableStateFlow(PlaylistBrowserState(PlaylistBrowseQuery(
        saved["source"] ?: "kw", saved["category"] ?: "全部", saved["categoryName"] ?: "", saved["order"] ?: "new").normalized()))
    val state = mutable.asStateFlow()
    private var request: Job? = null
    private var generation = 0
    var firstVisible: Int
        get() = saved["first"] ?: 0
        set(value) { saved["first"] = value }
    var scrollTop: Int
        get() = saved["top"] ?: 0
        set(value) { saved["top"] = value }
    var gridState: Parcelable?
        get() = saved["grid-state"]
        set(value) { saved["grid-state"] = value }
    fun ensureLoaded() { if (mutable.value.page == 0 && !mutable.value.loading) loadMore() }
    fun select(query: PlaylistBrowseQuery) {
        val normalized = query.normalized()
        if (normalized == mutable.value.query) return
        request?.cancel(); generation++
        saved["source"] = normalized.source; saved["category"] = normalized.category
        saved["categoryName"] = normalized.categoryName; saved["order"] = normalized.order
        firstVisible = 0; scrollTop = 0; gridState = null
        mutable.value = PlaylistBrowserState(normalized)
        loadMore()
    }
    fun loadMore() {
        val before = mutable.value
        if (before.loading || !before.hasMore) return
        val token = ++generation
        mutable.value = before.copy(loading = true, error = null)
        request = viewModelScope.launch {
            try {
                val page = before.page + 1
                val response = repository.page(before.query, page)
                if (generation != token) return@launch
                val merged = (before.items + response).distinctBy { Triple(it.source, it.id, it.digest) }
                mutable.value = before.copy(items = merged, page = page, loading = false, error = null,
                    hasMore = merged.size > before.items.size && !(before.query.source == "kw" && before.query.category.endsWith("-43")))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == token) mutable.value = before.copy(loading = false, error = e.localizedMessage ?: e.javaClass.simpleName) }
        }
    }
}
