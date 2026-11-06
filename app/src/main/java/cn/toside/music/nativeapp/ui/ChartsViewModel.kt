package cn.toside.music.nativeapp.ui

import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.online.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class ChartsState(val board: Board, val songs: List<Song> = emptyList(), val page: Int = 0,
    val loading: Boolean = false, val hasMore: Boolean = true, val error: String? = null)

internal class ChartsViewModel(private val saved: SavedStateHandle, private val repository: ChartsRepository) : ViewModel() {
    private val initialSource = saved.get<String>("source")?.takeIf { it in sources } ?: "kw"
    private val initialBoard = repository.boards(initialSource).firstOrNull { it.id == saved.get<String>("board") } ?: repository.defaultBoard(initialSource)
    private val mutable = MutableStateFlow(ChartsState(initialBoard))
    val state = mutable.asStateFlow()
    private var request: Job? = null
    private var generation = 0
    var scrollState: Parcelable?
        get() = saved["list-state"]
        set(value) { saved["list-state"] = value }
    fun choices() = repository.boards(mutable.value.board.source)
    fun selectSource(source: String) { require(source in sources); if (source != mutable.value.board.source) select(repository.defaultBoard(source)) }
    fun select(board: Board) {
        if (board == mutable.value.board) return
        require(board in repository.boards(board.source))
        request?.cancel(); generation++
        saved["source"] = board.source; saved["board"] = board.id; scrollState = null
        mutable.value = ChartsState(board); loadMore()
    }
    fun ensureLoaded() { if (mutable.value.page == 0 && !mutable.value.loading) loadMore() }
    fun loadMore() {
        val before = mutable.value
        if (before.loading || !before.hasMore) return
        val token = ++generation
        mutable.value = before.copy(loading = true, error = null)
        request = viewModelScope.launch {
            try {
                val page = before.page + 1
                val response = repository.page(before.board, page)
                if (token != generation) return@launch
                val songs = (before.songs + response).distinctBy { it.source to it.id }
                mutable.value = before.copy(songs = songs, page = page, loading = false, error = null,
                    hasMore = before.board.source !in setOf("tx", "mg") && response.size >= 100 && songs.size > before.songs.size)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation) mutable.value = before.copy(loading = false, error = e.localizedMessage ?: e.javaClass.simpleName) }
        }
    }
    companion object { val sources = listOf("kw", "kg", "tx", "wy", "mg") }
}
