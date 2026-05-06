package com.migsmusic.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.migsmusic.AppPreferences
import com.migsmusic.data.local.model.PlaylistSong
import com.migsmusic.data.local.model.PlaylistSummary
import com.migsmusic.data.repository.LibraryRepository
import com.migsmusic.data.repository.PlaylistRepository
import com.migsmusic.playback.PlaybackManager
import com.migsmusic.playlistimport.DiscoveredM3u
import com.migsmusic.playlistimport.M3uEntry
import com.migsmusic.playlistimport.MatchedSong
import com.migsmusic.playlistimport.matchM3uEntries
import com.migsmusic.playlistimport.parseM3u
import com.migsmusic.playlistimport.scanForM3uFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Staging state for an M3U import. Surfaced to the UI between picking a file and committing
 * the new playlist, so the dialog can show match counts + let the user edit the name.
 */
data class M3uImportStaging(
    val defaultName: String,
    val matched: List<MatchedSong>,
    val unmatched: List<M3uEntry>,
)

enum class PlaylistSortOrder(val label: String) {
    NAME_ASC("Name A→Z"),
    NAME_DESC("Name Z→A"),
    SONG_COUNT_DESC("Most songs"),
}

/**
 * Sort options that apply *within* a playlist. [DEFAULT] preserves the canonical position
 * order (i.e. the order songs were imported from M3U or added manually). All other options
 * are display-only — they don't mutate the underlying `position` field — so picking [DEFAULT]
 * always restores the original order. Drag-to-reorder is hidden when sort != [DEFAULT].
 */
enum class PlaylistContentSortOrder(val label: String) {
    DEFAULT("Default order"),
    TITLE_ASC("Title A→Z"),
    TITLE_DESC("Title Z→A"),
    ARTIST_ASC("Artist A→Z"),
    DURATION_ASC("Shortest first"),
    DURATION_DESC("Longest first"),
}

class PlaylistsViewModel(
    private val playlistRepository: PlaylistRepository,
    private val libraryRepository: LibraryRepository,
    private val playbackManager: PlaybackManager,
    private val preferences: AppPreferences,
) : ViewModel() {
    private val playlistSort = MutableStateFlow(preferences.playlistSortOrder)
    val playlistSortOrder: StateFlow<PlaylistSortOrder> = playlistSort.asStateFlow()

    // Lazily: starts on first subscribe, then keeps the value cached for the VM's lifetime.
    // Using WhileSubscribed restarted from `emptyList` on every Playlists-tab navigation, which
    // intermittently raced with Room's invalidation-tracker debounce and made `openReorderRemoveDelete`
    // flaky. The cost of keeping ~tens of PlaylistSummary rows in memory is negligible.
    val playlists: StateFlow<List<PlaylistSummary>> =
        combine(playlistRepository.observePlaylists(), playlistSort) { list, order ->
            sortPlaylists(list, order)
        }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun setPlaylistSortOrder(order: PlaylistSortOrder) {
        playlistSort.value = order
        preferences.playlistSortOrder = order
    }

    private fun sortPlaylists(
        items: List<PlaylistSummary>,
        order: PlaylistSortOrder,
    ): List<PlaylistSummary> =
        when (order) {
            PlaylistSortOrder.NAME_ASC -> items.sortedBy { it.name.lowercase() }
            PlaylistSortOrder.NAME_DESC -> items.sortedByDescending { it.name.lowercase() }
            PlaylistSortOrder.SONG_COUNT_DESC ->
                items.sortedWith(compareByDescending<PlaylistSummary> { it.songCount }.thenBy { it.name.lowercase() })
        }

    fun playlistSongs(playlistId: Long): StateFlow<List<PlaylistSong>> =
        playlistRepository.observePlaylistSongs(playlistId)
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { playlistRepository.createPlaylist(name) }
    }

    fun createPlaylistAndAddSong(
        name: String,
        songId: Long,
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val playlistId = playlistRepository.createPlaylist(name)
            playlistRepository.addSong(playlistId, songId)
        }
    }

    /** Creates a new playlist and adds all [songIds] in order. Used by "Save queue as playlist". */
    fun createPlaylistFromSongs(
        name: String,
        songIds: List<Long>,
    ) {
        if (name.isBlank() || songIds.isEmpty()) return
        viewModelScope.launch {
            val playlistId = playlistRepository.createPlaylist(name)
            songIds.forEach { playlistRepository.addSong(playlistId, it) }
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch { playlistRepository.deletePlaylist(playlistId) }
    }

    fun renamePlaylist(
        playlistId: Long,
        name: String,
    ) {
        if (name.isBlank()) return
        viewModelScope.launch { playlistRepository.renamePlaylist(playlistId, name) }
    }

    fun addSong(
        playlistId: Long,
        songId: Long,
    ) {
        viewModelScope.launch { playlistRepository.addSong(playlistId, songId) }
    }

    fun removeSong(playlistItemId: Long) {
        viewModelScope.launch { playlistRepository.removeSong(playlistItemId) }
    }

    fun moveSong(
        playlistId: Long,
        fromIndex: Int,
        toIndex: Int,
    ) {
        viewModelScope.launch { playlistRepository.moveSong(playlistId, fromIndex, toIndex) }
    }

    /**
     * Reverts every song in [playlistId] to its `originalPosition` — i.e. the order it was
     * imported from M3U / added in. The user-facing wording is "Restore import order".
     */
    fun restoreOriginalOrder(playlistId: Long) {
        viewModelScope.launch { playlistRepository.restoreOriginalOrder(playlistId) }
    }

    fun playPlaylist(
        songs: List<PlaylistSong>,
        startIndex: Int,
        shuffle: Boolean = false,
    ) {
        playbackManager.playContext(
            songIds = songs.map { it.songId },
            startIndex = startIndex,
            shuffle = shuffle,
        )
    }

    fun addSongToQueueNext(songId: Long) {
        playbackManager.playNext(songId)
    }

    fun addSongToQueueLater(songId: Long) {
        playbackManager.playLater(songId)
    }

    // ---- M3U import ----

    private val _importStaging = MutableStateFlow<M3uImportStaging?>(null)
    val importStaging: StateFlow<M3uImportStaging?> = _importStaging.asStateFlow()

    /**
     * Parses [m3uContent] (already read from a content URI by the UI), matches it against
     * the current library, and stages the result. The dialog observes [importStaging] to
     * render match counts and the editable name field.
     *
     * @param defaultName starting playlist name (typically the M3U filename minus ext).
     */
    fun previewM3uImport(
        m3uContent: String,
        defaultName: String,
    ) {
        viewModelScope.launch {
            val library = libraryRepository.observeAllSongs().first()
            val (matched, unmatched) =
                withContext(Dispatchers.Default) {
                    val entries = parseM3u(m3uContent)
                    val result = matchM3uEntries(entries, library)
                    result.matched to result.unmatched
                }
            _importStaging.value =
                M3uImportStaging(
                    defaultName = defaultName,
                    matched = matched,
                    unmatched = unmatched,
                )
        }
    }

    fun cancelM3uImport() {
        _importStaging.value = null
    }

    /**
     * Commits the staged import — creates a fresh playlist with [name] containing the
     * already-matched songs in M3U order. Clears staging on completion.
     */
    fun commitM3uImport(name: String) {
        val staging = _importStaging.value ?: return
        val songIds = staging.matched.map { it.song.id }
        if (name.isBlank() || songIds.isEmpty()) return
        viewModelScope.launch {
            playlistRepository.createPlaylistWithSongs(name, songIds)
            _importStaging.value = null
        }
    }

    // ---- Auto-detected M3U files in the user's Music folder ----

    private val _musicFolderUri = MutableStateFlow(preferences.musicFolderTreeUri?.let(Uri::parse))
    val musicFolderUri: StateFlow<Uri?> = _musicFolderUri.asStateFlow()

    // Seed from the persisted cache so the Playlists tab shows the previously-discovered list
    // immediately on cold start. The next refreshAvailableM3uFiles() call walks SAF in the
    // background and updates this if anything's changed.
    private val _availableM3uFiles = MutableStateFlow<List<DiscoveredM3u>>(loadCachedM3uList())
    val availableM3uFiles: StateFlow<List<DiscoveredM3u>> = _availableM3uFiles.asStateFlow()

    private fun loadCachedM3uList(): List<DiscoveredM3u> =
        preferences.cachedDiscoveredM3uTsv
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size == 2 && parts[0].isNotBlank()) {
                    DiscoveredM3u(uri = Uri.parse(parts[0]), displayName = parts[1])
                } else {
                    null
                }
            }
            .toList()

    private fun saveCachedM3uList(items: List<DiscoveredM3u>) {
        preferences.cachedDiscoveredM3uTsv =
            items.joinToString(separator = "\n") { "${it.uri}\t${it.displayName}" }
    }

    /**
     * Persists the SAF tree URI the user just granted via [ActivityResultContracts.OpenDocumentTree],
     * after the caller has already taken a persistable read permission via the ContentResolver.
     */
    fun setMusicFolderUri(uri: Uri?) {
        preferences.musicFolderTreeUri = uri?.toString()
        _musicFolderUri.value = uri
    }

    /**
     * Scans the SAF tree at [treeUri] for `.m3u` / `.m3u8` files and updates
     * [availableM3uFiles]. The Playlists screen calls this on entry. No-op when [treeUri]
     * is null (user hasn't granted folder access yet) — clears the list to keep state honest.
     */
    fun refreshAvailableM3uFiles(
        context: Context,
        treeUri: Uri?,
    ) {
        if (treeUri == null) {
            _availableM3uFiles.value = emptyList()
            saveCachedM3uList(emptyList())
            return
        }
        viewModelScope.launch {
            val fresh = scanForM3uFiles(context, treeUri)
            _availableM3uFiles.value = fresh
            // Persist so the next cold start renders this list instantly without waiting
            // for SAF to walk the tree again.
            saveCachedM3uList(fresh)
        }
    }

    /**
     * Reads [uri] (a content URI, typically from [DiscoveredM3u.uri]), parses + matches it,
     * and stages the result for the import dialog to render.
     */
    fun importDiscoveredM3u(
        context: Context,
        uri: Uri,
        defaultName: String,
    ) {
        viewModelScope.launch {
            val content =
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                    }.getOrNull().orEmpty()
                }
            if (content.isBlank()) return@launch
            previewM3uImport(content, defaultName)
        }
    }
}
