package com.migsmusic.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.migsmusic.AppPreferences
import com.migsmusic.data.local.entity.SongEntity
import com.migsmusic.data.local.model.AlbumSummary
import com.migsmusic.data.local.model.ArtistSummary
import com.migsmusic.data.local.model.FolderSummary
import com.migsmusic.data.repository.LibraryRepository
import com.migsmusic.playback.PlaybackManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val libraryRepository: LibraryRepository,
    private val playbackManager: PlaybackManager,
    private val preferences: AppPreferences,
) : ViewModel() {
    private val searchQuery = MutableStateFlow("")
    private val scanState = MutableStateFlow(LibraryScanState())
    private val sortOrder = MutableStateFlow(preferences.songSortOrder)
    val songSortOrder: StateFlow<SongSortOrder> = sortOrder
    private val albumSort = MutableStateFlow(preferences.albumSortOrder)
    val albumSortOrder: StateFlow<AlbumSortOrder> = albumSort
    private val artistSort = MutableStateFlow(preferences.artistSortOrder)
    val artistSortOrder: StateFlow<ArtistSortOrder> = artistSort

    val folders: StateFlow<List<FolderSummary>> =
        libraryRepository.observeFolders()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val allSongsSource = libraryRepository.observeAllSongs()

    private val debouncedQuery =
        searchQuery
            .debounce(150)
            .map(String::trim)
            .distinctUntilChanged()

    val visibleSongs: StateFlow<List<SongEntity>> =
        combine(
            debouncedQuery.flatMapLatest { query ->
                if (query.isBlank()) allSongsSource else libraryRepository.searchSongs(query)
            },
            sortOrder,
        ) { songs, order -> sortSongs(songs, order) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val artists: StateFlow<List<ArtistSummary>> =
        combine(libraryRepository.observeArtists(), artistSort) { list, order -> sortArtists(list, order) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val albums: StateFlow<List<AlbumSummary>> =
        combine(libraryRepository.observeAlbums(), albumSort) { list, order -> sortAlbums(list, order) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val libraryScanState: StateFlow<LibraryScanState> = scanState

    /**
     * Cold-start scan, exactly once per process. Cached DB rows are visible immediately
     * via [allSongsSource]; this rescan refreshes anything that changed while the app was
     * killed. Subsequent Activity recreations within the same process don't re-scan
     * (avoids thrashing the UI when navigation triggers many recompositions);
     * [LibrarySyncObserver] handles further changes via MediaStore notifications.
     */
    fun ensureScanned() {
        if (!scannedThisProcess.compareAndSet(false, true)) return
        viewModelScope.launch {
            if (!scanState.value.isScanning) {
                scanLibrary()
            }
        }
    }

    private companion object {
        // Process-wide guard so that Activity recreates / config changes don't kick off
        // a fresh scan each time. ContentObserver covers in-process changes after this.
        private val scannedThisProcess = AtomicBoolean(false)
    }

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setSongSortOrder(order: SongSortOrder) {
        sortOrder.value = order
        preferences.songSortOrder = order
    }

    fun setAlbumSortOrder(order: AlbumSortOrder) {
        albumSort.value = order
        preferences.albumSortOrder = order
    }

    fun setArtistSortOrder(order: ArtistSortOrder) {
        artistSort.value = order
        preferences.artistSortOrder = order
    }

    private fun sortAlbums(
        items: List<AlbumSummary>,
        order: AlbumSortOrder,
    ): List<AlbumSummary> =
        when (order) {
            AlbumSortOrder.TITLE_ASC -> items.sortedWith(compareBy({ it.title.lowercase() }, { it.artist.lowercase() }))
            AlbumSortOrder.TITLE_DESC ->
                items.sortedWith(compareByDescending<AlbumSummary> { it.title.lowercase() }.thenBy { it.artist.lowercase() })
            AlbumSortOrder.ARTIST_ASC -> items.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
            AlbumSortOrder.SONG_COUNT_DESC -> items.sortedWith(compareByDescending<AlbumSummary> { it.songCount }.thenBy { it.title.lowercase() })
        }

    private fun sortArtists(
        items: List<ArtistSummary>,
        order: ArtistSortOrder,
    ): List<ArtistSummary> =
        when (order) {
            ArtistSortOrder.NAME_ASC -> items.sortedBy { it.name.lowercase() }
            ArtistSortOrder.NAME_DESC -> items.sortedByDescending { it.name.lowercase() }
            ArtistSortOrder.SONG_COUNT_DESC -> items.sortedWith(compareByDescending<ArtistSummary> { it.songCount }.thenBy { it.name.lowercase() })
            ArtistSortOrder.ALBUM_COUNT_DESC -> items.sortedWith(compareByDescending<ArtistSummary> { it.albumCount }.thenBy { it.name.lowercase() })
        }

    private fun sortSongs(
        songs: List<SongEntity>,
        order: SongSortOrder,
    ): List<SongEntity> =
        when (order) {
            SongSortOrder.TITLE_ASC -> songs.sortedWith(compareBy({ it.title.lowercase() }, { it.artist.lowercase() }))
            SongSortOrder.TITLE_DESC ->
                songs.sortedWith(
                    compareByDescending<SongEntity> { it.title.lowercase() }.thenBy { it.artist.lowercase() },
                )
            SongSortOrder.ARTIST_ASC ->
                songs.sortedWith(
                    compareBy(
                        { it.artist.lowercase() },
                        { it.album.lowercase() },
                        { it.discNumber },
                        { it.trackNumber },
                        { it.title.lowercase() },
                    ),
                )
            SongSortOrder.DATE_ADDED_DESC -> songs.sortedByDescending { it.dateAddedSeconds }
            SongSortOrder.DURATION_ASC -> songs.sortedBy { it.durationMs }
            SongSortOrder.DURATION_DESC -> songs.sortedByDescending { it.durationMs }
        }

    fun songsInFolder(folderPath: String): Flow<List<SongEntity>> = libraryRepository.observeSongsInFolder(folderPath)

    /** Subfolders directly under [parentPath]. Empty string = top-level. */
    fun subfoldersOf(parentPath: String): Flow<List<FolderSummary>> = libraryRepository.observeSubfolders(parentPath)

    /** Songs whose folderPath equals [parentPath] exactly (no recursion). */
    fun directSongsIn(parentPath: String): Flow<List<SongEntity>> = libraryRepository.observeDirectSongsIn(parentPath)

    /** All songs in [parentPath] and its subfolders (recursive). */
    fun songsRecursivelyIn(parentPath: String): Flow<List<SongEntity>> = libraryRepository.observeSongsRecursivelyIn(parentPath)

    fun songsByArtist(artist: String): Flow<List<SongEntity>> = libraryRepository.observeSongsByArtist(artist)

    fun songsByAlbum(albumKey: String): Flow<List<SongEntity>> = libraryRepository.observeSongsByAlbum(albumKey)

    fun scanLibrary() {
        if (scanState.value.isScanning) return
        viewModelScope.launch {
            scanState.value = LibraryScanState(isScanning = true)
            runCatching { libraryRepository.scanDevice() }
                .onSuccess { count ->
                    scanState.value = LibraryScanState(lastScanCount = count)
                    playbackManager.refreshLibraryState()
                }
                .onFailure { throwable ->
                    scanState.value = LibraryScanState(errorMessage = throwable.message ?: "Scan failed")
                }
        }
    }

    fun playSongs(
        songs: List<SongEntity>,
        startIndex: Int,
        shuffle: Boolean = false,
    ) {
        playbackManager.playContext(
            songIds = songs.map { it.id },
            startIndex = startIndex,
            shuffle = shuffle,
        )
    }

    fun playNext(songId: Long) {
        playbackManager.playNext(songId)
    }

    fun playLater(songId: Long) {
        playbackManager.playLater(songId)
    }
}

data class LibraryScanState(
    val isScanning: Boolean = false,
    val lastScanCount: Int? = null,
    val errorMessage: String? = null,
)

enum class SongSortOrder(val label: String) {
    TITLE_ASC("Title A→Z"),
    TITLE_DESC("Title Z→A"),
    ARTIST_ASC("Artist A→Z"),
    DATE_ADDED_DESC("Recently added"),
    DURATION_ASC("Shortest first"),
    DURATION_DESC("Longest first"),
}

enum class AlbumSortOrder(val label: String) {
    TITLE_ASC("Title A→Z"),
    TITLE_DESC("Title Z→A"),
    ARTIST_ASC("Artist A→Z"),
    SONG_COUNT_DESC("Most songs"),
}

enum class ArtistSortOrder(val label: String) {
    NAME_ASC("Name A→Z"),
    NAME_DESC("Name Z→A"),
    SONG_COUNT_DESC("Most songs"),
    ALBUM_COUNT_DESC("Most albums"),
}
