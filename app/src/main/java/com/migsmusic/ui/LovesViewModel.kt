package com.migsmusic.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.migsmusic.data.local.entity.SongEntity
import com.migsmusic.data.repository.LovesRepository
import com.migsmusic.playback.PlaybackManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LovesViewModel(
    private val lovesRepository: LovesRepository,
    private val playbackManager: PlaybackManager,
) : ViewModel() {
    val songs: StateFlow<List<SongEntity>> =
        lovesRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

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

    fun playNext(songId: Long) = playbackManager.playNext(songId)

    fun playLater(songId: Long) = playbackManager.playLater(songId)

    fun unlove(songId: Long) {
        viewModelScope.launch { lovesRepository.unlove(songId) }
    }
}
