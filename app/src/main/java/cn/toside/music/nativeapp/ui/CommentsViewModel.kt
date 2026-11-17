package cn.toside.music.nativeapp.ui

import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.online.CommentsRepository
import cn.toside.music.nativeapp.online.MusicComment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class CommentsState(val requested: Song, val source: Song = requested, val hot: Boolean = true,
    val items: List<MusicComment> = emptyList(), val totals: Map<Boolean, Long> = emptyMap(),
    val page: Int = 0, val hasMore: Boolean = true, val loading: Boolean = false, val error: String? = null)

internal class CommentsViewModel(private val saved: SavedStateHandle, song: Song,
    private val reply: String?, private val repository: CommentsRepository) : ViewModel() {
    private val mutable = MutableStateFlow(CommentsState(song, hot = if (reply == null) saved["hot"] ?: true else false))
    val state = mutable.asStateFlow()
    private var request: Job? = null
    private var generation = 0
    var listState: Parcelable?
        get() = saved["comments-list"]
        set(value) { saved["comments-list"] = value }

    fun ensureLoaded() { if (mutable.value.page == 0 && !mutable.value.loading) loadMore() }
    fun select(hot: Boolean) {
        if (reply != null || mutable.value.hot == hot) return
        request?.cancel(); generation++; listState = null; saved["hot"] = hot
        repository.restart(original = false)
        mutable.value = mutable.value.copy(hot = hot, items = emptyList(), page = 0, hasMore = true, loading = false, error = null)
        loadMore()
    }
    fun refresh() {
        request?.cancel(); generation++; listState = null
        repository.restart(original = true)
        mutable.value = mutable.value.copy(source = mutable.value.requested, items = emptyList(), totals = emptyMap(), page = 0, hasMore = true, loading = false, error = null)
        loadMore()
    }
    fun loadMore() {
        val before = mutable.value
        if (before.loading || !before.hasMore) return
        val token = ++generation
        mutable.value = before.copy(loading = true, error = null)
        request = viewModelScope.launch {
            try {
                val next = before.page + 1
                val result = repository.load(before.hot, next, reply)
                if (token != generation) return@launch
                val merged = (before.items + result.page.comments).distinctBy { it.id.ifBlank { "${it.name}:${it.date}:${it.text}" } }
                mutable.value = before.copy(source = result.song, items = merged, page = next, loading = false, error = null,
                    totals = (if (before.source.source == result.song.source) before.totals else emptyMap()) + (before.hot to result.page.total),
                    hasMore = result.page.hasMore && merged.size > before.items.size)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (token == generation) mutable.value = before.copy(loading = false, error = e.localizedMessage ?: e.javaClass.simpleName)
            }
        }
    }
}
