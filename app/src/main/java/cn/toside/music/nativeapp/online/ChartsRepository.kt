package cn.toside.music.nativeapp.online

import cn.toside.music.nativeapp.data.Song

internal interface ChartsRepository {
    fun boards(source: String): List<Board>
    suspend fun page(board: Board, page: Int): List<Song>
}

internal class NativeChartsRepository : ChartsRepository {
    override fun boards(source: String): List<Board> = OnlineBoards().let { api ->
        when (source) { "kw" -> KuwoBoards().boards; "kg" -> api.boards; "tx" -> api.qqBoards; "wy" -> api.wyBoards; "mg" -> api.miguBoards; else -> emptyList() }
    }
    override suspend fun page(board: Board, page: Int) = OnlineBoards().songs(board, page)
}

internal fun ChartsRepository.defaultBoard(source: String): Board = boards(source).let { choices ->
    choices.firstOrNull { it.name == "热歌榜" } ?: choices.first()
}
