package com.migsmusic.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.migsmusic.data.local.entity.SongEntity
import com.migsmusic.data.local.model.PlaylistSong
import com.migsmusic.data.local.model.PlaylistSummary
import com.migsmusic.data.repository.PlaylistRepository
import com.migsmusic.playback.PlaybackManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistsViewModel(
    private val playlistRepository: PlaylistRepository,
    private val playbackManager: PlaybackManager,
) : ViewModel() {
    // Lazily: starts on first subscribe, then keeps the value cached for the VM's lifetime.
    // Using WhileSubscribed restarted from `emptyList` on every Playlists-tab navigation, which
    // intermittently raced with Room's invalidation-tracker debounce and made `openReorderRemoveDelete`
    // flaky. The cost of keeping ~tens of PlaylistSummary rows in memory is negligible.
    val playlists: StateFlow<List<PlaylistSummary>> = playlistRepository.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun playlistSongs(playlistId: Long): StateFlow<List<PlaylistSong>> =
        playlistRepository.observePlaylistSongs(playlistId)
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { playlistRepository.createPlaylist(name) }
    }

    fun createPlaylistAndAddSong(name: String, songId: Long) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val playlistId = playlistRepository.createPlaylist(name)
            playlistRepository.addSong(playlistId, songId)
        }
    }

    /** Creates a new playlist and adds all [songIds] in order. Used by "Save queue as playlist". */
    fun createPlaylistFromSongs(name: String, songIds: List<Long>) {
        if (name.isBlank() || songIds.isEmpty()) return
        viewModelScope.launch {
            val playlistId = playlistRepository.createPlaylist(name)
            songIds.forEach { playlistRepository.addSong(playlistId, it) }
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch { playlistRepository.deletePlaylist(playlistId) }
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { playlistRepository.renamePlaylist(playlistId, name) }
    }

    fun addSong(playlistId: Long, songId: Long) {
        viewModelScope.launch { playlistRepository.addSong(playlistId, songId) }
    }

    fun removeSong(playlistItemId: Long) {
        viewModelScope.launch { playlistRepository.removeSong(playlistItemId) }
    }

    fun moveSong(playlistId: Long, fromIndex: Int, toIndex: Int) {
        viewModelScope.launch { playlistRepository.moveSong(playlistId, fromIndex, toIndex) }
    }

    fun playPlaylist(songs: List<PlaylistSong>, startIndex: Int, shuffle: Boolean = false) {
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
}
