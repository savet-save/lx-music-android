package cn.toside.music.nativeapp.ui

import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.AppPreferences
import cn.toside.music.nativeapp.data.DislikeStore
import cn.toside.music.nativeapp.data.DownloadTask
import cn.toside.music.nativeapp.data.Downloads
import cn.toside.music.nativeapp.data.ExternalAudio
import cn.toside.music.nativeapp.data.ImportedList
import cn.toside.music.nativeapp.data.LegacyBackup
import cn.toside.music.nativeapp.data.LocalMusic
import cn.toside.music.nativeapp.data.Playlist
import cn.toside.music.nativeapp.data.Playlists
import cn.toside.music.nativeapp.data.RecentSongs
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.lyrics.LrcParser
import cn.toside.music.nativeapp.lyrics.LyricLine
import cn.toside.music.nativeapp.lyrics.Lyrics
import cn.toside.music.nativeapp.online.Board
import cn.toside.music.nativeapp.online.KugouPlaylists
import cn.toside.music.nativeapp.online.KuwoBoards
import cn.toside.music.nativeapp.online.NeteaseApi
import cn.toside.music.nativeapp.online.OnlineBoards
import cn.toside.music.nativeapp.online.OnlinePlaylist
import cn.toside.music.nativeapp.online.OnlinePlaylists
import cn.toside.music.nativeapp.online.PlaylistBrowserRepository
import cn.toside.music.nativeapp.online.NativePlaylistBrowserRepository
import cn.toside.music.nativeapp.online.ChartsRepository
import cn.toside.music.nativeapp.online.NativeChartsRepository
import cn.toside.music.nativeapp.online.OnlineSearch
import cn.toside.music.nativeapp.online.SearchCatalog
import cn.toside.music.nativeapp.online.SearchUnavailableException
import cn.toside.music.nativeapp.online.SearchHistory
import cn.toside.music.nativeapp.online.SearchSuggestions
import cn.toside.music.nativeapp.player.LyricAlignment
import cn.toside.music.nativeapp.player.LyricAppearance
import cn.toside.music.nativeapp.player.LyricOffsets
import cn.toside.music.nativeapp.player.LyricTiming
import cn.toside.music.nativeapp.player.PlaybackService
import cn.toside.music.nativeapp.player.PlayerSettings
import cn.toside.music.nativeapp.player.PlayerState
import cn.toside.music.nativeapp.source.UserSourceEntry
import cn.toside.music.nativeapp.source.UserSourceLibrary
import cn.toside.music.nativeapp.sync.BackgroundSyncSettings
import cn.toside.music.nativeapp.sync.BackgroundSyncState
import cn.toside.music.nativeapp.sync.SyncService
import cn.toside.music.nativeapp.utils.DiagnosticLog
import cn.toside.music.nativeapp.utils.ExternalLinks
import cn.toside.music.nativeapp.utils.LxLink
import cn.toside.music.nativeapp.utils.PermissionPolicy
import cn.toside.music.nativeapp.utils.SongShare
import cn.toside.music.nativeapp.utils.readBounded

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.app.AlertDialog
import android.app.Dialog
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(UiPreferences.context(newBase)) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var service: PlaybackService? = null
    private var bound = false
    private var incomingHandled = false
    private var startupPlayerPending = false
    private var stateJob: Job? = null
    private var loadJob: Job? = null
    private var suggestionJob: Job? = null
    private var syncService: SyncService? = null
    private var syncBound = false
    private var syncObserverJob: Job? = null
    private var playlistChangesJob: Job? = null
    private var syncConflictId: Long? = null
    private var allSongs = emptyList<Song>()
    private var visibleSongs = emptyList<Song>()
    private lateinit var playlists: Playlists
    private lateinit var lyrics: Lyrics
    private lateinit var playerSettings: PlayerSettings
    private lateinit var lyricOffsets: LyricOffsets
    private var lyricDelayMs = 0
    private var lyricAppearance = LyricAppearance(21, LyricAlignment.LEFT)
    private var pendingSettingsState: Bundle? = null
    private var navigation: NavController? = null
    internal var playlistBrowserRepository: PlaylistBrowserRepository = NativePlaylistBrowserRepository()
    internal var chartsRepository: ChartsRepository = NativeChartsRepository()
    internal var onlinePlaylistSongs: suspend (OnlinePlaylist, Int) -> List<Song> = { playlist, page -> OnlinePlaylists(applicationContext).songs(playlist, page) }
    private lateinit var musicContent: View
    private var pageSidebar: View? = null
    private var settingsAppearancePosted = false
    private var settingsRecreateRequested = false
    private var sourceUpdateJob: Job? = null
    private var sourceUpdateNoticeKey: String? = null
    private var pendingSourceExportId: String? = null
    private var pactDialog: Dialog? = null
    private lateinit var preferences: AppPreferences
    private lateinit var palette: UiPalette
    private lateinit var uiLayout: AdaptiveLayout
    private lateinit var lx: LxStyles
    private val navigationItems = mutableMapOf<Int, TextView>()
    private var navigationDrawer: HomeDrawerLayout? = null
    private var playlistDrawer: HomeDrawerLayout? = null
    private var playlistPicker: PlaylistPicker? = null
    private var playlistPickerJob: Job? = null
    private val playlistActionsPopup = PlatformPopup("playlist-actions", outlined = true)
    private val songActionsPopup = PlatformPopup("song-actions", outlined = true)
    private val choicesPopup = PlatformPopup("app-choice")
    private val playlistDrawerBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { playlistDrawer?.close() }
    }
    private var restoreNavigation = false
    private var tableHeader: FrameLayout? = null
    private var playerRestorePending = false
    private var returnToPlayerFromSettings = false
    private var restoreDetailLyrics = false
    private var highlightedSong: String? = null
    private lateinit var miniBar: LinearLayout
    private var artworkKey: String? = null
    private var artworkBitmap: Bitmap? = null
    private var artworkJob: Job? = null
    private lateinit var miniCoverImage: ImageView
    private var lyricKey: String? = null
    private var pendingLyricSongId: Long? = null
    private var pendingLyricSource: String = "local"
    private var lyricLines = emptyList<LyricLine>()
    private var lyricJob: Job? = null
    private var activePlaylist: Playlist? = null
    private var onlineMode = false
    private var boardMode = false
    private var onlinePlaylistMode = false
    private var selectedOnlinePlaylist: OnlinePlaylist? = null
    private var onlinePlaylistPage = 0
    private var selectedBoard: Board? = null
    private var boardPage = 0
    private data class DownloadRequest(val song: Song, val quality: String, val retryKey: String? = null)
    private var pendingDownload: DownloadRequest? = null
    private var waitingForDownloadSettings = false
    private var waitingForServiceDownload: DownloadRequest? = null
    private var downloadQualityDialog: Dialog? = null
    private sealed interface AudioAction {
        data object OpenLibrary : AudioAction
        data object Resume : AudioAction
        data class Play(val songs: List<Song>, val position: Int) : AudioAction
        data class RecoverPlay(val id: Long, val source: String, val playlistId: Long?) : AudioAction
        data class ImportBackup(val uri: Uri) : AudioAction
        data class ExportBackup(val uri: Uri) : AudioAction
    }
    private var pendingAudioAction: AudioAction? = null
    private var waitingForAudioSettings = false
    private var waitingForServicePlayback: AudioAction? = null
    private var searchSource = SearchCatalog.ALL
    private val platformPopup = PlatformPopup()
    private var searchCatalog = SearchCatalog()
    private var playlistSearchMode = false
    private var playlistSearchResults = emptyList<OnlinePlaylist>()
    private var searchNextPages = emptyMap<String, Int>()
    private var catalogLoading = false
    private var searchGeneration = 0L
    private lateinit var searchRow: LinearLayout
    private lateinit var searchTabs: LinearLayout
    private lateinit var searchSourceButton: TextView
    private lateinit var searchClearButton: TextView
    private lateinit var headerSyncButton: View
    private lateinit var listHeaderView: LinearLayout
    private lateinit var emptyIcon: TextView
    private lateinit var emptyHint: TextView
    private val searchTabLabels = mutableMapOf<Boolean, TextView>()
    private val searchTabIndicators = mutableMapOf<Boolean, View>()
    private val isCatalogPage get() = onlineMode && !boardMode && !onlinePlaylistMode
    private var searchPage = 0
    private var searchedText = ""
    private var changingSeek = false
    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var time: TextView
    private lateinit var toggle: TextView
    private lateinit var iconFont: Typeface
    private lateinit var seek: SeekBar
    private lateinit var search: EditText
    private lateinit var heading: TextView
    private lateinit var listActionButton: TextView
    private lateinit var pageTitle: TextView
    private lateinit var lyric: TextView
    private lateinit var loadMore: TextView
    private var playerDialog: Dialog? = null
    private var detailTitle: TextView? = null
    private var detailTime: TextView? = null
    private var detailSeek: SeekBar? = null
    private var detailToggle: TextView? = null
    private var detailMode: TextView? = null
    private var detailScroll: PlayerLyricScroll? = null
    private var detailCover: FrameLayout? = null
    private var detailCoverImage: ImageView? = null
    private var detailPageToggle: TextView? = null
    private var detailShowingLyrics = false
    private var detailSeeking = false
    private var detailRenderedLines: List<LyricLine>? = null
    private var detailActiveLine = -1
    private data class PageSnapshot(val songs: List<Song>, val playlist: Playlist?, val board: Board?, val onlinePlaylist: OnlinePlaylist?,
        val online: Boolean, val boards: Boolean, val onlineLists: Boolean, val source: String, val query: String, val searchVisible: Boolean,
        val searchPage: Int, val boardPage: Int, val playlistPage: Int, val more: Boolean, val loading: Boolean, val first: Int, val top: Int,
        val searchPlaylists: Boolean, val foundPlaylists: List<OnlinePlaylist>, val nextPages: Map<String, Int>)
    private class RetainedPageState : ViewModel() { var page: PageSnapshot? = null; var beforePlaylist: PageSnapshot? = null }
    private val retainedPageState by lazy {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RetainedPageState() as T
        })[RetainedPageState::class.java]
    }
    private var pendingScroll: Pair<Int, Int>? = null
    private var restoringSearchText = false
    private var suppressPageNavigation = false
    private var beforePlaylistSaved: Bundle? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as PlaybackService.LocalBinder).service
            stateJob = scope.launch { service?.state?.collectLatest { renderPlayer(it) } }
            lyricJob = scope.launch { service?.lyricState?.collectLatest { value ->
                if (value.key == lyricKey) {
                    lyricLines = value.lines
                    service?.state?.value?.let { updateLyric(it.position); renderDetails(it) }
                    if (value.error != null) lyric.text = value.error
                }
            } }
            artworkJob = scope.launch { service?.artworkState?.collectLatest { value ->
                val song = service?.state?.value?.song
                if (value.key == song?.let { "${it.source}:${it.id}" }) { artworkBitmap = value.bitmap; updateArtwork() }
            } }
            sourceUpdateJob = scope.launch { service?.sourceUpdates?.collectLatest { update ->
                settingsFragment()?.refreshSources()
                val key = update?.let { "${it.id}:${it.log}" }
                if (update != null && key != sourceUpdateNoticeKey && settingsFragment()?.savePageState()?.getString("category") != "sources") {
                    toast(getString(R.string.ui_7894327145b0, update.name))
                }
                sourceUpdateNoticeKey = key
            } }
            waitingForServicePlayback?.let { action -> waitingForServicePlayback = null; performAudioAction(action) }
            waitingForServiceDownload?.let { song -> waitingForServiceDownload = null; downloadSong(song) }
            if (intent.getBooleanExtra("open-player", false)) { intent.removeExtra("open-player"); showPlayerDetails() }
            handleIncomingIntent()
            settingsFragment()?.refreshSources()
            settingsFragment()?.refreshPlayback()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; stateJob?.cancel(); lyricJob?.cancel(); artworkJob?.cancel(); sourceUpdateJob?.cancel() }
    }
    private val syncConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            syncService = (binder as SyncService.LocalBinder).service
            syncObserverJob = scope.launch { syncService?.state?.collectLatest { renderSyncState(it) } }
        }
        override fun onServiceDisconnected(name: ComponentName?) { syncService = null; syncObserverJob?.cancel() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        val activeTheme = NativeThemes(this).active()
        setTheme(if (activeTheme.dark) R.style.NativeDark else R.style.NativeLight)
        super.onCreate(savedInstanceState)
        beforePlaylistSaved = savedInstanceState?.getBundle("music-before-playlist")
        pendingSettingsState = savedInstanceState?.getBundle("settings-state")?.takeIf { settingsFragment() == null }
        restoreNavigation = savedInstanceState?.getBoolean("navigation-open") == true
        palette = UiPalette(this, activeTheme)
        uiLayout = AdaptiveLayout(resources.configuration.screenWidthDp, resources.configuration.screenHeightDp)
        playlists = Playlists(applicationContext)
        lyrics = Lyrics(applicationContext)
        playerSettings = PlayerSettings(applicationContext)
        lyricOffsets = LyricOffsets(applicationContext)
        lyricAppearance = playerSettings.appearance(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        preferences = AppPreferences(applicationContext)
        startupPlayerPending = savedInstanceState == null && preferences.startupPlayer
        iconFont = Typeface.createFromAsset(assets, "fonts/icomoon.ttf")
        lx = LxStyles(this, palette, iconFont)
        playerRestorePending = savedInstanceState?.getBoolean("player-open") == true
        returnToPlayerFromSettings = savedInstanceState?.getBoolean("settings-return-player") == true
        restoreDetailLyrics = savedInstanceState?.getBoolean("player-lyrics") == true
        pendingLyricSongId = savedInstanceState?.let { if (it.containsKey("lyricSongId")) it.getLong("lyricSongId") else null }
        pendingLyricSource = savedInstanceState?.getString("lyricSource") ?: "local"
        pendingDownload = savedInstanceState?.getBundle("pendingDownload")?.let(::downloadRequestFromBundle)
        waitingForServiceDownload = savedInstanceState?.getBundle("waitingForServiceDownload")?.let(::downloadRequestFromBundle)
        waitingForDownloadSettings = savedInstanceState?.getBoolean("waitingForDownloadSettings", false) ?: false
        pendingAudioAction = savedInstanceState?.getBundle("pendingAudioAction")?.let(::audioActionFromBundle)
        pendingSourceExportId = savedInstanceState?.getString("source-export-id")
        incomingHandled = savedInstanceState?.getBoolean("incoming-handled", false) ?: false
        waitingForAudioSettings = savedInstanceState?.getBoolean("waitingForAudioSettings", false) ?: false
        buildUi()
        initializeNavigation()
        if (preferences.bool("common.isAgreePact")) {
            if (!restorePage(savedInstanceState)) { if (hasAudioPermission()) loadSongs() else showLocalPermissionPrompt() }
            if (savedInstanceState?.getBoolean("playlist-drawer-open") == true) window.decorView.post {
                if (navigation?.currentDestination?.id == R.id.music_page) showPlaylists()
            }
        } else pactDialog = showPact(this, scope) {
            if (hasAudioPermission()) loadSongs() else showLocalPermissionPrompt()
            startAppServices()
        }
    }
    override fun onStart() {
        super.onStart()
        if (preferences.bool("common.isAgreePact")) startAppServices()
    }
    private fun startAppServices() {
        if (bound) return
        bound = bindService(Intent(this, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
        syncBound = bindService(Intent(this, SyncService::class.java), syncConnection, Context.BIND_AUTO_CREATE)
        playlistChangesJob = scope.launch { playlists.changes.onStart { emit(Unit) }.collectLatest { refreshSynchronizedPlaylist() } }
        try { SyncService.resumeIfEnabled(this) }
        catch (e: RuntimeException) { toast(getString(R.string.ui_671ad117f5de, java.lang.String.valueOf(e.localizedMessage))) }
        checkNativeUpdatesOnStartup(this, scope) { release -> toast(getString(R.string.update_available_hint, release.version)) }
    }
    override fun onStop() {
        platformPopup.dismiss(); playlistActionsPopup.dismiss(); songActionsPopup.dismiss(); choicesPopup.dismiss()
        stateJob?.cancel()
        lyricJob?.cancel()
        artworkJob?.cancel()
        sourceUpdateJob?.cancel()
        suggestionJob?.cancel()
        if (bound) { unbindService(connection); bound = false; service = null }
        syncObserverJob?.cancel(); playlistChangesJob?.cancel()
        if (syncBound) { unbindService(syncConnection); syncBound = false; syncService = null }
        super.onStop()
    }
    override fun onResume() {
        super.onResume()
        service?.refreshLyricPreferences()
        if (waitingForAudioSettings) {
            waitingForAudioSettings = false
            val action = pendingAudioAction; pendingAudioAction = null
            if (hasAudioPermission() && action != null) performAudioAction(action)
            else if (action != null) onAudioAccessDenied(action)
        }
        if (waitingForDownloadSettings) {
            waitingForDownloadSettings = false
            val song = pendingDownload; pendingDownload = null
            val permission = PermissionPolicy.downloadWrite(Build.VERSION.SDK_INT)
            if (song != null && (permission == null || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)) downloadSong(song)
            else if (song != null) toast(getString(R.string.ui_cbe111f9db69))
        }
    }
    override fun onPostResume() {
        super.onPostResume()
        pendingSettingsState?.let { saved -> pendingSettingsState = null; openSettings(state = saved) }
    }
    override fun onDestroy() { pactDialog?.dismiss(); playerDialog?.dismiss(); downloadQualityDialog?.dismiss(); scope.cancel(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        val page = pageSnapshot()
        retainedPageState.page = page
        outState.putBoolean("navigation-open", navigationDrawer?.isOpen == true)
        outState.putBoolean("playlist-drawer-open", playlistDrawer?.isOpen == true)
        saveMusicPage(page, outState)
        outState.putBundle("music-before-playlist", retainedPageState.beforePlaylist?.let { Bundle().apply { saveMusicPage(it, this) } } ?: beforePlaylistSaved)
        outState.putBoolean("incoming-handled", incomingHandled)
        outState.putBoolean("player-open", playerDialog?.isShowing == true || playerRestorePending)
        outState.putBoolean("settings-return-player", returnToPlayerFromSettings)
        outState.putBoolean("player-lyrics", detailShowingLyrics)
        pendingLyricSongId?.let { outState.putLong("lyricSongId", it) }
        outState.putString("lyricSource", pendingLyricSource)
        pendingDownload?.let { outState.putBundle("pendingDownload", downloadRequestToBundle(it)) }
        waitingForServiceDownload?.let { outState.putBundle("waitingForServiceDownload", downloadRequestToBundle(it)) }
        pendingAudioAction?.let { outState.putBundle("pendingAudioAction", audioActionToBundle(it)) }
        outState.putBoolean("waitingForAudioSettings", waitingForAudioSettings)
        outState.putBoolean("waitingForDownloadSettings", waitingForDownloadSettings)
        pendingSourceExportId?.let { outState.putString("source-export-id", it) }
        pendingSettingsState?.let { outState.putBundle("settings-state", it) }
        super.onSaveInstanceState(outState)
    }
    private fun saveMusicPage(page: PageSnapshot, outState: Bundle) {
        outState.putInt("home-mode", when { page.boards -> 2; page.onlineLists -> 3; page.online -> 1; else -> 0 })
        outState.putString("home-source", page.source); outState.putString("home-query", page.query.take(2000)); outState.putBoolean("home-search-visible", page.searchVisible)
        outState.putBoolean("home-search-playlists", page.searchPlaylists)
        outState.putInt("home-first", page.first); outState.putInt("home-top", page.top)
        page.playlist?.let { outState.putLong("home-playlist", it.id) }
        page.board?.let { outState.putStringArray("home-board", arrayOf(it.id, it.name, it.source)) }
        page.onlinePlaylist?.let { outState.putStringArray("home-online-list", arrayOf(it.id, it.digest, it.name, it.author, it.source)) }
    }
    private fun pageSnapshot() = PageSnapshot(allSongs, activePlaylist, selectedBoard, selectedOnlinePlaylist, onlineMode, boardMode, onlinePlaylistMode,
        searchSource, search.text.toString(), search.visibility == View.VISIBLE, searchPage, boardPage, onlinePlaylistPage,
        loadMore.visibility == View.VISIBLE || loadJob?.isActive == true && (allSongs.isNotEmpty() || isCatalogPage && playlistSearchResults.isNotEmpty()), loadJob?.isActive == true,
        pendingScroll?.first ?: list.firstVisiblePosition, pendingScroll?.second ?: (list.getChildAt(0)?.top ?: 0),
        playlistSearchMode, playlistSearchResults, searchNextPages)
    private fun restorePage(saved: Bundle?, restartLoads: Boolean = true): Boolean {
        val previous = suppressPageNavigation
        suppressPageNavigation = true
        try { return restorePageState(saved, restartLoads) } finally { suppressPageNavigation = previous }
    }
    private fun restorePageState(saved: Bundle?, restartLoads: Boolean): Boolean {
        val retained = retainedPageState.page
        if (retained != null) {
            allSongs = retained.songs; activePlaylist = retained.playlist; selectedBoard = retained.board; selectedOnlinePlaylist = retained.onlinePlaylist
            onlineMode = retained.online; boardMode = retained.boards; onlinePlaylistMode = retained.onlineLists; searchSource = retained.source
            playlistSearchMode = retained.searchPlaylists; playlistSearchResults = retained.foundPlaylists; searchNextPages = retained.nextPages
            searchPage = retained.searchPage; searchedText = retained.query; boardPage = retained.boardPage; onlinePlaylistPage = retained.playlistPage
            restoringSearchText = true
            try { search.setText(retained.query) } finally { restoringSearchText = false }
            suggestionJob?.cancel(); setSearchVisible(retained.searchVisible)
            updateListAction()
            pageTitle.text = getString(when { boardMode -> R.string.ui_1ee95aa94712; onlinePlaylistMode -> R.string.ui_473e57b72072; onlineMode -> R.string.ui_44ce7ae909bb; else -> R.string.ui_27c95ec6b766 })
            heading.text = when { boardMode -> "›   ${selectedBoard?.name.orEmpty()}"; onlinePlaylistMode -> "›   ${selectedOnlinePlaylist?.name.orEmpty()}"
                onlineMode -> getString(R.string.ui_e07216a6d505, sourceLabel()); activePlaylist != null -> "›   ${activePlaylist!!.displayName}"; else -> getString(R.string.ui_33eeaa47c231) }
            if (preferences.saveScroll) pendingScroll = retained.first to retained.top
            filterSongs(); loadMore.visibility = if (retained.more) View.VISIBLE else View.GONE
            val empty = if (isCatalogPage && playlistSearchMode) playlistSearchResults.isEmpty() else allSongs.isEmpty()
            if (restartLoads) {
                if (retained.loading && isCatalogPage && !empty && searchPage > 0) searchOnline(append = true)
                else if (empty || retained.loading) reloadRestoredPage()
            }
            return true
        }
        if (saved == null || !saved.containsKey("home-mode")) return false
        searchSource = saved.getString("home-source")?.takeIf { it in SearchCatalog.choices } ?: SearchCatalog.ALL
        playlistSearchMode = saved.getBoolean("home-search-playlists")
        val query = saved.getString("home-query").orEmpty()
        if (preferences.saveScroll) pendingScroll = saved.getInt("home-first") to saved.getInt("home-top")
        when (saved.getInt("home-mode")) {
            1 -> { openSearchPage(false); search.setText(query); if (query.isNotBlank()) searchOnline() }
            2 -> saved.getStringArray("home-board")?.takeIf { it.size == 3 }?.let { loadBoard(Board(it[0], it[1], it[2])) } ?: return false
            3 -> saved.getStringArray("home-online-list")?.takeIf { it.size == 5 }?.let { loadOnlinePlaylist(OnlinePlaylist(it[0], it[1], it[2], it[3], it[4])) } ?: return false
            else -> {
                if (!saved.containsKey("home-playlist")) return false
                scope.launch {
                    val playlist = playlists.list().firstOrNull { it.id == saved.getLong("home-playlist") }
                    suppressPageNavigation = true
                    try {
                        if (playlist != null) { loadPlaylist(playlist); search.setText(query); setSearchVisible(saved.getBoolean("home-search-visible")) }
                        else openLocalPage(false)
                    } finally { suppressPageNavigation = false }
                }
            }
        }
        return true
    }
    private fun reloadRestoredPage() {
        when { boardMode -> selectedBoard?.let { loadBoard(it) }; onlinePlaylistMode -> selectedOnlinePlaylist?.let { loadOnlinePlaylist(it) }
            onlineMode -> if (search.text.isNotBlank()) searchOnline() else { setCatalogStatus("") }
            activePlaylist != null -> { val query = search.text.toString(); loadPlaylist(activePlaylist!!); search.setText(query) }
            hasAudioPermission() -> loadSongs(); else -> showLocalPermissionPrompt() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); incomingHandled = false
        if (intent.getBooleanExtra("open-player", false) && service != null) { intent.removeExtra("open-player"); showPlayerDetails() }
        handleIncomingIntent()
    }
    private fun handleIncomingIntent() {
        if (incomingHandled || service == null || !preferences.bool("common.isAgreePact")) return
        incomingHandled = true
        val incoming = intent
        val uri = incoming.data ?: if (incoming.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        } else null
        val text = incoming.getStringExtra(Intent.EXTRA_TEXT)
        if (uri == null && text.isNullOrBlank()) return
        scope.launch {
            try {
                val link = uri ?: text?.trim()?.takeIf { it.startsWith("lxmusic://") }?.let(Uri::parse)
                if (link?.scheme == "lxmusic") { runLxLink(ExternalLinks.parse(link)); return@launch }
                if (uri == null) { showPlaylistLink(text.orEmpty()); return@launch }
                require(uri.scheme in setOf("file", "content")) { getString(R.string.ui_936ed13655b8) }
                val name = ExternalAudio.name(this@MainActivity, uri)
                val extension = name.substringAfterLast('.', "").lowercase()
                if (extension == "js") {
                    AlertDialog.Builder(this@MainActivity).setTitle(getString(R.string.ui_93c862a05840)).setMessage(name).setNegativeButton(getString(R.string.ui_2cd0f3be8738), null)
                        .setPositiveButton(getString(R.string.ui_576d81bb0631)) { _, _ -> importSourceUri(uri) }.show()
                } else if (extension in setOf("json", "lxmc") || incoming.type == "application/json" || incoming.type == "application/octet-stream" && extension !in setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus")) {
                    onActivityResult(REQUEST_BACKUP, RESULT_OK, Intent().setData(uri))
                } else {
                    require(incoming.type.orEmpty().startsWith("audio/") || extension in setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus")) { getString(R.string.ui_f327410a7cc1) }
                    val song = ExternalAudio.import(this@MainActivity, uri)
                    performAudioAction(AudioAction.Play(listOf(song), 0))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(getString(R.string.ui_11d9e3c1753d, java.lang.String.valueOf(e.localizedMessage))) }
        }
    }
    private fun importSourceUri(uri: Uri) {
        scope.launch {
            try {
                val script = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use { it.readBounded(2 * 1024 * 1024).toString(Charsets.UTF_8) } ?: error(getString(R.string.ui_5903b3b5a9ca)) }
                (service ?: error(getString(R.string.ui_7ca47a28e695))).importSource(script); toast(getString(R.string.ui_18710cc58894))
                settingsFragment()?.refreshSources()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(getString(R.string.ui_0874f11320ab, java.lang.String.valueOf(e.localizedMessage))) }
        }
    }
    private suspend fun runLxLink(link: LxLink) {
        val player = service ?: return
        when (link.type) {
            "player" -> when (link.action) {
                "play" -> toggleResumeFromLink()
                "pause" -> player.pause()
                "skipNext" -> player.next()
                "skipPrev" -> player.previous()
                "togglePlay" -> togglePlayback()
                "collect", "uncollect" -> player.state.value.song?.let { song ->
                    val id = playlists.loveList()
                    if (link.action == "collect") playlists.add(id, song) else playlists.remove(id, song)
                }
                "dislike" -> player.state.value.song?.let { DislikeStore(this).add(it.title, it.artist); player.next() }
                else -> error(getString(R.string.ui_908fd41b724a))
            }
            "songlist" -> {
                require(link.action == "play") { getString(R.string.ui_93f8317b3c21) }
                val source = link.data?.optString("source") ?: link.paths.getOrNull(0).orEmpty()
                val id = link.data?.optString("id")?.takeIf { it.isNotBlank() } ?: link.data?.optString("url")?.takeIf { it.isNotBlank() } ?: link.paths.getOrNull(1).orEmpty()
                val index = link.data?.optInt("index", 1) ?: link.paths.getOrNull(2)?.toIntOrNull() ?: 1
                val list = ExternalLinks.playlist(source, id, applicationContext)
                toast(getString(R.string.ui_477a0fc0b291))
                val songs = ExternalLinks.allSongs(list, applicationContext)
                require(songs.isNotEmpty()) { getString(R.string.ui_ed2156c5f636) }; player.play(songs, (index - 1).coerceIn(0, songs.lastIndex))
            }
            "music" -> {
                if (link.action == "play") {
                    val data = link.data ?: error(getString(R.string.ui_0fea25b96961))
                    require(data.optString("source") in ExternalLinks.sources && data.optString("name").isNotBlank()) { getString(R.string.ui_4f751ba03fed) }
                    val song = LegacyBackup.convert(data, emptyMap()) ?: error(getString(R.string.ui_4f751ba03fed))
                    val next = player.queueState.value?.let { it.index + 1 } ?: 0
                    player.enqueue(listOf(song), true)
                    player.playQueued(next)
                } else if (link.action == "searchPlay") {
                    val name = link.data?.optString("name")?.take(200) ?: link.paths.firstOrNull()?.take(200).orEmpty()
                    require(name.isNotBlank()) { getString(R.string.ui_5bfaaf2346e1) }
                    val singer = link.data?.optString("singer").orEmpty()
                    val split = if (link.data == null && name.contains('-')) name.split('-', limit = 2).map(String::trim) else listOf(name, singer)
                    val duration = link.data?.optString("interval")?.split(':')?.mapNotNull(String::toLongOrNull)?.fold(0L) { total, part -> total * 60 + part }?.times(1000) ?: 0
                    val target = Song(0, Uri.EMPTY, split[0], split.getOrElse(1) { "" }, duration,
                        sourceInfo = org.json.JSONObject().put("albumName", link.data?.optString("albumName").orEmpty()).toString())
                    val song = player.otherSources(target).firstOrNull() ?: error(getString(R.string.ui_6c9ad7b94469))
                    if (link.data?.optBoolean("playLater") == true) player.enqueue(listOf(song)) else {
                        val next = player.queueState.value?.let { it.index + 1 } ?: 0
                        player.enqueue(listOf(song), true); player.playQueued(next)
                    }
                } else error(getString(R.string.ui_bf5201384405))
            }
        }
    }
    private fun toggleResumeFromLink() {
        if (PermissionPolicy.requiresLibraryAccess(this, service?.state?.value?.song) && !hasAudioPermission()) requestAudioAccess(AudioAction.Resume) else service?.resume()
    }
    private fun showPlaylistLink(initial: String = "") {
        val input = EditText(this).apply { hint = getString(R.string.ui_fd2c0650c75b); setText(initial); maxLines = 4 }
        AlertDialog.Builder(this).setTitle(getString(R.string.ui_ac7aa03a458f)).setView(input).setNegativeButton(getString(R.string.ui_2cd0f3be8738), null).setPositiveButton(getString(R.string.ui_acfc4e74a650)) { dialog, _ ->
            val value = input.text.toString().trim()
            val guessed = ExternalLinks.sourceFromUrl(value)
            fun open(source: String) { scope.launch {
                try { loadOnlinePlaylist(ExternalLinks.playlist(source, value, applicationContext)) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { toast(getString(R.string.ui_03c68e4bc44d, java.lang.String.valueOf(e.localizedMessage))) }
            } }
            if (guessed != null) open(guessed) else {
                // Wait until the input dialog has released its window, then reacquire the page anchor.
                (dialog as AlertDialog).setOnDismissListener {
                    window.decorView.post {
                        if (isFinishing || isDestroyed || isChangingConfigurations) return@post
                        val anchor = navigationHost()?.childFragmentManager?.primaryNavigationFragment?.view?.findViewWithTag<View>("playlist-open")
                            ?: if (searchSourceButton.isShown) searchSourceButton else heading
                        val ids = PlatformPopup.sources
                        platformPopup.show(anchor, ids, ids.map { UiPreferences.sourceName(this, it) }, "", palette, alignEnd = true, onSelected = ::open)
                    }
                }
            }
        }.show()
    }
    private fun refreshOnlinePlaylist(playlist: Playlist) {
        val source = playlist.source ?: return; val id = playlist.sourceListId ?: return
        AlertDialog.Builder(this).setTitle(getString(R.string.ui_f5d5d510fdfe)).setMessage(getString(R.string.ui_ca37ac5cc4f6))
            .setNegativeButton(getString(R.string.ui_2cd0f3be8738), null).setPositiveButton(getString(R.string.ui_aee887434131)) { _, _ -> scope.launch {
                try {
                    toast(getString(R.string.ui_129963b15e11))
                    val songs = ExternalLinks.allSongs(OnlinePlaylist(id, if (source == "kw") "8" else "", playlist.name, "", source), applicationContext)
                    playlists.replaceSongs(playlist.id, songs); toast(getString(R.string.ui_07c05b19b201, java.lang.String.valueOf(songs.size)))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { toast(getString(R.string.ui_e7f735120024, java.lang.String.valueOf(e.localizedMessage))) }
            } }.show()
    }
    private fun buildUi() {
        val navContainer = findViewById<FragmentContainerView>(R.id.main_nav_host)
            ?: FragmentContainerView(this).apply { id = R.id.main_nav_host }
        (navContainer.parent as? ViewGroup)?.removeView(navContainer)
        navigationDrawer?.close(false)
        navigationDrawer = null
        playlistDrawer?.close(false)
        navigationItems.clear()
        searchTabLabels.clear(); searchTabIndicators.clear(); tableHeader = null
        val primary = palette.primary
        val foreground = palette.foreground
        val root = HomeNavigationLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = if (uiLayout.expanded) "home-expanded" else "home-compact"
            setBackgroundColor(palette.surface); fitsSystemWindows = true
            eligible = { y ->
                val searchBounds = android.graphics.Rect()
                val location = IntArray(2); getLocationOnScreen(location)
                val inSearch = ::search.isInitialized && search.visibility == View.VISIBLE &&
                    search.getGlobalVisibleRect(searchBounds) && y + location[1] >= searchBounds.top && y + location[1] <= searchBounds.bottom
                navigation?.currentDestination?.id !in setOf(R.id.settings_page, R.id.playlist_browser, R.id.downloads_page) &&
                    navigationDrawer?.isOpen != true && playlistDrawer?.isOpen != true && chartsFragment()?.isBoardDrawerOpen != true &&
                    preferences.bool("common.homePageScroll", true) && (!::miniBar.isInitialized || y < miniBar.top) && !inSearch
            }
            navigate = { direction ->
                val current = when { navigation?.currentDestination?.id == R.id.charts_page -> 2; boardMode -> 2; onlinePlaylistMode -> 3; onlineMode -> 1; else -> 0 }
                when ((current + direction).coerceIn(0, 3).takeIf { it != current }) {
                    0 -> openLocalPage(false)
                    1 -> openSearchPage()
                    2 -> showBoards()
                    3 -> showOnlinePlaylists()
                }
            }
        }
        val workspace = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        musicContent = main
        pageSidebar = if (uiLayout.expanded) buildSidebar() else null
        if (uiLayout.expanded && !preferences.menuOnRight) workspace.addView(pageSidebar, LinearLayout.LayoutParams(dp(uiLayout.sidebarWidth), -1))
        workspace.addView(navContainer, LinearLayout.LayoutParams(0, -1, 1f))
        if (uiLayout.expanded && preferences.menuOnRight) workspace.addView(pageSidebar, LinearLayout.LayoutParams(dp(uiLayout.sidebarWidth), -1))
        val homeContent = if (uiLayout.expanded) workspace else HomeDrawerLayout(this, workspace, buildPhoneNavigation(), preferences.menuOnRight)
            .also { navigationDrawer = it }
        root.addView(homeContent, LinearLayout.LayoutParams(-1, 0, 1f))
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56)
            setPadding(dp(if (uiLayout.expanded) 20 else 4), dp(4), dp(8), dp(4))
        }
        val menu = lx.icon(59680, getString(R.string.ui_e35a36e26d52), 19f) { showNavigation() }
        pageTitle = lx.label(getString(R.string.ui_27c95ec6b766), 19f).apply {
            setPadding(dp(8), 0, dp(8), 0); setTypeface(null, Typeface.BOLD)
        }
        if (!uiLayout.expanded && !preferences.menuOnRight) header.addView(menu, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(pageTitle, LinearLayout.LayoutParams(0, -2, 1f))
        searchTabs = buildSearchTabs()
        header.addView(searchTabs, LinearLayout.LayoutParams(-2, -2))
        headerSyncButton = lx.icon(59687, getString(R.string.ui_727036c7fc2a)) { showSyncSettings() }
        header.addView(headerSyncButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        if (uiLayout.expanded || preferences.menuOnRight) header.addView(menu, LinearLayout.LayoutParams(dp(48), dp(48)))
        main.addView(header)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val picker = PlaylistPicker(this, lx, palette, { it.displayName }, ::selectPlaylist, ::showPlaylistActions)
        playlistPicker = picker
        playlistDrawer = HomeDrawerLayout(this, body, picker, onRight = false, widthFraction = .82f,
            tagPrefix = "playlist-drawer", closeDescription = R.string.playlist_close_picker,
            onOpenChanged = { open ->
                playlistDrawerBack.isEnabled = open
                if (!open) { playlistActionsPopup.dismiss(); playlistPickerJob?.cancel() }
            }).also { main.addView(it, LinearLayout.LayoutParams(-1, 0, 1f)) }
        val listHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(if (uiLayout.expanded) 12 else 0), dp(2), dp(4), dp(2)) }
        listHeaderView = listHeader
        heading = TextView(this).apply {
            text = getString(R.string.ui_33eeaa47c231); textSize = 14f; setTextColor(foreground); gravity = Gravity.CENTER_VERTICAL
            background = lx.touch(); isFocusable = true; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(15), 0, dp(8), 0); setOnClickListener {
                if (boardMode) showBoards() else if (onlinePlaylistMode) showOnlinePlaylists() else if (onlineMode) showSearchSources() else showPlaylists()
            }
            setOnLongClickListener {
                if ((boardMode || onlinePlaylistMode) && allSongs.isNotEmpty()) { saveCurrentOnlineList(); true } else false
            }
        }
        listHeader.addView(heading, LinearLayout.LayoutParams(0, dp(48), 1f))
        listActionButton = TextView(this).apply {
            text = 59660.toChar().toString(); typeface = iconFont; textSize = 18f; gravity = Gravity.CENTER; setTextColor(foreground); contentDescription = getString(R.string.ui_073a0d57165c)
            background = lx.touch(); isFocusable = true
            setOnClickListener {
                if (boardMode || onlinePlaylistMode) showOnlineListActions()
                else setSearchVisible(search.visibility != View.VISIBLE)
            }
            setOnLongClickListener { showBatchSongs(); true }
        }
        listHeader.addView(listActionButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        body.addView(listHeader)
        search = AutoCompleteTextView(this).apply {
            threshold = 1
            setOnItemClickListener { _, _, _, _ -> if (onlineMode && !boardMode && !onlinePlaylistMode) searchOnline() }
            setOnLongClickListener { if (onlineMode) { showSearchTools(); true } else false }
            hint = getString(R.string.ui_7ad45199f05a); textSize = 14f; setSingleLine(true); visibility = View.GONE; imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(16), dp(8), dp(16), dp(8)); setTextColor(foreground); setHintTextColor(palette.muted)
            background = lx.shape(lx.panel, border = palette.divider)
            backgroundTintList = null
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH && onlineMode) { searchOnline(); true } else false
            }
        }
        searchSourceButton = lx.label(sourceLabel(), 14f).apply {
            tag = "search-source"; maxWidth = dp(144); gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0); background = lx.touch(); isFocusable = true
            setOnClickListener { showSearchSources() }
        }
        searchClearButton = lx.label("×", 23f, palette.primary).apply {
            tag = "search-clear"; gravity = Gravity.CENTER; background = lx.touch(); isFocusable = true
            contentDescription = getString(R.string.search_clear)
            setOnClickListener { search.text.clear(); search.requestFocus() }
        }
        searchRow = LinearLayout(this).apply {
            tag = "search-row"; gravity = Gravity.CENTER_VERTICAL
            addView(searchSourceButton, LinearLayout.LayoutParams(-2, dp(48)))
            addView(search, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(searchClearButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        body.addView(searchRow, LinearLayout.LayoutParams(-1, -2))
        body.addView(View(this).apply { setBackgroundColor(palette.divider) }, LinearLayout.LayoutParams(-1, dp(1)))
        status = TextView(this).apply {
            text = getString(R.string.ui_2e14553ec90c); textSize = 12f; setTextColor(palette.muted); setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnClickListener { if (!onlineMode && activePlaylist == null && !hasAudioPermission()) requestAudioAccess(AudioAction.OpenLibrary) }
        }
        body.addView(status)
        if (uiLayout.expanded) {
            tableHeader = FrameLayout(this).apply { addView(buildTableHeader()) }
            body.addView(tableHeader)
        }
        list = ListView(this).apply { divider = null; isVerticalScrollBarEnabled = false }
        list.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val target = view as ListView
            if (target.isShown && target.childCount > 0 && target.firstVisiblePosition == pendingScroll?.first) pendingScroll = null
        }
        val listContainer = FrameLayout(this)
        listContainer.addView(list, FrameLayout.LayoutParams(-1, -1))
        val empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16)); isClickable = false
            emptyIcon = lx.label(59669.toChar().toString(), 52f, palette.primary).apply { typeface = iconFont; gravity = Gravity.CENTER; alpha = .35f }
            addView(emptyIcon)
            emptyHint = lx.label(getString(R.string.layout_empty_hint), 13f, palette.muted).apply {
                tag = "search-empty"
                maxLines = 3; gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0)
            }
            addView(emptyHint, LinearLayout.LayoutParams(-1, -2))
        }
        listContainer.addView(empty, FrameLayout.LayoutParams(-1, -1)); list.emptyView = empty
        body.addView(listContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        loadMore = TextView(this).apply {
            text = getString(R.string.ui_af90a08fec8e); textSize = 13f; gravity = Gravity.CENTER; setTextColor(primary); visibility = View.GONE
            setOnClickListener {
                if (boardMode) selectedBoard?.let { loadBoard(it, append = true) }
                else if (onlinePlaylistMode) selectedOnlinePlaylist?.let { loadOnlinePlaylist(it, append = true) }
                else searchOnline(append = true)
            }
        }
        body.addView(loadMore, LinearLayout.LayoutParams(-1, dp(48)))
        val playerBar = LinearLayout(this).apply {
            tag = "mini-player"; gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL
            setPadding(dp(if (uiLayout.expanded) 20 else 10), dp(8), dp(8), dp(4))
            setBackgroundColor(lx.panel)
        }
        miniBar = playerBar
        val cover = FrameLayout(this).apply {
            background = lx.shape(palette.placeholder); clipToOutline = true
            contentDescription = getString(R.string.ui_f518674b61f6); setOnClickListener { showPlayerDetails() }
        }
        cover.addView(TextView(this).apply { text = "LX"; textSize = 17f; gravity = Gravity.CENTER; setTextColor(primary) }, FrameLayout.LayoutParams(-1, -1))
        miniCoverImage = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        cover.addView(miniCoverImage, FrameLayout.LayoutParams(-1, -1))
        playerBar.addView(cover, LinearLayout.LayoutParams(dp(48), dp(48)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
        title = lx.label(getString(R.string.ui_2389f5b06bce)).apply { setTypeface(null, Typeface.BOLD) }
        info.addView(title)
        lyric = TextView(this).apply { text = ""; textSize = 11f; setTextColor(primary); maxLines = 1 }
        info.addView(lyric)
        time = TextView(this).apply { text = "00:00 / 00:00"; textSize = 11f; setTextColor(palette.muted) }
        info.addView(time)
        info.setOnClickListener { showPlayerDetails() }
        playerBar.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        if (uiLayout.expanded) playerBar.addView(lx.icon(59668, getString(R.string.ui_8c747908bf69), 24f) { service?.previous() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        toggle = TextView(this).apply {
            text = 59653.toChar().toString(); typeface = iconFont; textSize = 24f; gravity = Gravity.CENTER; setTextColor(foreground); contentDescription = getString(R.string.ui_c3396195e91c)
            background = lx.touch(lx.selected, 24); setTextColor(primary); isFocusable = true
            setOnClickListener {
            togglePlayback()
        } }
        playerBar.addView(toggle, LinearLayout.LayoutParams(dp(48), dp(48)))
        playerBar.addView(lx.icon(59654, getString(R.string.ui_486e42475adb), 24f) { service?.next() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        playerBar.addView(lx.icon(59651, getString(R.string.ui_f01b6815d826)) { showPlaybackQueue() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(playerBar)
        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(primary); thumbTintList = ColorStateList.valueOf(primary)
            contentDescription = getString(R.string.ui_fc16e2a0fca6)
        }
        root.addView(seek, LinearLayout.LayoutParams(-1, dp(18)))
        palette.background(root); palette.textStyle(root)
        installHomeContent(root)
        activeMusicFragment()?.refreshContent()
        updateNavigationSelection()
        updateSearchChrome()
        ScreenInsets.install(window, root)
        if (restoreNavigation) { root.post { navigationDrawer?.open(false) }; restoreNavigation = false }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (restoringSearchText) return
                if (!onlineMode) filterSongs() else if (isCatalogPage) {
                    if (s.isNullOrBlank()) resetCatalogResults()
                    else if (catalogLoading) { loadJob?.cancel(); searchGeneration++; catalogLoading = false; setCatalogStatus("") }
                    updateSearchChrome(); updateSuggestions(s?.toString().orEmpty())
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        list.setOnItemClickListener { _, _, position, _ ->
            if (isCatalogPage && playlistSearchMode) {
                playlistSearchResults.getOrNull(position)?.let { loadOnlinePlaylist(it) }
                return@setOnItemClickListener
            }
            val song = visibleSongs.getOrNull(position) ?: return@setOnItemClickListener
            playListedSong(song)
        }
        list.setOnItemLongClickListener { _, row, position, _ ->
            if (isCatalogPage && playlistSearchMode) return@setOnItemLongClickListener false
            val song = visibleSongs.getOrNull(position) ?: return@setOnItemLongClickListener false
            showSongActions(row, song)
            true
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
            override fun onStartTrackingTouch(bar: SeekBar?) { changingSeek = true }
            override fun onStopTrackingTouch(bar: SeekBar?) { changingSeek = false; service?.seekTo(bar?.progress ?: 0) }
        })
    }
    private fun buildSearchTabs() = LinearLayout(this).apply {
        tag = "search-tabs"
        listOf(false to R.string.search_songs, true to R.string.layout_songlists).forEach { (playlists, label) ->
            val text = lx.label(getString(label), 16f).apply { gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), 0) }
            val indicator = View(this@MainActivity).apply { setBackgroundColor(palette.primary); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            val tab = LinearLayout(this@MainActivity).apply {
                tag = if (playlists) "search-tab-playlists" else "search-tab-songs"
                orientation = LinearLayout.VERTICAL; minimumWidth = dp(56); background = lx.touch(); isFocusable = true
                setOnClickListener { selectSearchCategory(playlists) }
                addView(text, LinearLayout.LayoutParams(-2, dp(44)))
                addView(indicator, LinearLayout.LayoutParams(-1, dp(2)).apply { setMargins(dp(8), 0, dp(8), 0) })
            }
            searchTabLabels[playlists] = text; searchTabIndicators[playlists] = indicator
            addView(tab, LinearLayout.LayoutParams(-2, -2))
        }
    }
    private fun selectSearchCategory(playlists: Boolean) {
        if (playlistSearchMode == playlists) return
        playlistSearchMode = playlists
        resetCatalogResults()
        if (search.text.isNotBlank()) searchOnline()
    }
    private fun setSearchVisible(visible: Boolean) {
        search.visibility = if (visible) View.VISIBLE else View.GONE
        updateSearchChrome()
    }
    private fun updateSearchChrome() {
        val catalog = isCatalogPage
        val compact = catalog && !uiLayout.expanded
        searchTabs.visibility = if (catalog) View.VISIBLE else View.GONE
        headerSyncButton.visibility = if (compact) View.GONE else View.VISIBLE
        listHeaderView.visibility = if (compact) View.GONE else View.VISIBLE
        searchTabLabels.forEach { (playlists, label) ->
            val selected = playlistSearchMode == playlists
            label.isSelected = selected; (label.parent as View).isSelected = selected
            label.setTextColor(if (selected) palette.primary else palette.foreground)
            searchTabIndicators.getValue(playlists).visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }
        searchRow.visibility = search.visibility
        (searchRow.layoutParams as LinearLayout.LayoutParams).setMargins(if (compact) 0 else dp(16), if (compact) 0 else dp(8), if (compact) 0 else dp(16), if (compact) 0 else dp(4))
        searchSourceButton.visibility = if (compact) View.VISIBLE else View.GONE
        searchSourceButton.text = sourceLabel()
        searchSourceButton.contentDescription = "${getString(R.string.ui_9b980a0136e4)}: ${sourceLabel()}"
        searchClearButton.visibility = if (catalog) View.VISIBLE else View.GONE
        searchClearButton.isEnabled = search.text.isNotEmpty(); searchClearButton.alpha = if (searchClearButton.isEnabled) .8f else .35f
        search.background = if (compact) null else lx.shape(lx.panel, border = palette.divider)
        search.setPadding(if (compact) 0 else dp(16), dp(8), if (compact) 0 else dp(16), dp(8))
        search.hint = getString(if (compact) R.string.search_placeholder else R.string.ui_7ad45199f05a)
        search.contentDescription = if (catalog) getString(if (playlistSearchMode) R.string.ui_d6319126b753 else R.string.ui_7ad45199f05a) else null
        search.setHintTextColor(if (compact && !palette.theme.dark) Color.argb(128, Color.red(palette.primary), Color.green(palette.primary), Color.blue(palette.primary)) else palette.muted)
        emptyIcon.visibility = if (catalog) View.GONE else View.VISIBLE
        emptyHint.text = getString(when {
            !catalog -> R.string.layout_empty_hint
            catalogLoading -> R.string.ui_c37059a3d9df
            search.text.isBlank() -> R.string.search_empty
            else -> R.string.search_no_results
        })
        emptyHint.textSize = if (catalog) 20f else 13f
        emptyHint.setPadding(0, if (catalog) 0 else dp(16), 0, 0)
        tableHeader?.visibility = if (catalog && playlistSearchMode) View.GONE else View.VISIBLE
        status.visibility = if (compact && status.text.isBlank()) View.GONE else View.VISIBLE
    }
    private fun setCatalogStatus(message: CharSequence) {
        status.setTextColor(palette.muted); status.contentDescription = null; status.text = message
        updateSearchChrome()
    }
    private fun resetCatalogResults() {
        loadJob?.cancel(); suggestionJob?.cancel(); searchGeneration++; catalogLoading = false
        allSongs = emptyList(); visibleSongs = emptyList(); playlistSearchResults = emptyList()
        searchPage = 0; searchedText = ""; searchNextPages = emptyMap(); pendingScroll = null
        list.adapter = null; loadMore.visibility = View.GONE
        (search as? AutoCompleteTextView)?.apply { dismissDropDown(); setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, emptyList<String>())) }
        setCatalogStatus(if (uiLayout.expanded) getString(R.string.ui_bd997b86cde1) else "")
    }
    private fun renderPlaylistSearchResults() {
        updateNavigationSelection()
        setCatalogStatus(getString(R.string.search_playlist_count, playlistSearchResults.size))
        val first = pendingScroll?.first ?: list.firstVisiblePosition
        val top = pendingScroll?.second ?: (list.getChildAt(0)?.top ?: 0)
        list.adapter = object : ArrayAdapter<OnlinePlaylist>(this, android.R.layout.simple_list_item_1, playlistSearchResults) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val playlist = getItem(position)!!
                return LinearLayout(this@MainActivity).apply {
                    tag = "search-playlist-result"; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(64)
                    setPadding(dp(12), dp(8), dp(12), dp(8)); background = lx.touch()
                    addView(lx.label(59659.toChar().toString(), 24f, palette.primary).apply { typeface = iconFont; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(40), dp(48)))
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0)
                        addView(lx.label(playlist.name, 14f))
                        addView(lx.label(listOf(UiPreferences.sourceName(this@MainActivity, playlist.source), playlist.author).filter { it.isNotBlank() }.joinToString(" · "), 11f, palette.muted))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(lx.label("›", 24f, palette.muted))
                    palette.textStyle(this)
                }
            }
        }
        if (first < playlistSearchResults.size) list.setSelectionFromTop(first, top)
    }
    private fun navigateToPage(page: Int) {
        when (page) {
            0 -> if (activePlaylist != null) showPlaylists() else openLocalPage(false)
            1 -> openSearchPage(false)
            2 -> showBoards()
            3 -> showOnlinePlaylists()
            4 -> showDownloads()
        }
    }
    private fun buildPhoneNavigation() = LinearLayout(this).apply {
        tag = "home-drawer-panel"; orientation = LinearLayout.VERTICAL; setBackgroundColor(palette.surface)
        addView(lx.label("LX Music", 28f, palette.primary).apply {
            gravity = Gravity.CENTER; setBackgroundColor(lx.selected)
        }, LinearLayout.LayoutParams(-1, dp(if (uiLayout.compactPlayer) 72 else 144)))
        fun entry(parent: LinearLayout, code: Int, label: Int, page: Int? = null, click: () -> Unit) {
            val row = LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL; background = lx.touch(); minimumHeight = dp(56)
                setPadding(dp(12), dp(4), dp(12), dp(4)); isFocusable = true
                setOnClickListener { navigationDrawer?.close(); click() }
            }
            val icon = if (label == R.string.ui_b0d269c8707c) ImageView(this@MainActivity).apply {
                setImageResource(android.R.drawable.ic_lock_power_off); imageTintList = ColorStateList.valueOf(palette.muted)
                setPadding(dp(11), dp(13), dp(11), dp(13))
            } else lx.label(code.toChar().toString(), 21f, palette.muted).apply { typeface = iconFont; gravity = Gravity.CENTER }
            icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            row.addView(icon, LinearLayout.LayoutParams(dp(44), dp(48)))
            val text = lx.label(getString(label), 16f, palette.muted).apply { maxLines = 2; setPadding(dp(8), 0, 0, 0) }
            row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            if (page != null) { navigationItems[page] = text; row.tag = "navigation-$page" }
            else if (label == R.string.ui_a9531e47b75d) row.tag = "navigation-local"
            parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        val items = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8))
        }
        entry(items, 59660, R.string.layout_search, 1) { navigateToPage(1) }
        entry(items, 59659, R.string.layout_songlists, 3) { navigateToPage(3) }
        entry(items, 59658, R.string.ui_1ee95aa94712, 2) { navigateToPage(2) }
        entry(items, 59657, R.string.ui_27c95ec6b766, 0) { navigateToPage(0) }
        entry(items, 59656, R.string.download_page, 4) { navigateToPage(4) }
        entry(items, 59696, R.string.ui_a9531e47b75d) { openLocalPage(true) }
        entry(items, 59666, R.string.ui_c2dd14825672) { showPlaylists() }
        entry(items, 59655, R.string.settings_title) { showSettings() }
        addView(ScrollView(this@MainActivity).apply { isVerticalScrollBarEnabled = false; addView(items) }, LinearLayout.LayoutParams(-1, 0, 1f))
        entry(this, 59680, R.string.ui_38844b135cf7) { showNavigationActions() }
        if (preferences.showBack) entry(this, 59680, R.string.ui_f75a93326a3c) { moveTaskToBack(true) }
        if (preferences.showExit) entry(this, 59670, R.string.ui_b0d269c8707c) { confirmExit() }
    }
    private fun buildSidebar() = LinearLayout(this).apply {
        tag = "home-sidebar"; orientation = LinearLayout.VERTICAL; setBackgroundColor(lx.panel)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        addView(lx.label("LX Music", 23f, palette.primary).apply {
            setTypeface(null, Typeface.BOLD); setPadding(dp(14), 0, 0, 0)
        }, LinearLayout.LayoutParams(-1, dp(72)))
        val items = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        fun entry(code: Int, label: Int, page: Int? = null, click: () -> Unit) {
            val row = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL; background = lx.touch(); isFocusable = true; setOnClickListener { click() } }
            row.addView(lx.label(code.toChar().toString(), 19f, palette.muted).apply { typeface = iconFont; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(40), dp(48)))
            val text = lx.label(getString(label), 14f, palette.muted)
            row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            if (page != null) { navigationItems[page] = text; row.tag = "navigation-$page" }
            else if (label == R.string.ui_a9531e47b75d) row.tag = "navigation-local"
            items.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        entry(59660, R.string.ui_87a2113eb66f, 1) { navigateToPage(1) }
        entry(59657, R.string.ui_27c95ec6b766, 0) { navigateToPage(0) }
        entry(59658, R.string.ui_1ee95aa94712, 2) { navigateToPage(2) }
        entry(59659, R.string.ui_259597bf6ca7, 3) { navigateToPage(3) }
        entry(59656, R.string.download_page, 4) { navigateToPage(4) }
        items.addView(View(this@MainActivity).apply { setBackgroundColor(palette.divider) }, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(10), dp(16), dp(10), dp(16)) })
        entry(59696, R.string.ui_a9531e47b75d) { openLocalPage(true) }
        entry(59666, R.string.ui_c2dd14825672) { showPlaylists() }
        entry(59687, R.string.ui_727036c7fc2a) { showSyncSettings() }
        entry(59655, R.string.settings_title) { showSettings() }
        entry(59659, R.string.ui_788db1cfec2a) { openSettings("theme") }
        addView(ScrollView(this@MainActivity).apply { isVerticalScrollBarEnabled = false; addView(items) }, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(lx.icon(59680, getString(R.string.ui_e35a36e26d52), 19f) { showNavigation() }, LinearLayout.LayoutParams(-1, dp(48)))
    }
    private fun updateNavigationSelection() {
        val current = when { navigation?.currentDestination?.id == R.id.downloads_page -> 4; navigation?.currentDestination?.id == R.id.playlist_browser -> 3; navigation?.currentDestination?.id == R.id.charts_page -> 2; boardMode -> 2; onlinePlaylistMode -> 3; onlineMode -> 1; else -> 0 }
        navigationItems.forEach { (page, item) ->
            val selected = current == page
            item.isSelected = selected; item.setTextColor(if (selected) palette.primary else palette.muted)
            item.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            val target = item.parent as ViewGroup
            target.isSelected = selected; target.background = lx.touch(if (selected) lx.selected else Color.TRANSPARENT)
            (target.getChildAt(0) as TextView).setTextColor(if (selected) palette.primary else palette.muted)
        }
    }
    private fun buildTableHeader() = LinearLayout(this).apply {
        tag = "song-table-header"; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(lx.panel)
        fun cell(label: String, weight: Float) { addView(lx.label(label, 12f, palette.muted).apply { setPadding(dp(8), 0, dp(8), 0) }, LinearLayout.LayoutParams(0, -2, weight)) }
        addView(lx.label("#", 12f, palette.muted).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(48), dp(36)))
        cell(getString(R.string.layout_track), 2f); cell(getString(R.string.layout_artist), 1f)
        if (uiLayout.albumColumn && preferences.showAlbum) cell(getString(R.string.layout_album), 1f)
        addView(lx.label(getString(R.string.layout_duration), 12f, palette.muted).apply { gravity = Gravity.CENTER; visibility = if (preferences.showDuration) View.VISIBLE else View.GONE }, LinearLayout.LayoutParams(dp(60), -2))
        addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(48), 1))
    }
    private fun showNavigation() {
        playlistDrawer?.close(false)
        val drawer = navigationDrawer
        if (drawer != null) {
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
            drawer.open()
        } else showNavigationActions()
    }
    private fun confirmExit() {
        AlertDialog.Builder(this).setTitle(getString(R.string.ui_0c5f909e92d2)).setMessage(getString(R.string.ui_2474ed447b00))
            .setNegativeButton(getString(R.string.ui_2cd0f3be8738), null).setPositiveButton(getString(R.string.ui_498e1d59b4d7)) { _, _ ->
                service?.pause()
                startService(Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_EXIT))
                startService(Intent(this, SyncService::class.java).setAction(SyncService.ACTION_EXIT))
                finishAndRemoveTask()
            }.show()
    }
    private fun showNavigationActions() {
        val labels = mutableListOf(getString(R.string.ui_a9531e47b75d), getString(R.string.ui_c2dd14825672), getString(R.string.ui_87a2113eb66f), getString(R.string.ui_1ee95aa94712), getString(R.string.ui_259597bf6ca7), getString(R.string.ui_3158c8d56b7b), getString(R.string.ui_a5cd0a9169e8), getString(R.string.ui_3facd3e00be5), getString(R.string.ui_599818eb56df), getString(R.string.ui_727036c7fc2a), getString(R.string.ui_2a3ec620e12c), getString(R.string.ui_8c38525734c0), getString(R.string.ui_486b54a7b0af), getString(R.string.ui_6e763b71e69c), getString(R.string.ui_f01b6815d826), getString(R.string.ui_cad84b75d661), getString(R.string.ui_426744e26e7e), getString(R.string.ui_ac7aa03a458f), getString(R.string.settings_title), getString(R.string.ui_788db1cfec2a), getString(R.string.ui_4fd38bc4f6d4), getString(R.string.ui_bb1d19b85634))
        labels.add(getString(R.string.download_page))
        if (preferences.showBack) labels.add(getString(R.string.ui_f75a93326a3c))
        if (preferences.showExit) labels.add(getString(R.string.ui_b0d269c8707c))
        val anchor = navigationMenuAnchor() ?: return
        choicesPopup.show(anchor, labels.indices.map(Int::toString), labels, "", palette, alignEnd = preferences.menuOnRight) { id ->
            val which = id.toInt()
            if (labels[which] == getString(R.string.download_page)) { showDownloads(); return@show }
            if (labels[which] == getString(R.string.ui_f75a93326a3c)) { moveTaskToBack(true); return@show }
            if (labels[which] == getString(R.string.ui_b0d269c8707c)) {
                confirmExit()
                return@show
            }
            if (which == 0) openLocalPage(true)
            else if (which == 1) showPlaylists()
            else if (which == 2) openSearchPage()
            else if (which == 3) showBoards()
            else if (which == 4) showOnlinePlaylists()
            else if (which == 5) {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, REQUEST_SOURCE)
            } else if (which == 6) {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, REQUEST_BACKUP)
            } else if (which == 7) {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"; putExtra(Intent.EXTRA_TITLE, "lx_list.lxmc")
                }, REQUEST_EXPORT)
            } else if (which == 8) showPlaybackQuality() else if (which == 9) showSyncSettings()
            else if (which == 10) showDislikeRules() else if (which == 11) showPlaybackSettings()
            else if (which == 12) openSettings("desktop-lyrics")
            else if (which == 13) showSources() else if (which == 14) showPlaybackQueue()
            else if (which == 15) showSearchTools() else if (which == 16) showHistory() else if (which == 17) showPlaylistLink()
            else if (which == 18) showSettings()
            else if (which == 19) openSettings("theme")
            else if (which == 20) openSettings("about")
            else if (which == 21) openSettings("diagnostics")
        }
    }
    private fun navigationMenuAnchor(): View? {
        fun find(view: View): View? {
            if (!view.isShown) return null
            if (view.contentDescription == getString(R.string.ui_e35a36e26d52)) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return find(window.decorView)
    }
    private fun openLocalPage(requestPermission: Boolean) {
        playlistDrawer?.close()
        showMusicPage()
        loadJob?.cancel()
        if (onlineMode || activePlaylist != null) {
            allSongs = emptyList()
            pendingScroll = null
        }
        onlineMode = false; boardMode = false; onlinePlaylistMode = false; updateListAction(); pageTitle.text = getString(R.string.ui_27c95ec6b766); loadMore.visibility = View.GONE
        activePlaylist = null; heading.text = getString(R.string.ui_33eeaa47c231); search.text.clear(); setSearchVisible(false)
        if (hasAudioPermission()) {
            filterSongs()
            loadSongs()
        } else {
            showLocalPermissionPrompt()
            if (requestPermission) requestAudioAccess(AudioAction.OpenLibrary)
        }
    }
    private fun openSearchPage(showTools: Boolean = true) {
        playlistDrawer?.close(false)
        showMusicPage()
        onlineMode = true; boardMode = false; onlinePlaylistMode = false; updateListAction(); pageTitle.text = getString(R.string.ui_44ce7ae909bb); activePlaylist = null; heading.text = getString(R.string.ui_e07216a6d505, sourceLabel()); loadMore.visibility = View.GONE
        search.text.clear(); setSearchVisible(true); resetCatalogResults()
        if (showTools && preferences.hotSearch && uiLayout.expanded) showSearchTools()
    }
    private fun showDislikeRules() {
        val store = DislikeStore(applicationContext)
        val input = EditText(this).apply {
            setText(store.get().text); hint = getString(R.string.ui_14b8d12a0ed6)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6; maxLines = 12; gravity = Gravity.TOP
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.ui_2a3ec620e12c)).setView(input).setNegativeButton(getString(R.string.ui_2cd0f3be8738), null)
            .setPositiveButton(getString(R.string.ui_a3030bf8f16d)) { _, _ -> updateDislikeRules { store.save(input.text.toString()) } }
            .setNeutralButton(getString(R.string.ui_1ef3de06b32e)) { _, _ -> updateDislikeRules { store.clear() } }.show()
    }
    private fun updateDislikeRules(change: suspend () -> Unit) {
        scope.launch {
            try { change(); toast(getString(R.string.ui_e2e7a21aa13b)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(getString(R.string.ui_749d84b5fa76, java.lang.String.valueOf(e.localizedMessage))) }
        }
    }
    private fun showDislikeChoices(song: Song) {
        AlertDialog.Builder(this).setTitle(getString(R.string.ui_9b3d69ae09ce))
            .setItems(arrayOf(getString(R.string.ui_a5c2cb376cf6), getString(R.string.ui_3ab737d3e7d1), getString(R.string.ui_024a5e862936))) { _, which ->
                val name = if (which == 2) "" else song.title
                val artist = if (which == 1) "" else song.artist
                if (name.isBlank() && artist.isBlank()) { toast(getString(R.string.ui_b7f9c185ce8e)); return@setItems }
                updateDislikeRules { DislikeStore(applicationContext).add(name, artist) }
            }.setNegativeButton(getString(R.string.ui_2cd0f3be8738), null).show()
    }
    private fun showSyncSettings() = openSettings("sync")
    private fun connectSync(address: String, authCode: String) {
        try {
            SyncService.connect(this, address, authCode)
            val history = getSharedPreferences("permission-history", MODE_PRIVATE)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !history.getBoolean("sync-notification-requested", false)) {
                history.edit().putBoolean("sync-notification-requested", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_SYNC_NOTIFICATION)
            }
        } catch (e: RuntimeException) { toast(getString(R.string.ui_79110e8d2e06, java.lang.String.valueOf(e.localizedMessage))) }
    }
    private fun renderSyncState(value: BackgroundSyncState) {
        settingsFragment()?.renderSyncState(value)
        if (::headerSyncButton.isInitialized) {
            headerSyncButton.isActivated = value.active
            headerSyncButton.contentDescription = getString(R.string.ui_727036c7fc2a) + " · " + syncMessage(value.message)
            (headerSyncButton as? TextView)?.setTextColor(if (value.conflict != null || value.active) palette.primary else palette.muted)
        }
        val request = value.conflict
        if (request != null && syncConflictId != request.id) {
            syncConflictId = request.id
            if (settingsFragment()?.savePageState()?.getString("category") != "sync") toast(getString(R.string.sync_pending_choice))
        }
        if (request == null) syncConflictId = null
    }
    private suspend fun refreshSynchronizedPlaylist() {
        val current = activePlaylist.takeUnless { onlineMode }
        if (current == null && playlistDrawer?.isOpen != true) return
        val items = playlists.list()
        if (playlistDrawer?.isOpen == true) renderPlaylistPicker(items)
        if (current == null) return
        val updated = items.firstOrNull { it.id == current.id }
        val songs = updated?.let { playlists.songs(it.id) }.orEmpty()
        if (onlineMode || activePlaylist?.id != current.id) return
        if (updated == null) {
            activePlaylist = null; allSongs = emptyList(); visibleSongs = emptyList(); list.adapter = null
            heading.text = getString(R.string.ui_3cda5816cb6e); status.text = getString(R.string.ui_e48ff968de78)
        } else {
            activePlaylist = updated; heading.text = "›   ${updated.displayName}"
            allSongs = songs; filterSongs()
        }
    }
    private fun showPlaybackQuality() = openSettings("playback")
    private fun showPlaybackSettings() {
        if (playerDialog?.isShowing == true) {
            returnToPlayerFromSettings = true; restoreDetailLyrics = detailShowingLyrics; playerDialog?.dismiss()
        }
        openSettings("playback")
    }
    private fun playbackSettingsContent(): View? {
        val player = service ?: return null
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return PlayerSettingsContent(this, playerSettings, lyricOffsets, landscape, player.state.value.song,
            player::setPlaybackRate, player::setVolume, {
                lyricAppearance = playerSettings.appearance(landscape)
                detailRenderedLines = null
                updateLyric(player.state.value.position)
                renderDetails(player.state.value)
                player.refreshLyricPreferences()
            }, { song ->
                if (lyricKey == "${song.source}:${song.id}") {
                    lyricDelayMs = lyricOffsets.get(song); player.refreshLyricPreferences()
                    updateLyric(player.state.value.position); renderDetails(player.state.value)
                }
            }).createContent()
    }
    private fun showSettings() = openSettings()
    private fun showSources() = openSettings("sources")
    internal fun openSettings(category: String? = null, state: Bundle? = null) {
        settingsFragment()?.let { existing -> category?.let(existing::selectCategory); return }
        val saved = state ?: Bundle().apply { category?.let { putString("category", it) } }
        if (supportFragmentManager.isStateSaved || navigation == null) { pendingSettingsState = saved; return }
        hidePageKeyboard()
        if (preferences.saveScroll && pendingScroll == null) pendingScroll = list.firstVisiblePosition to (list.getChildAt(0)?.top ?: 0)
        navigation?.navigate(R.id.settings_page, SettingsFragment.create(saved).arguments, NavOptions.Builder().setLaunchSingleTop(true).build())
    }
    private fun navigationHost() = supportFragmentManager.findFragmentById(R.id.main_nav_host) as? NavHostFragment
    internal fun settingsFragment() = navigationHost()?.childFragmentManager?.primaryNavigationFragment as? SettingsFragment
    private fun activeMusicFragment() = navigationHost()?.childFragmentManager?.primaryNavigationFragment as? MusicPageFragment
    internal fun musicPageContent(): View = musicContent
    internal fun navigationController(): NavController = requireNotNull(navigation)
    internal fun openAppNavigation() = showNavigation()
    internal fun openPlaylistLink() = showPlaylistLink()
    internal fun openBrowserPlaylist(playlist: OnlinePlaylist) = loadOnlinePlaylist(playlist)
    internal fun chartsFragment() = navigationHost()?.childFragmentManager?.primaryNavigationFragment as? ChartsFragment
    internal fun playPageSongs(songs: List<Song>, position: Int) {
        val song = songs.getOrNull(position) ?: return
        val action = AudioAction.Play(songs.toList(), position)
        if (PermissionPolicy.requiresLibraryAccess(this, song) && !hasAudioPermission()) requestAudioAccess(action) else performAudioAction(action)
    }
    internal fun openChartSongActions(anchor: View, songs: List<Song>, position: Int) {
        val song = songs.getOrNull(position) ?: return
        showSongActionsFor(anchor, song, null) { playPageSongs(songs, position) }
    }
    private fun playListedSong(song: Song) {
        playPageSongs(allSongs, allSongs.indexOf(song))
    }
    internal fun downloadTableLayout() = uiLayout.contentWidth >= 620
    internal fun searchDownloadedSong(song: Song) {
        openSearchPage(false)
        searchTerm(listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" "))
    }
    private fun showDownloads() {
        val nav = navigation ?: return
        if (supportFragmentManager.isStateSaved || nav.currentDestination?.id == R.id.downloads_page) return
        hidePageKeyboard()
        if (!nav.popBackStack(R.id.downloads_page, false)) nav.navigate(R.id.downloads_page)
    }
    private fun hidePageKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(currentFocus?.windowToken, 0)
        navigationDrawer?.close(false)
    }
    private fun initializeNavigation() {
        val host = navigationHost() ?: NavHostFragment.create(R.navigation.main_navigation).also {
            supportFragmentManager.beginTransaction().replace(R.id.main_nav_host, it).setPrimaryNavigationFragment(it).commitNow()
        }
        navigation = host.navController
        navigation?.addOnDestinationChangedListener { _, destination, _ ->
            choicesPopup.dismiss()
            if (destination.id != R.id.settings_page && returnToPlayerFromSettings) {
                returnToPlayerFromSettings = false
                val lyrics = restoreDetailLyrics
                window.decorView.post {
                    showPlayerDetails()
                    if (lyrics && !uiLayout.splitPlayer) toggleDetailPage()
                }
            }
            if (destination.id != R.id.music_page) playlistDrawer?.close(false)
            if (destination.id == R.id.music_page && (retainedPageState.beforePlaylist != null || beforePlaylistSaved != null)) {
                loadJob?.cancel()
                retainedPageState.page = retainedPageState.beforePlaylist
                val reload = retainedPageState.beforePlaylist?.loading != false
                retainedPageState.beforePlaylist = null
                val saved = beforePlaylistSaved; beforePlaylistSaved = null
                restorePage(saved, restartLoads = reload)
            }
            updateDestinationChrome()
        }
        onBackPressedDispatcher.addCallback(this, playlistDrawerBack)
        updateDestinationChrome()
    }
    private fun showMusicPage() {
        if (suppressPageNavigation) return
        val nav = navigation ?: return
        if (nav.currentDestination?.id != R.id.music_page && !supportFragmentManager.isStateSaved) nav.popBackStack(R.id.music_page, false)
    }
    private fun showPlaylistBrowser() {
        val nav = navigation ?: return
        if (supportFragmentManager.isStateSaved || nav.currentDestination?.id == R.id.playlist_browser) return
        hidePageKeyboard()
        if (!nav.popBackStack(R.id.playlist_browser, false)) nav.navigate(R.id.playlist_browser)
    }
    internal fun settingsPlayer(): PlaybackService? = service
    internal fun settingsActions() = SettingsActions(
            importLocal = {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, REQUEST_SOURCE)
            },
            sourceActions = { anchor, entry, changed -> showSourceActions(anchor, entry, changed) },
            desktopLyrics = { openSettings("desktop-lyrics") },
            syncState = { syncService?.state?.value ?: BackgroundSyncSettings(this).let { BackgroundSyncState(active = it.enabled, message = it.message) } },
            connectSync = ::connectSync,
            disconnectSync = { SyncService.disconnect(this) },
            chooseSync = { id, mode -> syncService?.choose(id, mode) ?: false },
            exportLogs = ::exportDiagnostics,
            sourceUpdate = { service?.sourceUpdates?.value },
            dismissSourceUpdate = { service?.dismissSourceUpdate() },
            playbackContent = ::playbackSettingsContent
        )
    private fun exportDiagnostics() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"; putExtra(Intent.EXTRA_TITLE, "lx-native-diagnostics.txt")
        }, REQUEST_LOG_EXPORT)
    }
    internal fun refreshSettingsPreferences() {
        service?.refreshPreferences(); filterSongs(); service?.state?.value?.let(::renderPlayer)
    }
    internal fun closeSettings() {
        if (!supportFragmentManager.isStateSaved) navigation?.popBackStack()
    }
    private fun installHomeContent(root: View) {
        setContentView(root)
        updateDestinationChrome()
    }
    private fun updateDestinationChrome() {
        if (!::miniBar.isInitialized) return
        val settings = navigation?.currentDestination?.id == R.id.settings_page
        updateMiniPlayerVisibility(service?.state?.value?.song)
        pageSidebar?.visibility = if (settings) View.GONE else View.VISIBLE
        updateNavigationSelection()
        findViewById<ViewGroup>(android.R.id.content).getChildAt(0)?.requestApplyInsets()
        if (!settings && ::list.isInitialized) {
            val target = list
            target.post {
                val restore = pendingScroll ?: return@post
                if (list === target && target.isShown && restore.first < target.count) target.setSelectionFromTop(restore.first, restore.second)
            }
        }
    }
    internal fun refreshSettingsAppearance(recreate: Boolean) {
        settingsRecreateRequested = settingsRecreateRequested || recreate
        if (settingsAppearancePosted) return
        settingsAppearancePosted = true
        window.decorView.post {
            settingsAppearancePosted = false
            val recreateActivity = settingsRecreateRequested; settingsRecreateRequested = false
            if (isDestroyed || isFinishing || isChangingConfigurations) return@post
            if (recreateActivity) recreate() else applyThemeInPlace()
        }
    }
    @Suppress("DEPRECATION")
    private fun applyThemeInPlace() {
        val page = pageSnapshot()
        val restorePlaylistPicker = playlistDrawer?.isOpen == true
        val previousQuery = searchedText
        val previousStatus = status.text
        val moreVisibility = loadMore.visibility
        val theme = NativeThemes(this).active()
        setTheme(if (theme.dark) R.style.NativeDark else R.style.NativeLight)
        palette = UiPalette(this, theme); lx = LxStyles(this, palette, iconFont)
        retainedPageState.page = page
        buildUi(); restorePage(null, restartLoads = false)
        if (restorePlaylistPicker) window.decorView.post { showPlaylists() }
        searchedText = previousQuery
        status.text = previousStatus; loadMore.visibility = moreVisibility; updateSearchChrome()
        service?.state?.value?.let(::renderPlayer); updateArtwork()
        settingsFragment()?.applyTheme()
        window.statusBarColor = palette.surface; window.navigationBarColor = palette.surface
        if (Build.VERSION.SDK_INT >= 30) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (theme.dark) 0 else light, light)
        } else {
            val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = if (theme.dark) window.decorView.systemUiVisibility and light.inv() else window.decorView.systemUiVisibility or light
        }
    }
    private fun showPlaybackQueue() {
        val player = service ?: return
        val snapshot = player.queueState.value
        if (snapshot == null || snapshot.songs.isEmpty()) { toast(getString(R.string.ui_09679f956e0e)); return }
        val list = ListView(this)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, snapshot.songs.mapIndexed { index, song ->
            "${if (index == snapshot.index) "▶ " else ""}${index + 1}. ${song.title} · ${song.artist}"
        })
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.ui_827be73ec6db, java.lang.String.valueOf(snapshot.songs.size))).setView(list)
            .setNegativeButton(getString(R.string.ui_3fd47edce45b), null).setNeutralButton(getString(R.string.ui_d18e02739127)) { _, _ -> player.clearQueue() }.create()
        fun current(): Boolean {
            if (player.queueState.value?.songs == snapshot.songs) return true
            dialog.dismiss(); showPlaybackQueue(); return false
        }
        list.setOnItemClickListener { _, _, position, _ -> if (current()) { player.playQueued(position); dialog.dismiss() } }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val song = snapshot.songs[position]
            AlertDialog.Builder(this).setTitle(song.title).setItems(arrayOf(getString(R.string.ui_c3396195e91c), getString(R.string.ui_f853a70b1204), getString(R.string.ui_e75e8b4e5c97), getString(R.string.ui_6135d4159e89))) { _, which ->
                if (!current()) return@setItems
                when (which) {
                    0 -> player.playQueued(position)
                    1 -> player.moveQueued(position, (position - 1).coerceAtLeast(0))
                    2 -> player.moveQueued(position, (position + 1).coerceAtMost(snapshot.songs.lastIndex))
                    3 -> player.removeQueued(position)
                }
                dialog.dismiss(); if (which != 0) showPlaybackQueue()
            }.show(); true
        }
        dialog.show(); list.setSelection(snapshot.index)
    }
    private fun showSourceActions(anchor: View, entry: UserSourceEntry, changed: () -> Unit = {}) {
        val actions = arrayOf(getString(R.string.ui_f4f0ead1116b), getString(R.string.ui_0fd5cd79a1b5), getString(R.string.ui_ba9a8defba4e, java.lang.String.valueOf(if (entry.allowUpdateAlert) getString(R.string.ui_3fd47edce45b) else getString(R.string.ui_8da97ddda990))), getString(R.string.ui_2f9daa828907))
        choicesPopup.show(anchor, actions.indices.map(Int::toString), actions.toList(), "", palette) { id ->
            val index = id.toInt()
            when (index) {
                0 -> scope.launch {
                    try { service?.selectSource(entry.id); changed(); toast(getString(R.string.ui_032d025aff70)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { toast(getString(R.string.ui_ced3fddd61e0, java.lang.String.valueOf(e.localizedMessage))) }
                }
                1 -> {
                    pendingSourceExportId = entry.id
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "application/javascript"
                        putExtra(Intent.EXTRA_TITLE, entry.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80) + ".js")
                    }, REQUEST_SOURCE_EXPORT)
                }
                2 -> scope.launch {
                    try { service?.allowSourceUpdates(entry.id, !entry.allowUpdateAlert); changed(); toast(getString(R.string.ui_5c73d0fff5e2)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { toast(getString(R.string.ui_749d84b5fa76, java.lang.String.valueOf(e.localizedMessage))) }
                }
                3 -> AlertDialog.Builder(this).setMessage(getString(R.string.ui_852174674832, java.lang.String.valueOf(entry.name))).setNegativeButton(getString(R.string.ui_2cd0f3be8738), null)
                    .setPositiveButton(getString(R.string.ui_2f9daa828907)) { _, _ -> scope.launch {
                        try { service?.removeSource(entry.id); changed(); toast(getString(R.string.ui_a1bfd178ab34)) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { toast(getString(R.string.ui_57deb333c04b, java.lang.String.valueOf(e.localizedMessage))) }
                    } }.show()
            }
        }
    }
    private fun updateListAction() {
        updateNavigationSelection()
        if (::searchRow.isInitialized && ::emptyHint.isInitialized && ::status.isInitialized) updateSearchChrome()
        val menu = boardMode || onlinePlaylistMode
        listActionButton.text = (if (menu) 59667 else 59660).toChar().toString()
        listActionButton.contentDescription = if (menu) getString(R.string.ui_7b1b6d54c192) else getString(R.string.ui_073a0d57165c)
    }
    private fun showOnlineListActions() {
        val choose = if (boardMode) getString(R.string.ui_5dd238551428) else getString(R.string.ui_0650128981bd)
        AlertDialog.Builder(this).setTitle(getString(R.string.ui_68f0bc744846)).setItems(arrayOf(choose, getString(R.string.ui_7f7653be272b))) { _, which ->
            if (which == 0) { if (boardMode) showBoards() else showOnlinePlaylists() }
            else if (allSongs.isNotEmpty()) saveCurrentOnlineList() else toast(getString(R.string.ui_24f59c4dbea0))
        }.show()
    }
    private fun showOnlinePlaylists() = showPlaylistBrowser()
    private fun showPlaylistSearch() { showMusicPage(); playlistSearchMode = true; openSearchPage(false) }
    private fun saveCurrentOnlineList() {
        val sourceName = if (boardMode) selectedBoard?.name else selectedOnlinePlaylist?.name
        val input = EditText(this).apply { setSingleLine(true); setText(sourceName ?: getString(R.string.ui_006d2287b3ea)) }
        val songs = allSongs.toList()
        AlertDialog.Builder(this).setTitle(getString(R.string.ui_7f7653be272b)).setView(input)
            .setMessage(getString(R.string.ui_ce626c5abcb8, java.lang.String.valueOf(songs.size)))
            .setNegativeButton(getString(R.string.ui_2cd0f3be8738), null).setPositiveButton(getString(R.string.ui_a3030bf8f16d)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) { toast(getString(R.string.ui_ff45f8f5d491)); return@setPositiveButton }
                scope.launch {
                    try {
                        val original = selectedOnlinePlaylist.takeIf { onlinePlaylistMode }
                        if (original != null) playlists.importOnline(original.copy(name = name), songs)
                        else playlists.importLists(listOf(ImportedList(name, songs)))
                        toast(getString(R.string.ui_1d8c2c9c0aba, java.lang.String.valueOf(songs.size)))
                    }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { toast(getString(R.string.ui_749d84b5fa76, java.lang.String.valueOf(e.localizedMessage))) }
                }
            }.show()
    }
    private fun loadOnlinePlaylist(playlist: OnlinePlaylist, append: Boolean = false) {
        val nav = navigation
        if (!suppressPageNavigation && !append && nav != null && nav.currentDestination?.id != R.id.playlist_songs && !supportFragmentManager.isStateSaved) {
            if (retainedPageState.beforePlaylist == null && beforePlaylistSaved == null) retainedPageState.beforePlaylist = pageSnapshot()
            hidePageKeyboard(); nav.navigate(R.id.playlist_songs)
        }
        val more = append && onlinePlaylistMode && selectedOnlinePlaylist == playlist && onlinePlaylistPage > 0
        val page = if (more) onlinePlaylistPage + 1 else 1
        onlineMode = true; boardMode = false; onlinePlaylistMode = true; updateListAction(); pageTitle.text = getString(R.string.ui_473e57b72072); activePlaylist = null
        selectedOnlinePlaylist = playlist; heading.text = "›   ${playlist.name}"; setSearchVisible(false)
        if (!more) { allSongs = emptyList(); visibleSongs = emptyList(); list.adapter = null; onlinePlaylistPage = 0 }
        loadJob?.cancel(); loadMore.visibility = View.GONE; status.text = getString(R.string.ui_aed6f82d382c)
        loadJob = scope.launch {
            try {
                val items = onlinePlaylistSongs(playlist, page)
                if (onlinePlaylistMode && selectedOnlinePlaylist == playlist) {
                    allSongs = if (more) (allSongs + items).distinctBy { it.source to it.id } else items
                    onlinePlaylistPage = page; filterSongs(); loadMore.visibility = if (items.size >= if (playlist.source == "mg") 30 else 100) View.VISIBLE else View.GONE
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (onlinePlaylistMode) { status.text = getString(R.string.ui_31206f5478ba, java.lang.String.valueOf(e.localizedMessage)); if (more) loadMore.visibility = View.VISIBLE } }
        }
    }
    private fun showBoards() {
        val nav = navigation ?: return
        if (supportFragmentManager.isStateSaved || nav.currentDestination?.id == R.id.charts_page) return
        hidePageKeyboard()
        if (!nav.popBackStack(R.id.charts_page, false)) nav.navigate(R.id.charts_page)
    }
    private fun loadBoard(board: Board, append: Boolean = false) {
        showMusicPage()
        val more = append && boardMode && selectedBoard == board && boardPage > 0
        val page = if (more) boardPage + 1 else 1
        onlineMode = true; boardMode = true; onlinePlaylistMode = false; updateListAction(); pageTitle.text = getString(R.string.ui_1ee95aa94712); activePlaylist = null
        selectedBoard = board; heading.text = "›   ${board.name}"; setSearchVisible(false)
        if (!more) { allSongs = emptyList(); visibleSongs = emptyList(); list.adapter = null; boardPage = 0 }
        loadJob?.cancel(); loadMore.visibility = View.GONE; status.text = getString(R.string.ui_4760ac1e18cc)
        loadJob = scope.launch {
            try {
                val items = OnlineBoards().songs(board, page)
                if (boardMode && selectedBoard == board) {
                    allSongs = if (more) (allSongs + items).distinctBy { it.source to it.id } else items
                    boardPage = page; filterSongs(); loadMore.visibility = if (board.source !in setOf("tx", "mg") && items.size >= 100) View.VISIBLE else View.GONE
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (boardMode) { status.text = getString(R.string.ui_22a7c1cdbc29, java.lang.String.valueOf(e.localizedMessage)); if (more) loadMore.visibility = View.VISIBLE } }
        }
    }
    private fun sourceLabel(): String = sourceLabel(searchSource)
    private fun sourceLabel(source: String): String = if (source == SearchCatalog.ALL) getString(R.string.search_all_sources) else UiPreferences.sourceName(this, source)
    private fun showSearchSources() {
        val ids = PlatformPopup.sources + SearchCatalog.ALL
        val anchor = if (searchSourceButton.isShown) searchSourceButton else heading
        platformPopup.show(anchor, ids, ids.map(::sourceLabel), searchSource, palette) { selected ->
            searchSource = selected
            heading.text = getString(R.string.ui_e07216a6d505, sourceLabel())
            resetCatalogResults()
            if (search.text.isNotBlank()) searchOnline()
        }
    }
    private fun showPlayerDetails() {
        val state = service?.state?.value ?: return
        if (playerDialog?.isShowing == true) return
        val primary = palette.primary
        val dialog = Dialog(this)
        playerDialog = dialog
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(palette.surface); fitsSystemWindows = true
            tag = if (uiLayout.splitPlayer) "player-expanded" else "player-compact"
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "‹"; textSize = 32f; gravity = Gravity.CENTER; setTextColor(palette.foreground); contentDescription = getString(R.string.ui_572cf45ba436)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        detailTitle = TextView(this).apply { textSize = 17f; setTextColor(palette.foreground); maxLines = 1; gravity = Gravity.CENTER_VERTICAL }
        header.addView(detailTitle, LinearLayout.LayoutParams(0, dp(48), 1f))
        detailPageToggle = TextView(this).apply {
            text = getString(R.string.ui_9dbfa9b42bb3); textSize = 14f; gravity = Gravity.CENTER; setTextColor(primary)
            visibility = if (uiLayout.splitPlayer) View.GONE else View.VISIBLE; background = lx.touch()
            setOnClickListener { toggleDetailPage() }
        }
        header.addView(detailPageToggle, LinearLayout.LayoutParams(dp(56), dp(48)))
        header.addView(lx.icon(59655, getString(R.string.ui_8c38525734c0)) { showPlaybackSettings() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        content.addView(header)
        val splitBody = if (uiLayout.splitPlayer) LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL } else null
        val playerArea = if (splitBody != null) LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(16))
        } else content
        if (splitBody != null) {
            content.addView(splitBody, LinearLayout.LayoutParams(-1, 0, 1f))
            splitBody.addView(playerArea, LinearLayout.LayoutParams(0, -1, .45f))
        }
        val scroll = PlayerLyricScroll(this, lx.selected, {
            service?.state?.value?.let(::renderDetailTime)
        }, { index ->
            val player = service
            val line = lyricLines.getOrNull(index)
            if (player != null && line != null && !player.state.value.preparing) {
                player.seekTo(LyricTiming.seekPosition(line.timeMs, lyricDelayMs, player.state.value.duration))
                detailScroll?.follow(index)
            } else detailScroll?.follow(detailActiveLine.coerceAtLeast(0))
        }).apply {
            lines.setPadding(dp(24), 0, dp(24), 0)
            if (splitBody == null) {
                contentDescription = getString(R.string.ui_ccbb5ee62b1a)
                setOnClickListener { toggleDetailPage() }
            }
        }
        detailScroll = scroll
        val page = FrameLayout(this)
        detailCover = AlbumCoverLayout(this).apply { setOnClickListener { if (!uiLayout.splitPlayer) toggleDetailPage() } }
        val coverSize = dp(240)
        val album = FrameLayout(this).apply {
            background = lx.shape(palette.placeholder, 10); clipToOutline = true
        }
        album.addView(TextView(this).apply { text = "LX"; textSize = 58f; gravity = Gravity.CENTER; setTextColor(primary) }, FrameLayout.LayoutParams(-1, -1))
        detailCoverImage = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        album.addView(detailCoverImage, FrameLayout.LayoutParams(-1, -1))
        detailCover?.addView(album, FrameLayout.LayoutParams(coverSize, coverSize, Gravity.CENTER))
        page.addView(detailCover, FrameLayout.LayoutParams(-1, -1))
        if (splitBody == null) page.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        else {
            splitBody.addView(View(this).apply { setBackgroundColor(palette.divider) }, LinearLayout.LayoutParams(dp(1), -1).apply { setMargins(0, dp(24), 0, dp(24)) })
            splitBody.addView(scroll, LinearLayout.LayoutParams(0, -1, .55f))
        }
        detailShowingLyrics = uiLayout.splitPlayer; scroll.visibility = if (uiLayout.splitPlayer) View.VISIBLE else View.GONE
        playerArea.addView(page, LinearLayout.LayoutParams(-1, 0, 1f))
        detailTime = TextView(this).apply { textSize = 12f; gravity = Gravity.CENTER; setTextColor(palette.muted) }
        playerArea.addView(detailTime)
        detailSeek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(primary); thumbTintList = ColorStateList.valueOf(primary)
            contentDescription = getString(R.string.ui_fc16e2a0fca6)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
                override fun onStartTrackingTouch(bar: SeekBar?) { detailSeeking = true }
                override fun onStopTrackingTouch(bar: SeekBar?) { detailSeeking = false; service?.seekTo(bar?.progress ?: 0) }
            })
        }
        playerArea.addView(detailSeek, LinearLayout.LayoutParams(-1, dp(if (uiLayout.compactPlayer) 32 else 40)).apply { setMargins(dp(12), 0, dp(12), 0) })
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        fun button(code: Int, label: String, click: () -> Unit) = TextView(this).apply {
            text = code.toChar().toString(); typeface = iconFont; textSize = 28f; gravity = Gravity.CENTER
            setTextColor(primary); contentDescription = label; background = lx.touch(); isFocusable = true; setOnClickListener { click() }
        }
        controls.addView(button(59668, getString(R.string.ui_8c747908bf69)) { service?.previous() }, LinearLayout.LayoutParams(dp(72), dp(64)))
        detailToggle = button(59653, getString(R.string.ui_c3396195e91c)) { togglePlayback() }
        detailToggle?.background = lx.touch(lx.selected, 32)
        controls.addView(detailToggle, LinearLayout.LayoutParams(dp(72), dp(64)))
        controls.addView(button(59654, getString(R.string.ui_486e42475adb)) { service?.next() }, LinearLayout.LayoutParams(dp(72), dp(64)))
        playerArea.addView(controls)
        val options = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        detailMode = lx.label("", 13f, palette.muted).apply {
            gravity = Gravity.CENTER; background = lx.touch()
            setOnClickListener { service?.cycleMode() }
        }
        options.addView(detailMode, LinearLayout.LayoutParams(0, dp(48), 1f))
        options.addView(lx.label(getString(R.string.ui_7afda7dc6745), 13f, palette.muted).apply {
            gravity = Gravity.CENTER; background = lx.touch()
            setOnClickListener { showSleepTimer() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        options.addView(lx.label(getString(R.string.ui_f01b6815d826), 13f, palette.muted).apply {
            gravity = Gravity.CENTER; background = lx.touch()
            setOnClickListener { showPlaybackQueue() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        playerArea.addView(options)
        palette.background(content); palette.textStyle(content)
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(palette.surface))
        dialog.setOnDismissListener {
            playerDialog = null; detailTitle = null; detailTime = null; detailSeek = null; detailToggle = null; detailMode = null
            detailScroll = null; detailCover = null; detailCoverImage = null; detailPageToggle = null
            detailRenderedLines = null; detailActiveLine = -1; detailSeeking = false
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.let { ScreenInsets.install(it, content) }
        updateArtwork()
        renderDetails(state)
    }
    private fun toggleDetailPage() {
        if (uiLayout.splitPlayer) return
        detailShowingLyrics = !detailShowingLyrics
        detailScroll?.cancelPreview()
        detailCover?.visibility = if (detailShowingLyrics) View.GONE else View.VISIBLE
        detailScroll?.visibility = if (detailShowingLyrics) View.VISIBLE else View.GONE
        detailPageToggle?.text = if (detailShowingLyrics) getString(R.string.ui_ccbb5ee62b1a) else getString(R.string.ui_9dbfa9b42bb3)
        detailActiveLine = -1
        service?.state?.value?.let { renderDetails(it) }
    }
    private fun updateArtwork() {
        miniCoverImage.setImageBitmap(artworkBitmap)
        miniCoverImage.visibility = if (artworkBitmap == null) View.GONE else View.VISIBLE
        detailCoverImage?.setImageBitmap(artworkBitmap)
        detailCoverImage?.visibility = if (artworkBitmap == null) View.GONE else View.VISIBLE
    }
    private fun showSleepTimer() {
        val minutes = intArrayOf(0, 15, 30, 60, 90, 120)
        val finish = CheckBox(this).apply { text = getString(R.string.ui_13f910fe7cce) }
        AlertDialog.Builder(this).setTitle(getString(R.string.ui_7afda7dc6745))
            .setView(finish)
            .setItems(arrayOf(getString(R.string.ui_0446dbd36ecc), getString(R.string.ui_015feae6f8e4), getString(R.string.ui_a01a28d147ac), getString(R.string.ui_bf6201684e5b), getString(R.string.ui_928ff220b57b), getString(R.string.ui_0b41f8fb1d48), getString(R.string.ui_c6414cdd4464))) { _, which ->
                if (which == 6) { service?.setSleepTimer(0, true); toast(getString(R.string.ui_c6414cdd4464)) }
                else {
                    service?.setSleepTimer(minutes[which], which > 0 && finish.isChecked)
                    toast(if (which == 0) getString(R.string.ui_db424926e343) else getString(R.string.ui_dd38be1cc98a, java.lang.String.valueOf(minutes[which])))
                }
            }.show()
    }
    private fun renderDetailTime(state: PlayerState) {
        val preview = detailScroll?.previewIndex?.let(lyricLines::getOrNull)
        val position = preview?.let { LyricTiming.seekPosition(it.timeMs, lyricDelayMs, state.duration) } ?: state.position
        detailTime?.text = "${formatTime(position)} / ${formatTime(state.duration)}"
    }

    private fun renderDetails(state: PlayerState) {
        if (playerDialog?.isShowing != true) return
        detailTitle?.text = state.song?.let { "${it.title}  ·  ${it.artist}" } ?: getString(R.string.ui_f518674b61f6)
        renderDetailTime(state)
        detailSeek?.apply { max = state.duration.coerceAtLeast(0); if (!detailSeeking) progress = state.position }
        detailToggle?.apply {
            text = (if (state.playing) 59661 else 59653).toChar().toString()
            contentDescription = if (state.playing) getString(R.string.ui_8d12fc0d4eb2) else getString(R.string.ui_c3396195e91c)
            isEnabled = state.song != null && !state.preparing
        }
        detailMode?.text = state.mode.localized(this)
        val scroll = detailScroll ?: return
        val container = scroll.lines
        val newLyrics = detailRenderedLines !== lyricLines
        if (newLyrics) {
            scroll.cancelPreview()
            scroll.scrollTo(0, 0)
            detailRenderedLines = lyricLines; detailActiveLine = -1; container.removeAllViews()
            if (lyricLines.isEmpty()) {
                container.addView(TextView(this).apply { text = getString(R.string.ui_0245e1b77ac5); textSize = 16f; gravity = Gravity.CENTER; setTextColor(palette.faint) })
            } else lyricLines.forEach { line ->
                container.addView(TextView(this).apply {
                    text = LyricRendering.text(line, playerSettings).ifBlank { "♪" }; textSize = lyricAppearance.fontSizeSp.toFloat()
                    gravity = when (lyricAppearance.alignment) { LyricAlignment.LEFT -> Gravity.LEFT; LyricAlignment.CENTER -> Gravity.CENTER_HORIZONTAL; LyricAlignment.RIGHT -> Gravity.RIGHT }
                    setTextColor(palette.muted)
                    setPadding(0, dp(13), 0, dp(13))
                }, LinearLayout.LayoutParams(-1, -2))
            }
        }
        val active = LrcParser.indexAt(lyricLines, LyricTiming.lyricPosition(state.position.toLong(), lyricDelayMs))
        if (active != detailActiveLine || newLyrics) {
            (container.getChildAt(detailActiveLine) as? TextView)?.let { view ->
                view.setTextColor(palette.muted)
                lyricLines.getOrNull(detailActiveLine)?.let { view.text = LyricRendering.text(it, playerSettings) }
            }
            (container.getChildAt(active) as? TextView)?.setTextColor(palette.primary)
            scroll.follow(active.coerceAtLeast(0))
            detailActiveLine = active
        }
        lyricLines.getOrNull(active)?.let { line ->
            (container.getChildAt(active) as? TextView)?.text = LyricRendering.styled(line, playerSettings, true,
                LyricTiming.lyricPosition(state.position.toLong(), lyricDelayMs), palette.muted, palette.primary)
        }
    }
    private fun hasAudioPermission(): Boolean = checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED
    private fun audioPermission(): String = PermissionPolicy.audio(Build.VERSION.SDK_INT)
    private fun showLocalPermissionPrompt() {
        loadJob?.cancel(); allSongs = emptyList(); visibleSongs = emptyList(); list.adapter = null
        status.text = getString(R.string.ui_799c8f89223c)
        status.setTextColor(palette.primary)
        status.contentDescription = getString(R.string.ui_ccdab23ef0b3)
    }
    private fun requestAudioAccess(action: AudioAction) {
        if (hasAudioPermission()) { performAudioAction(action); return }
        pendingAudioAction = action
        val permission = audioPermission()
        val preferences = getSharedPreferences("permission-history", MODE_PRIVATE)
        if (preferences.getBoolean("audio-requested", false) && !shouldShowRequestPermissionRationale(permission)) {
            AlertDialog.Builder(this).setTitle(getString(R.string.ui_172fc36a4a5c))
                .setMessage(getString(R.string.ui_9d0bbc51b8d2))
                .setNegativeButton(getString(R.string.ui_2cd0f3be8738)) { _, _ -> pendingAudioAction = null }
                .setOnCancelListener { pendingAudioAction = null }
                .setPositiveButton(getString(R.string.ui_37aa6ad6a36d)) { _, _ ->
                    waitingForAudioSettings = true
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                }.show()
            return
        }
        preferences.edit().putBoolean("audio-requested", true).apply()
        requestPermissions(arrayOf(permission), REQUEST_AUDIO)
    }
    private fun onAudioAccessDenied(action: AudioAction) {
        when (action) {
            AudioAction.OpenLibrary -> if (!onlineMode && activePlaylist == null) {
                showLocalPermissionPrompt(); status.text = getString(R.string.ui_75f6a07d7c9d)
            }
            is AudioAction.ImportBackup -> previewBackup(action.uri)
            is AudioAction.ExportBackup -> toast(getString(R.string.ui_7b9143ea8eb8))
            else -> toast(getString(R.string.ui_bb0ff4f9b826))
        }
    }
    private fun performAudioAction(action: AudioAction) {
        when (action) {
            AudioAction.OpenLibrary -> if (!onlineMode && activePlaylist == null) loadSongs()
            AudioAction.Resume -> service?.resume() ?: run { waitingForServicePlayback = action }
            is AudioAction.Play -> {
                if (action.position !in action.songs.indices) return
                service?.play(action.songs, action.position) ?: run { waitingForServicePlayback = action }
            }
            is AudioAction.RecoverPlay -> scope.launch {
                try {
                    val songs = if (action.playlistId == null) LocalMusic(applicationContext).load() else playlists.songs(action.playlistId)
                    val index = songs.indexOfFirst { it.id == action.id && it.source == action.source }
                    if (index < 0) toast(getString(R.string.ui_a65a40087086)) else performAudioAction(AudioAction.Play(songs, index))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { toast(getString(R.string.ui_7aea1779d123, java.lang.String.valueOf(e.localizedMessage))) }
            }
            is AudioAction.ImportBackup -> previewBackup(action.uri)
            is AudioAction.ExportBackup -> exportBackup(action.uri)
        }
    }
    private fun audioActionToBundle(action: AudioAction): Bundle = Bundle().apply {
        when (action) {
            AudioAction.OpenLibrary -> putString("type", "library")
            AudioAction.Resume -> putString("type", "resume")
            is AudioAction.Play -> {
                val song = action.songs.getOrNull(action.position) ?: return@apply
                putString("type", "play"); putLong("id", song.id); putString("source", song.source)
                activePlaylist?.let { putLong("playlist", it.id) }
            }
            is AudioAction.RecoverPlay -> {
                putString("type", "play"); putLong("id", action.id); putString("source", action.source)
                action.playlistId?.let { putLong("playlist", it) }
            }
            is AudioAction.ImportBackup -> { putString("type", "import"); putString("uri", action.uri.toString()) }
            is AudioAction.ExportBackup -> { putString("type", "export"); putString("uri", action.uri.toString()) }
        }
    }
    private fun audioActionFromBundle(bundle: Bundle): AudioAction? = when (bundle.getString("type")) {
        "library" -> AudioAction.OpenLibrary
        "resume" -> AudioAction.Resume
        "play" -> AudioAction.RecoverPlay(bundle.getLong("id"), bundle.getString("source") ?: "local", if (bundle.containsKey("playlist")) bundle.getLong("playlist") else null)
        "import" -> bundle.getString("uri")?.let { AudioAction.ImportBackup(Uri.parse(it)) }
        "export" -> bundle.getString("uri")?.let { AudioAction.ExportBackup(Uri.parse(it)) }
        else -> null
    }
    private fun songToBundle(song: Song): Bundle = Bundle().apply {
        putLong("id", song.id); putString("uri", song.uri.toString()); putString("title", song.title)
        putString("artist", song.artist); putLong("duration", song.duration); putString("source", song.source); putString("sourceInfo", song.sourceInfo)
    }
    private fun songFromBundle(bundle: Bundle): Song = Song(bundle.getLong("id"), Uri.parse(bundle.getString("uri") ?: ""),
        bundle.getString("title") ?: "", bundle.getString("artist") ?: "", bundle.getLong("duration"),
        bundle.getString("source") ?: "local", bundle.getString("sourceInfo"))
    private fun togglePlayback() {
        val player = service ?: return
        if (player.state.value.playing) { player.pause(); return }
        if (PermissionPolicy.requiresLibraryAccess(this, player.state.value.song) && !hasAudioPermission()) requestAudioAccess(AudioAction.Resume)
        else player.resume()
    }
    @Deprecated("Deprecated in Android")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO) {
            val action = pendingAudioAction; pendingAudioAction = null
            if (action != null) {
                if (hasAudioPermission()) performAudioAction(action) else onAudioAccessDenied(action)
            }
        } else if (requestCode == REQUEST_DOWNLOAD) {
            val song = pendingDownload; pendingDownload = null
            if (song != null && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) downloadSong(song)
            else toast(getString(R.string.ui_cbe111f9db69))
        }
    }
    private fun loadSongs() {
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                allSongs = LocalMusic(applicationContext).load()
                filterSongs()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = getString(R.string.ui_7ee7cd3a2806, java.lang.String.valueOf(e.localizedMessage)) }
        }
    }
    private val Playlist.displayName: String
        get() = if (syncId == "love") getString(R.string.playlist_favorites) else name

    private fun loadPlaylist(playlist: Playlist) {
        playlistDrawer?.close()
        showMusicPage()
        loadJob?.cancel()
        val switching = onlineMode || activePlaylist?.id != playlist.id
        if (switching) {
            allSongs = emptyList()
            if (!suppressPageNavigation) pendingScroll = null
        }
        onlineMode = false; boardMode = false; onlinePlaylistMode = false; updateListAction(); pageTitle.text = getString(R.string.ui_27c95ec6b766)
        loadMore.visibility = View.GONE
        activePlaylist = playlist
        heading.text = "›   ${playlist.displayName}"
        search.text.clear()
        if (switching) filterSongs()
        loadJob = scope.launch {
            try { allSongs = playlists.songs(playlist.id); filterSongs() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = getString(R.string.ui_ef6e30183259, java.lang.String.valueOf(e.localizedMessage)) }
        }
    }
    private fun showPlaylists() {
        val drawer = playlistDrawer ?: return
        if (drawer.isOpen) { drawer.close(); return }
        showMusicPage()
        hidePageKeyboard()
        platformPopup.dismiss()
        drawer.open()
        playlistPickerJob?.cancel()
        playlistPickerJob = scope.launch {
            try {
                val items = playlists.list()
                if (playlistDrawer === drawer && drawer.isOpen) renderPlaylistPicker(items, reveal = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(getString(R.string.ui_ef6e30183259, java.lang.String.valueOf(e.localizedMessage))) }
        }
    }
    private fun renderPlaylistPicker(items: List<Playlist>, reveal: Boolean = false) {
        if (playlistPicker?.render(items.sortedBy { it.syncId != "love" }, activePlaylist?.id, !onlineMode, reveal) == true) {
            playlistActionsPopup.dismiss()
        }
    }
    private fun selectPlaylist(playlist: Playlist) {
        if (onlineMode || activePlaylist?.id != playlist.id) {
            setSearchVisible(false)
            loadPlaylist(playlist)
        } else playlistDrawer?.close()
        if (!preferences.clickPlaylistPlay) return
        scope.launch {
            try {
                val songs = playlists.songs(playlist.id)
                if (songs.isEmpty() || onlineMode || activePlaylist?.id != playlist.id) return@launch
                val play = AudioAction.Play(songs, 0)
                if (songs.any { PermissionPolicy.requiresLibraryAccess(this@MainActivity, it) } && !hasAudioPermission()) requestAudioAccess(play)
                else performAudioAction(play)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(getString(R.string.ui_ef6e30183259, java.lang.String.valueOf(e.localizedMessage))) }
        }
    }
    private fun createPlaylist() {
        val input = EditText(this).apply { hint = getString(R.string.ui_9d3981ee6aa3); setSingleLine(true) }
        AlertDialog.Builder(this).setTitle(getString(R.string.ui_13eeeadb23fb)).setView(input).setNegativeButton(getString(R.string.ui_2cd0f3be8738), null)
            .setPositiveButton(getString(R.string.ui_cde2cd071d25)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) { toast(getString(R.string.ui_ff45f8f5d491)); return@setPositiveButton }
                scope.launch {
                    try { val id = playlists.create(name); loadPlaylist(Playlist(id, name)) }
                    catch (e: Exception) { toast(getString(R.string.ui_a5164865c034, java.lang.String.valueOf(e.localizedMessage))) }
                }
            }.show()
    }
    private enum class PlaylistAction(val label: Int) {
        CREATE(R.string.ui_13eeeadb23fb), RENAME(R.string.ui_0d0cbac2eee5), SORT(R.string.ui_4abdef1f2424),
        ENQUEUE(R.string.ui_b6cd52c6fe92), REFRESH(R.string.ui_31448436755a), UP(R.string.ui_46a150506a48),
        DOWN(R.string.ui_77037dd314bf), CLEAR(R.string.ui_4e0ca928260e), DELETE(R.string.ui_0a1d79c26e3b)
    }
    private fun showPlaylistActions(anchor: View, playlist: Playlist) {
        val builtin = playlist.syncId in setOf("default", "love")
        val actions = buildList {
            add(PlaylistAction.CREATE); add(PlaylistAction.RENAME)
            add(PlaylistAction.SORT); add(PlaylistAction.ENQUEUE); add(PlaylistAction.REFRESH)
            if (!builtin) { add(PlaylistAction.UP); add(PlaylistAction.DOWN) }
            add(PlaylistAction.CLEAR)
            add(PlaylistAction.DELETE)
        }
        val disabled = buildSet {
            if (builtin) { add(PlaylistAction.RENAME.name); add(PlaylistAction.DELETE.name) }
            if (playlist.source !in ExternalLinks.sources || playlist.sourceListId.isNullOrBlank()) add(PlaylistAction.REFRESH.name)
        }
        playlistActionsPopup.show(anchor, actions.map { it.name }, actions.map { getString(it.label) }, "", palette,
            alignEnd = true, disabled = disabled) { selected ->
            val action = PlaylistAction.valueOf(selected)
            runPlaylistAction(action, playlist)
        }
    }
    private fun runPlaylistAction(action: PlaylistAction, playlist: Playlist) {
        fun runEdit(edit: suspend () -> Unit) { scope.launch {
            try { edit() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(getString(R.string.ui_e31d650c716b, java.lang.String.valueOf(e.localizedMessage))) }
        } }
        when (action) {
            PlaylistAction.CREATE -> createPlaylist()
            PlaylistAction.REFRESH -> refreshOnlinePlaylist(playlist)
            PlaylistAction.ENQUEUE -> runEdit { service?.enqueue(playlists.songs(playlist.id)); toast(getString(R.string.ui_51a4e2f9dea0)) }
            PlaylistAction.SORT -> AlertDialog.Builder(this).setTitle(getString(action.label)).setItems(arrayOf(getString(R.string.ui_7ec5153cfab4), getString(R.string.ui_cfb624b8a25b), getString(R.string.ui_04a95e0654d3), getString(R.string.ui_9e9d4588c7f0))) { _, order ->
                runEdit { playlists.sortSongs(playlist.id, arrayOf("title", "artist", "duration", "reverse")[order]) }
            }.show()
            PlaylistAction.RENAME -> {
                val input = EditText(this).apply { setSingleLine(true); setText(playlist.name) }
                AlertDialog.Builder(this).setTitle(getString(R.string.ui_b4262f363f77)).setView(input).setNegativeButton(getString(R.string.ui_2cd0f3be8738), null)
                    .setPositiveButton(getString(R.string.ui_a3030bf8f16d)) { _, _ -> runEdit { playlists.rename(playlist.id, input.text.toString()) } }.show()
            }
            PlaylistAction.UP, PlaylistAction.DOWN -> runEdit {
                val users = playlists.list().filterNot { it.syncId in setOf("default", "love") }
                val position = users.indexOfFirst { it.id == playlist.id } + if (action == PlaylistAction.UP) -1 else 1
                playlists.movePlaylist(playlist.id, position)
            }
            PlaylistAction.CLEAR, PlaylistAction.DELETE -> {
                val deleting = action == PlaylistAction.DELETE
                val verb = getString(if (deleting) R.string.ui_2f9daa828907 else R.string.ui_1ef3de06b32e)
                AlertDialog.Builder(this).setMessage(getString(R.string.ui_88ff633c9cdd, verb, playlist.displayName))
                    .setNegativeButton(getString(R.string.ui_2cd0f3be8738), null).setPositiveButton(getString(R.string.ui_fac2a67ad878)) { _, _ -> runEdit {
                        if (deleting) {
                            val wasSelected = activePlaylist?.id == playlist.id
                            playlists.delete(playlist.id)
                            if (wasSelected && !onlineMode && (activePlaylist?.id == playlist.id || activePlaylist == null)) openLocalPage(false)
                        } else playlists.clear(playlist.id)
                    } }.show()
            }
        }
    }
    private fun showBatchSongs() {
        val snapshot = visibleSongs.toList(); val sourceList = activePlaylist
        if (snapshot.isEmpty()) { toast(getString(R.string.ui_24f59c4dbea0)); return }
        val checked = BooleanArray(snapshot.size)
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.ui_92503cecb0e4))
            .setMultiChoiceItems(snapshot.map { "${it.title} · ${it.artist}" }.toTypedArray(), checked) { _, index, value -> checked[index] = value }
            .setNegativeButton(getString(R.string.ui_2cd0f3be8738), null).setNeutralButton(getString(R.string.ui_3a5040b68abf), null).setPositiveButton(getString(R.string.ui_acfc4e74a650)) { _, _ ->
                val songs = snapshot.filterIndexed { index, _ -> checked[index] }
                if (songs.isEmpty()) { toast(getString(R.string.ui_7c75b6ccc72a)); return@setPositiveButton }
                val actions = mutableListOf(getString(R.string.ui_2cd77e843e22), getString(R.string.ui_f655b3c8a2b6), getString(R.string.ui_cc143006b20d))
                if (sourceList != null) actions.addAll(listOf(getString(R.string.ui_2b96f6a6a976), getString(R.string.ui_c7d9494f6392)))
                AlertDialog.Builder(this).setTitle(getString(R.string.ui_e44c6ed9b6a6, java.lang.String.valueOf(songs.size))).setItems(actions.toTypedArray()) { _, action ->
                    when (actions[action]) {
                        getString(R.string.ui_2cd77e843e22) -> {
                            val play = AudioAction.Play(songs, 0)
                            if (songs.any { PermissionPolicy.requiresLibraryAccess(this@MainActivity, it) } && !hasAudioPermission()) requestAudioAccess(play) else performAudioAction(play)
                        }
                        getString(R.string.ui_f655b3c8a2b6) -> service?.enqueue(songs)
                        getString(R.string.ui_c7d9494f6392) -> AlertDialog.Builder(this).setMessage(getString(R.string.ui_74f577267660, java.lang.String.valueOf(songs.size)))
                            .setNegativeButton(getString(R.string.ui_2cd0f3be8738), null).setPositiveButton(getString(R.string.ui_6135d4159e89)) { _, _ -> scope.launch {
                                try { playlists.batchSongs(songs, sourceList?.id, null, true); toast(getString(R.string.ui_4f0536be4304)) }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { toast(getString(R.string.ui_1dd0b9466445, java.lang.String.valueOf(e.localizedMessage))) }
                            } }.show()
                        else -> scope.launch {
                            try {
                                val targets = playlists.list().filterNot { it.id == sourceList?.id }
                                AlertDialog.Builder(this@MainActivity).setTitle(actions[action]).setItems(targets.map { it.displayName }.toTypedArray()) { _, index -> scope.launch {
                                    try {
                                        playlists.batchSongs(songs, sourceList?.id, targets[index].id, actions[action] == getString(R.string.ui_2b96f6a6a976))
                                        toast(getString(R.string.ui_158871d8b201))
                                    } catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { toast(getString(R.string.ui_e31d650c716b, java.lang.String.valueOf(e.localizedMessage))) }
                                } }.show()
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { toast(getString(R.string.ui_ef6e30183259, java.lang.String.valueOf(e.localizedMessage))) }
                        }
                    }
                }.show()
            }.create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            AlertDialog.Builder(this).setItems(arrayOf(getString(R.string.ui_3a5040b68abf), getString(R.string.ui_f4d4bae588c4), getString(R.string.song_range))) { _, choice ->
                if (choice < 2) checked.indices.forEach { checked[it] = choice == 0; dialog.listView.setItemChecked(it, checked[it]) }
                else {
                    val input = EditText(this).apply { hint = getString(R.string.song_range_hint); setSingleLine(true) }
                    val range = AlertDialog.Builder(this).setTitle(getString(R.string.song_range)).setView(input).setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
                    range.setOnShowListener { range.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val match = Regex("\\s*(\\d+)\\s*[-–]\\s*(\\d+)\\s*").matchEntire(input.text.toString())
                        val start = match?.groupValues?.get(1)?.toIntOrNull(); val end = match?.groupValues?.get(2)?.toIntOrNull()
                        if (start == null || end == null || start !in 1..snapshot.size || end !in start..snapshot.size) input.error = getString(R.string.song_position_invalid)
                        else { for (index in start - 1 until end) { checked[index] = true; dialog.listView.setItemChecked(index, true) }; range.dismiss() }
                    } }; range.show()
                }
            }.show()
        }
    }
    private fun showSongActions(anchor: View, song: Song) =
        showSongActionsFor(anchor, song, activePlaylist) { playListedSong(song) }

    private fun showSongActionsFor(anchor: View, song: Song, selected: Playlist?, play: () -> Unit) {
        val actions = buildList {
            add(R.string.ui_c3396195e91c)
            add(R.string.ui_94ca0a7bf933)
            add(R.string.ui_cc143006b20d)
            if (selected != null) {
                add(R.string.ui_2b96f6a6a976)
                add(R.string.song_position)
            }
            add(R.string.ui_732807e9a807)
            add(R.string.ui_804151aa3e97)
            add(R.string.song_source_details)
            if (song.source == "local") add(R.string.metadata_edit) else {
                add(R.string.song_clear_cache)
                add(R.string.ui_9be60bb7ddeb)
                add(R.string.ui_07f73642e525)
            }
            add(R.string.ui_9b3d69ae09ce)
            add(R.string.ui_f655b3c8a2b6)
            add(R.string.song_copy_name)
            add(R.string.ui_792336bc0bbf)
            add(R.string.ui_9c2f512b6594)
            if (selected != null) {
                add(R.string.ui_de23dedafe75)
                add(R.string.ui_d092b4040c1f)
                add(R.string.ui_44c2c89152c7)
            }
        }
        songActionsPopup.show(anchor, actions.map(Int::toString), actions.map(::getString), "", palette, alignEnd = true) { choice ->
            runSongAction(choice.toInt(), song, selected, play)
        }
    }

    private fun runSongAction(action: Int, song: Song, selected: Playlist?, play: () -> Unit) {
        when (action) {
            R.string.ui_c3396195e91c -> play()
            R.string.metadata_edit -> startActivityForResult(MetadataActivity.intentFor(this, song), REQUEST_METADATA)
            R.string.song_copy_name -> { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText(song.title, "${song.title} - ${song.artist}")) }
            R.string.song_source_details -> {
                val text = TextView(this).apply { text = "${song.title}\n${song.artist}\n${song.source}\n${song.uri}\n" + (song.sourceInfo?.let { runCatching { org.json.JSONObject(it).toString(2) }.getOrDefault(it) } ?: ""); setTextIsSelectable(true); setPadding(24, 12, 24, 12) }
                AlertDialog.Builder(this).setTitle(getString(R.string.song_source_details)).setView(ScrollView(this).apply { addView(text) }).setPositiveButton(android.R.string.ok, null)
                    .apply { SongShare.providerPage(song)?.let { uri -> setNeutralButton(getString(R.string.song_open_provider)) { _, _ -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) } } } }.show()
            }
            R.string.song_clear_cache -> scope.launch {
                try { (service ?: error(getString(R.string.ui_7ca47a28e695))).clearSongCache(song); toast(getString(R.string.song_cache_cleared)) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { toast(e.localizedMessage.orEmpty()) }
            }
            R.string.song_position -> if (selected != null) {
                val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = getString(R.string.song_position_hint) }
                val position = AlertDialog.Builder(this).setTitle(getString(R.string.song_position)).setView(input).setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
                position.setOnShowListener { position.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().toIntOrNull()
                    if (value == null || value < 1) input.error = getString(R.string.song_position_invalid)
                    else scope.launch {
                        try { playlists.moveSongPosition(selected.id, song, value - 1); position.dismiss() }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { input.error = e.localizedMessage }
                    }
                } }; position.show()
            }
            R.string.ui_804151aa3e97 -> runCatching { SongShare.share(this, song) }.onFailure { toast(getString(R.string.ui_72b5f22035d2, java.lang.String.valueOf(it.localizedMessage))) }
            R.string.ui_732807e9a807 -> scope.launch {
                try {
                    toast(getString(R.string.ui_d2678cc07f14))
                    val songs = service?.otherSources(song).orEmpty()
                    if (songs.isEmpty()) toast(getString(R.string.ui_6c9ad7b94469)) else AlertDialog.Builder(this@MainActivity).setTitle(getString(R.string.ui_cb355d5c3da2))
                        .setItems(songs.map { "${it.source.uppercase()} · ${it.title} · ${it.artist}" }.toTypedArray()) { _, index ->
                            AlertDialog.Builder(this@MainActivity).setTitle(songs[index].title).setItems(arrayOf(getString(R.string.ui_c3396195e91c), getString(R.string.ui_cc143006b20d), getString(R.string.ui_94ca0a7bf933))) { _, action ->
                                when (action) { 0 -> performAudioAction(AudioAction.Play(listOf(songs[index]), 0)); 1 -> choosePlaylistForSong(songs[index]); else -> service?.enqueue(listOf(songs[index]), true) }
                            }.show()
                        }.show()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { toast(getString(R.string.ui_f6baa7a2ba08, java.lang.String.valueOf(e.localizedMessage))) }
            }
            R.string.ui_07f73642e525 -> showComments(this, scope, song)
            R.string.ui_9b3d69ae09ce -> showDislikeChoices(song)
            R.string.ui_94ca0a7bf933, R.string.ui_f655b3c8a2b6 -> {
                service?.enqueue(listOf(song), action == R.string.ui_94ca0a7bf933); toast(getString(R.string.ui_51a4e2f9dea0))
            }
            R.string.ui_2b96f6a6a976 -> if (selected != null) scope.launch {
                try {
                val targets = playlists.list().filterNot { it.id == selected.id }
                AlertDialog.Builder(this@MainActivity).setTitle(getString(R.string.ui_2b96f6a6a976)).setItems(targets.map { it.displayName }.toTypedArray()) { _, target -> scope.launch {
                    try { playlists.moveSong(selected.id, targets[target].id, song) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { toast(getString(R.string.ui_9706d4890951, java.lang.String.valueOf(e.localizedMessage))) }
                } }.show()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { toast(getString(R.string.ui_ef6e30183259, java.lang.String.valueOf(e.localizedMessage))) }
            }
            R.string.ui_de23dedafe75, R.string.ui_d092b4040c1f -> if (selected != null) scope.launch {
                try {
                    val songs = playlists.songs(selected.id)
                    val position = songs.indexOfFirst { it.id == song.id && it.source == song.source } + if (action == R.string.ui_de23dedafe75) -1 else 1
                    playlists.moveSongPosition(selected.id, song, position)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { toast(getString(R.string.ui_3d085075c12b, java.lang.String.valueOf(e.localizedMessage))) }
            }
            R.string.ui_9c2f512b6594 -> {
                if (service?.state?.value?.song?.let { it.id == song.id && it.source == song.source } == true) service?.reloadLyrics(true)
                else scope.launch { lyrics.clearOnlineCache(song) }
                toast(getString(R.string.ui_698d047aee02))
            }
            R.string.ui_792336bc0bbf -> {
                pendingLyricSongId = song.id
                pendingLyricSource = song.source
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                }, REQUEST_LYRIC)
            }
            R.string.ui_9be60bb7ddeb -> chooseDownloadQuality(song)
            R.string.ui_44c2c89152c7 -> if (selected != null) {
                scope.launch {
                    try { playlists.remove(selected.id, song); if (activePlaylist?.id == selected.id) loadPlaylist(selected) }
                    catch (e: Exception) { toast(getString(R.string.ui_1dd0b9466445, java.lang.String.valueOf(e.localizedMessage))) }
                }
            }
            R.string.ui_cc143006b20d -> choosePlaylistForSong(song)
        }
    }
    private fun chooseDownloadQuality(song: Song) {
        if (downloadQualityDialog?.isShowing == true) return
        downloadQualityDialog = showDownloadQuality(this, song, palette, lx) { quality -> requestDownload(DownloadRequest(song, quality)) }
            .apply { setOnDismissListener { if (downloadQualityDialog === this) downloadQualityDialog = null } }
    }
    internal fun retryDownload(task: DownloadTask) {
        val quality = task.quality ?: return
        requestDownload(DownloadRequest(task.song, quality, task.key))
    }
    private fun requestDownload(request: DownloadRequest) {
        val permission = PermissionPolicy.downloadWrite(Build.VERSION.SDK_INT)
        if (permission == null || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            downloadSong(request); return
        }
        pendingDownload = request
        val preferences = getSharedPreferences("permission-history", MODE_PRIVATE)
        if (preferences.getBoolean("download-requested", false) && !shouldShowRequestPermissionRationale(permission)) {
            AlertDialog.Builder(this).setTitle(getString(R.string.ui_ee8644062426))
                .setMessage(getString(R.string.ui_5657f8f32261))
                .setNegativeButton(getString(R.string.ui_2cd0f3be8738)) { _, _ -> pendingDownload = null }
                .setOnCancelListener { pendingDownload = null }
                .setPositiveButton(getString(R.string.ui_37aa6ad6a36d)) { _, _ ->
                    waitingForDownloadSettings = true
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                }.show()
            return
        }
        preferences.edit().putBoolean("download-requested", true).apply()
        requestPermissions(arrayOf(permission), REQUEST_DOWNLOAD)
    }
    private fun downloadRequestToBundle(request: DownloadRequest) = songToBundle(request.song).apply {
        putString("download-quality", request.quality); putString("download-retry", request.retryKey)
    }
    private fun downloadRequestFromBundle(bundle: Bundle) = DownloadRequest(songFromBundle(bundle),
        bundle.getString("download-quality") ?: "128k", bundle.getString("download-retry"))

    private fun downloadSong(request: DownloadRequest) {
        val player = service ?: run { waitingForServiceDownload = request; return }
        scope.launch {
            try {
                if (Downloads(applicationContext).start(request.song, request.quality, request.retryKey, player::resolveDownloadUrl)) {
                    toast(getString(R.string.ui_6b7ce36f8aa9))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(getString(R.string.ui_42fb22bc6277, java.lang.String.valueOf(e.localizedMessage))) }
        }
    }
    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_METADATA) {
            if (resultCode == RESULT_OK && data != null) {
                val song = Song(data.getLongExtra("id", -1), Uri.parse(data.getStringExtra("uri")), data.getStringExtra("title").orEmpty(), data.getStringExtra("artist").orEmpty(), data.getLongExtra("duration", 0), "local", data.getStringExtra("info"))
                service?.metadataChanged(song)
                allSongs = allSongs.map { if (it.id == song.id && it.source == "local") song else it }; filterSongs()
            }
            return
        }
        if (requestCode == REQUEST_LOG_EXPORT) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) scope.launch {
                try {
                    val text = DiagnosticLog(applicationContext).read()
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error(getString(R.string.ui_bc86fe4ef5a1))
                    }
                    toast(getString(R.string.ui_7f05d4a5267c))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { toast(getString(R.string.ui_94477acc433d, java.lang.String.valueOf(e.localizedMessage))) }
            }
            return
        }
        if (requestCode == REQUEST_SOURCE_EXPORT) {
            val id = pendingSourceExportId; pendingSourceExportId = null
            val uri = data?.data
            if (resultCode == RESULT_OK && id != null && uri != null) scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val entry = UserSourceLibrary(applicationContext).read().entries.firstOrNull { it.id == id } ?: error(getString(R.string.ui_d002a3c70b6f))
                        contentResolver.openOutputStream(uri, "wt")?.use { it.write(entry.script.toByteArray(Charsets.UTF_8)) } ?: error(getString(R.string.ui_bc86fe4ef5a1))
                    }
                    toast(getString(R.string.ui_f09d848b26d5))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { toast(getString(R.string.ui_94477acc433d, java.lang.String.valueOf(e.localizedMessage))) }
            }
            return
        }
        if (requestCode == REQUEST_EXPORT) {
            val uri = data?.data ?: return
            if (resultCode != RESULT_OK) return
            scope.launch {
                try {
                    val hasLocal = playlists.list().any { list -> playlists.songs(list.id).any { PermissionPolicy.requiresLibraryAccess(this@MainActivity, it) } }
                    if (hasLocal && !hasAudioPermission()) requestAudioAccess(AudioAction.ExportBackup(uri)) else exportBackup(uri)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { toast(getString(R.string.ui_ef6e30183259, java.lang.String.valueOf(e.localizedMessage))) }
            }
            return
        }
        if (requestCode == REQUEST_BACKUP) {
            val uri = data?.data ?: return
            if (resultCode != RESULT_OK) return
            scope.launch {
                try {
                    val bytes = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.use { it.readBounded(32 * 1024 * 1024) } ?: error(getString(R.string.ui_96d30301c75b))
                    }
                    val privatePaths = ExternalAudio.existing(applicationContext).keys
                    val hasLocal = withContext(Dispatchers.Default) { LegacyBackup.containsLocalSongs(bytes, privatePaths) }
                    if (hasLocal && !hasAudioPermission()) requestAudioAccess(AudioAction.ImportBackup(uri)) else previewBackup(uri, bytes)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { toast(getString(R.string.ui_e67137044b3b, java.lang.String.valueOf(e.localizedMessage))) }
            }
            return
        }
        if (requestCode == REQUEST_SOURCE) {
            val uri = data?.data ?: return
            if (resultCode != RESULT_OK) return
            scope.launch {
                try {
                    val script = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.use { it.readBounded(2 * 1024 * 1024).toString(Charsets.UTF_8) }
                            ?: error(getString(R.string.ui_2dcda639f980))
                    }
                    val player = service ?: error(getString(R.string.ui_7ca47a28e695))
                    player.importSource(script)
                    toast(getString(R.string.ui_18710cc58894))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { toast(getString(R.string.ui_0874f11320ab, java.lang.String.valueOf(e.localizedMessage))) }
            }
            return
        }
        if (requestCode != REQUEST_LYRIC) return
        val id = pendingLyricSongId
        val source = pendingLyricSource
        pendingLyricSongId = null
        val uri = data?.data
        if (resultCode != RESULT_OK || id == null || uri == null) return
        scope.launch {
            try {
                lyrics.import(id, source, uri)
                if (lyricKey == "$source:$id") service?.reloadLyrics()
                toast(getString(R.string.ui_2b06ad3fc97c))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(getString(R.string.ui_e57c5262074b, java.lang.String.valueOf(e.localizedMessage))) }
        }
    }
    private fun exportBackup(uri: Uri) {
        scope.launch {
            try {
                val saved = playlists.list().map { it to playlists.songs(it.id) }
                val local = ExternalAudio.existing(applicationContext) + if (hasAudioPermission()) LocalMusic(applicationContext).byLegacyPath() else emptyMap()
                val paths = local.entries.filter { it.key.startsWith("/") }.associate { it.value.id to it.key }
                val missing = saved.sumOf { pair -> pair.second.count { it.source == "local" && it.id !in paths } }
                val snapshot = playlists.syncSnapshot(paths, forBackup = true)
                val bytes = withContext(Dispatchers.Default) { LegacyBackup.encodeSnapshot(snapshot) }
                withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) } ?: error(getString(R.string.ui_ce34bb9dcbed)) }
                toast(if (missing == 0) getString(R.string.ui_d8d9cfef9027) else getString(R.string.ui_1aa0f000b1e1, java.lang.String.valueOf(missing)))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(getString(R.string.ui_735faa049036, java.lang.String.valueOf(e.localizedMessage))) }
        }
    }
    private fun previewBackup(uri: Uri, existingBytes: ByteArray? = null) {
        scope.launch {
            try {
                val bytes = existingBytes ?: withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBounded(32 * 1024 * 1024) } ?: error(getString(R.string.ui_96d30301c75b))
                }
                val paths = ExternalAudio.existing(applicationContext) + if (hasAudioPermission()) LocalMusic(applicationContext).byLegacyPath() else emptyMap()
                val backup = withContext(Dispatchers.Default) { LegacyBackup.parse(bytes, paths) }
                val count = backup.lists.sumOf { it.songs.size }
                val message = getString(R.string.ui_9587a26d9a2c, java.lang.String.valueOf(backup.lists.size), java.lang.String.valueOf(count)) +
                    if (backup.skipped > 0) getString(R.string.ui_fbefd6976ef5, java.lang.String.valueOf(backup.skipped)) else ""
                val builder = AlertDialog.Builder(this@MainActivity).setTitle(getString(R.string.ui_63802f7b0c9b))
                    .setMessage(message).setNegativeButton(getString(R.string.ui_2cd0f3be8738), null).setPositiveButton(getString(R.string.ui_d75d8ce65fed)) { _, _ ->
                        scope.launch {
                            try { val saved = playlists.importLists(backup.lists); toast(getString(R.string.ui_f301e960381c, java.lang.String.valueOf(saved))) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { toast(getString(R.string.ui_dd5c3f8f623f, java.lang.String.valueOf(e.localizedMessage))) }
                        }
                    }
                if (backup.skipped == 0) builder.setNeutralButton(getString(R.string.ui_d02f0ac55e15)) { _, _ -> scope.launch {
                    try { toast(getString(R.string.ui_8f3a82ea5eda, java.lang.String.valueOf(playlists.restoreBackup(backup.lists)))) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { toast(getString(R.string.ui_edc6992a1230, java.lang.String.valueOf(e.localizedMessage))) }
                } }
                builder.show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(getString(R.string.ui_e67137044b3b, java.lang.String.valueOf(e.localizedMessage))) }
        }
    }
    private fun choosePlaylistForSong(song: Song) {
        scope.launch {
            try {
                val items = playlists.list()
                if (items.isEmpty()) { toast(getString(R.string.ui_bf12c5906f59)); return@launch }
                AlertDialog.Builder(this@MainActivity).setTitle(getString(R.string.ui_cc143006b20d))
                    .setItems(items.map { it.displayName }.toTypedArray()) { _, which ->
                        scope.launch {
                            try { toast(if (playlists.add(items[which].id, song)) getString(R.string.ui_889839915c3f) else getString(R.string.ui_54a28a424697)) }
                            catch (e: Exception) { toast(getString(R.string.ui_3350538a51ed, java.lang.String.valueOf(e.localizedMessage))) }
                        }
                    }.setNegativeButton(getString(R.string.ui_2cd0f3be8738), null).show()
            } catch (e: Exception) { toast(getString(R.string.ui_ef6e30183259, java.lang.String.valueOf(e.localizedMessage))) }
        }
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    private fun updateSuggestions(query: String) {
        suggestionJob?.cancel()
        if (!onlineMode || boardMode || onlinePlaylistMode || query.isBlank()) return
        val source = searchSource
        suggestionJob = scope.launch {
            delay(350)
            val history = if (preferences.searchHistory) SearchHistory(this@MainActivity).list().filter { it.contains(query, true) } else emptyList()
            val remote = try { SearchSuggestions().suggestions(source, query) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { emptyList() }
            if (onlineMode && !boardMode && !onlinePlaylistMode && search.text.toString() == query && searchSource == source) {
                val box = search as AutoCompleteTextView
                box.setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, (history + remote).distinct().take(15)))
                if (box.hasFocus() && box.isShown && window.decorView.hasWindowFocus()) box.showDropDown()
            }
        }
    }
    private fun searchTerm(term: String) {
        if (!isCatalogPage) openSearchPage(false)
        setSearchVisible(true); search.setText(term); searchOnline()
    }
    private fun showSearchTools() {
        val history = SearchHistory(this)
        val terms = history.list()
        val labels = listOf(getString(R.string.ui_dcd3c46d127f, java.lang.String.valueOf(sourceLabel())), getString(R.string.ui_02c0ab8fbd15)) + terms
        val view = ListView(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, labels) }
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.ui_6d0b959193ab)).setView(view).setNegativeButton(getString(R.string.ui_3fd47edce45b), null).create()
        view.setOnItemClickListener { _, _, index, _ ->
            when (index) {
                0 -> {
                    dialog.dismiss()
                    scope.launch {
                        try {
                            toast(getString(R.string.ui_fa0ead846beb))
                            val hot = SearchSuggestions().hot(searchSource)
                            if (hot.isEmpty()) toast(getString(R.string.ui_50933f24c7aa)) else AlertDialog.Builder(this@MainActivity).setTitle(getString(R.string.ui_2a61478eeecf, java.lang.String.valueOf(sourceLabel())))
                                .setItems(hot.toTypedArray()) { _, position -> searchTerm(hot[position]) }.setNegativeButton(getString(R.string.ui_3fd47edce45b), null).show()
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { toast(getString(R.string.ui_bf00a3daba4a, java.lang.String.valueOf(e.localizedMessage))) }
                    }
                }
                1 -> { history.clear(); dialog.dismiss(); showSearchTools() }
                else -> { dialog.dismiss(); searchTerm(terms[index - 2]) }
            }
        }
        view.setOnItemLongClickListener { _, _, index, _ ->
            if (index < 2) false else { history.remove(terms[index - 2]); dialog.dismiss(); showSearchTools(); true }
        }
        dialog.show()
    }
    private fun showHistory() {
        scope.launch {
            try {
                val history = RecentSongs(this@MainActivity)
                val songs = history.list()
                AlertDialog.Builder(this@MainActivity).setTitle(getString(R.string.ui_4f03b04808e7, java.lang.String.valueOf(songs.size)))
                    .setItems(songs.map { "${it.title} · ${it.artist}" }.toTypedArray()) { _, index ->
                        val action = AudioAction.Play(songs, index)
                        if (PermissionPolicy.requiresLibraryAccess(this@MainActivity, songs[index]) && !hasAudioPermission()) requestAudioAccess(action) else performAudioAction(action)
                    }.setNegativeButton(getString(R.string.ui_3fd47edce45b), null).setNeutralButton(getString(R.string.ui_1ef3de06b32e)) { _, _ -> scope.launch { history.clear() } }.show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(getString(R.string.ui_82abac1a7ffd, java.lang.String.valueOf(e.localizedMessage))) }
        }
    }
    private fun searchOnline(append: Boolean = false) {
        if (!isCatalogPage) return
        val query = search.text.toString().trim()
        if (query.isEmpty()) { toast(getString(R.string.ui_c975aa812f44)); return }
        SearchHistory(this).add(query)
        suggestionJob?.cancel(); (search as? AutoCompleteTextView)?.dismissDropDown()
        val more = append && query == searchedText && searchPage > 0
        val source = searchSource
        val playlists = playlistSearchMode
        val cursor = if (more) searchNextPages else null
        loadJob?.cancel()
        val generation = ++searchGeneration
        if (!more) { allSongs = emptyList(); visibleSongs = emptyList(); playlistSearchResults = emptyList(); searchPage = 0; list.adapter = null }
        catalogLoading = true
        setCatalogStatus(getString(R.string.ui_c37059a3d9df))
        loadMore.visibility = View.GONE
        fun current() = generation == searchGeneration && isCatalogPage && source == searchSource && playlists == playlistSearchMode && search.text.toString().trim() == query
        loadJob = scope.launch {
            try {
                val result = if (playlists) searchCatalog.playlists(query, source, cursor) else searchCatalog.songs(query, source, cursor)
                if (!current()) return@launch
                catalogLoading = false
                searchedText = query; searchPage++; searchNextPages = result.nextPages
                if (playlists) {
                    val items = result.items.filterIsInstance<OnlinePlaylist>()
                    playlistSearchResults = (if (more) playlistSearchResults + items else items).distinctBy { Triple(it.source, it.id, it.digest) }
                    renderPlaylistSearchResults()
                } else {
                    val items = result.items.filterIsInstance<Song>()
                    allSongs = (if (more) allSongs + items else items).distinctBy { it.source to it.id }
                    filterSongs()
                }
                loadMore.visibility = if (result.hasMore) View.VISIBLE else View.GONE
                if (result.failedSources.isNotEmpty()) setCatalogStatus("${status.text}\n${getString(R.string.search_partial, result.failedSources.joinToString { sourceLabel(it) })}")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (current()) {
                    catalogLoading = false
                    setCatalogStatus(if (e is SearchUnavailableException && source == SearchCatalog.ALL) getString(R.string.search_unavailable)
                        else getString(R.string.ui_3ce71e5b6037, java.lang.String.valueOf((e.cause ?: e).localizedMessage)))
                    loadMore.visibility = View.VISIBLE
                }
            }
        }
    }
    private fun filterSongs() {
        if (isCatalogPage && playlistSearchMode) { renderPlaylistSearchResults(); return }
        updateNavigationSelection()
        tableHeader?.apply { removeAllViews(); addView(buildTableHeader()) }
        val query = search.text.toString().trim()
        visibleSongs = if (onlineMode) allSongs else allSongs.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
        status.setTextColor(palette.muted); status.contentDescription = null
        status.text = if (!onlineMode && activePlaylist == null && !hasAudioPermission()) {
            status.setTextColor(palette.primary); status.contentDescription = getString(R.string.ui_ccdab23ef0b3)
            getString(R.string.ui_799c8f89223c)
        } else if (allSongs.isEmpty()) getString(R.string.ui_88034b35be15) else getString(R.string.ui_119b62e66b9d, java.lang.String.valueOf(visibleSongs.size))
        if (isCatalogPage && query.isEmpty() && allSongs.isEmpty() && !catalogLoading) status.text = if (uiLayout.expanded) getString(R.string.ui_bd997b86cde1) else ""
        updateSearchChrome()
        val restore = pendingScroll
        val firstVisible = if (preferences.saveScroll) (restore?.first ?: list.firstVisiblePosition).coerceAtMost((visibleSongs.size - 1).coerceAtLeast(0)) else 0
        val top = restore?.second ?: (list.getChildAt(0)?.top ?: 0)
        list.adapter = object : ArrayAdapter<Song>(this, android.R.layout.simple_list_item_1, visibleSongs) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val song = getItem(position)!!
                val playing = highlightedSong == "${song.source}:${song.id}"
                val row = LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL; minimumHeight = dp(if (uiLayout.expanded) 52 else 64)
                    setPadding(0, dp(6), 0, dp(6)); background = lx.touch(if (playing) lx.selected else Color.TRANSPARENT, 0)
                    isActivated = playing
                }
                row.addView(TextView(context).apply {
                    text = if (playing) "♪" else "${position + 1}"; textSize = 13f; setTextColor(if (playing) palette.primary else palette.faint); gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(dp(if (uiLayout.expanded) 48 else 40), -1))
                val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(if (uiLayout.expanded) 8 else 0), 0, dp(8), 0) }
                info.addView(lx.label(song.title, 14f, if (playing) palette.primary else palette.foreground))
                val album = if (preferences.showAlbum) song.sourceInfo?.let { runCatching { org.json.JSONObject(it).optString("albumName") }.getOrNull() } else null
                info.addView(TextView(context).apply {
                    val source = if (preferences.showSource) "${UiPreferences.sourceName(context, song.source)}  " else ""
                    text = if (uiLayout.expanded) source.trim() else source + song.artist + if (album.isNullOrBlank()) "" else " · $album"
                    textSize = 11f; setTextColor(palette.muted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    visibility = if (text.isBlank()) View.GONE else View.VISIBLE
                })
                row.addView(info, LinearLayout.LayoutParams(0, -2, if (uiLayout.expanded) 2f else 1f))
                if (uiLayout.expanded) {
                    row.addView(lx.label(song.artist, 13f, palette.muted).apply { setPadding(dp(8), 0, dp(8), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                    if (uiLayout.albumColumn && preferences.showAlbum) row.addView(lx.label(album?.takeIf { it.isNotBlank() } ?: "—", 13f, palette.muted).apply {
                        setPadding(dp(8), 0, dp(8), 0)
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }
                row.addView(lx.label(formatTime(song.duration.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()), 12f, palette.faint).apply {
                    gravity = Gravity.CENTER
                    visibility = if (preferences.showDuration) View.VISIBLE else View.GONE
                }, LinearLayout.LayoutParams(dp(if (uiLayout.expanded) 60 else 46), -2))
                row.addView(lx.icon(59667, getString(R.string.ui_507e04d42f14), 16f) {}.apply {
                    isFocusable = false
                    setOnClickListener { showSongActions(it, song) }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                palette.textStyle(row)
                return row
            }
        }
        if (firstVisible < visibleSongs.size) {
            list.setSelectionFromTop(firstVisible, top)
            if (restore != null) list.post { if (list.isShown && list.childCount > 0 && list.firstVisiblePosition == firstVisible) pendingScroll = null }
        }
    }
    private fun renderPlayer(state: PlayerState) {
        val song = state.song
        chartsFragment()?.renderPlayback(song)
        val nextLyricKey = song?.let { "${it.source}:${it.id}" }
        if (highlightedSong != nextLyricKey) {
            highlightedSong = nextLyricKey
            (list.adapter as? ArrayAdapter<*>)?.notifyDataSetChanged()
        }
        if (nextLyricKey != artworkKey) {
            artworkKey = nextLyricKey; artworkBitmap = service?.artworkState?.value?.takeIf { it.key == nextLyricKey }?.bitmap; updateArtwork()
        }
        if (nextLyricKey != lyricKey) {
            lyricKey = nextLyricKey; lyricDelayMs = song?.let(lyricOffsets::get) ?: 0
            lyricLines = service?.lyricState?.value?.takeIf { it.key == nextLyricKey }?.lines ?: emptyList()
        }
        updateLyric(state.position)
        title.text = state.error ?: state.song?.let { "${it.title} · ${it.artist}" } ?: getString(R.string.ui_2389f5b06bce)
        toggle.text = (if (state.playing) 59661 else 59653).toChar().toString()
        toggle.contentDescription = when { state.preparing -> getString(R.string.ui_d04fcbda737f); state.playing -> getString(R.string.ui_8d12fc0d4eb2); else -> getString(R.string.ui_c3396195e91c) }
        toggle.isEnabled = state.song != null && !state.preparing
        seek.max = state.duration.coerceAtLeast(0)
        seek.isEnabled = preferences.allowSeek && song != null
        detailSeek?.isEnabled = preferences.allowSeek && song != null
        updateMiniPlayerVisibility(song)
        if (!changingSeek) seek.progress = state.position
        time.text = "${formatTime(state.position)} / ${formatTime(state.duration)}"
        renderDetails(state)
        if (playerRestorePending) {
            playerRestorePending = false; showPlayerDetails()
            if (restoreDetailLyrics && !uiLayout.splitPlayer) toggleDetailPage()
        }
        if (startupPlayerPending && song != null) { startupPlayerPending = false; showPlayerDetails() }
    }
    private fun updateMiniPlayerVisibility(song: Song?) {
        miniBar.visibility = if (navigation?.currentDestination?.id == R.id.settings_page || preferences.autoHidePlayer && song == null) View.GONE else View.VISIBLE
        seek.visibility = miniBar.visibility
    }
    private fun updateLyric(position: Int) {
        lyric.maxLines = if (playerSettings.showTranslation || playerSettings.showRomanization) 3 else 1
        lyric.text = lyricLines.getOrNull(LrcParser.indexAt(lyricLines, LyricTiming.lyricPosition(position.toLong(), lyricDelayMs)))
            ?.let { LyricRendering.text(it, playerSettings) } ?: if (lyricLines.isEmpty()) "" else "♪"
    }
    private fun formatTime(milliseconds: Int): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    companion object { private const val REQUEST_AUDIO = 1; private const val REQUEST_LYRIC = 3; private const val REQUEST_SOURCE = 4; private const val REQUEST_BACKUP = 5; private const val REQUEST_DOWNLOAD = 6; private const val REQUEST_EXPORT = 7; private const val REQUEST_SYNC_NOTIFICATION = 8; private const val REQUEST_SOURCE_EXPORT = 9; private const val REQUEST_LOG_EXPORT = 10; private const val REQUEST_METADATA = 11 }
}

