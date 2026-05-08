package com.migsmusic.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.migsmusic.AppPreferences
import com.migsmusic.data.repository.LovesRepository
import com.migsmusic.playback.PlaybackManager
import com.migsmusic.playback.PlaybackUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    private val playbackManager: PlaybackManager,
    private val preferences: AppPreferences,
    private val lovesRepository: LovesRepository,
) : ViewModel() {
    val playbackUiState: StateFlow<PlaybackUiState> = playbackManager.uiState
    val currentPositionMs: StateFlow<Long> = playbackManager.currentPositionMs
    val shuffleEnabled: StateFlow<Boolean> = playbackManager.shuffleEnabled
    val crossfadeMs: StateFlow<Long> = playbackManager.crossfadeMs

    private val _confirmQueueJump = MutableStateFlow(preferences.confirmQueueJump)
    val confirmQueueJump: StateFlow<Boolean> = _confirmQueueJump.asStateFlow()

    fun setConfirmQueueJump(value: Boolean) {
        preferences.confirmQueueJump = value
        _confirmQueueJump.value = value
    }

    fun setCrossfadeMs(ms: Long) = playbackManager.setCrossfadeMs(ms)

    /** Convenience flow exposing just the currently-playing song id (or null). */
    val currentSongId: StateFlow<Long?> = playbackManager.currentSongId

    /**
     * Reactive heart state for whatever song is currently playing. Null current song
     * → false. Switching tracks tears down the prior `observeIsLoved` flow and
     * subscribes to the new one (flatMapLatest), so the heart button lights up
     * immediately on each track transition.
     */
    val isCurrentSongLoved: StateFlow<Boolean> =
        playbackManager.currentSongId
            .flatMapLatest { id ->
                if (id == null) flowOf(false) else lovesRepository.observeIsLoved(id)
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun toggleLoveCurrent() {
        val songId = playbackManager.currentSongId.value ?: return
        viewModelScope.launch {
            // Read current state from the source of truth rather than `isCurrentSongLoved.value`
            // — the StateFlow lags by one emission relative to the DB on a fresh subscription.
            val currentlyLoved = lovesRepository.observeIsLoved(songId).first()
            if (currentlyLoved) lovesRepository.unlove(songId) else lovesRepository.love(songId)
        }
    }

    fun toggleShuffle() = playbackManager.toggleShuffle()

    fun togglePlayPause() = playbackManager.togglePlayPause()

    fun skipToNext() = playbackManager.skipToNext()

    fun skipToPrevious() = playbackManager.skipToPrevious()

    fun jumpToEntry(entryId: String) = playbackManager.jumpToEntry(entryId)

    fun removeUpcoming(entryId: String) = playbackManager.removeUpcoming(entryId)

    fun moveUpcoming(
        entryId: String,
        newIndex: Int,
    ) = playbackManager.moveUpcoming(entryId, newIndex)

    fun seekTo(positionMs: Long) = playbackManager.seekTo(positionMs)

    fun cycleRepeatMode() = playbackManager.cycleRepeatMode()

    fun clearUpcoming() = playbackManager.clearUpcoming()
}
