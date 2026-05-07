package com.migsmusic.ui

import androidx.lifecycle.ViewModel
import com.migsmusic.AppPreferences
import com.migsmusic.playback.PlaybackManager
import com.migsmusic.playback.PlaybackUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlayerViewModel(
    private val playbackManager: PlaybackManager,
    private val preferences: AppPreferences,
) : ViewModel() {
    val playbackUiState: StateFlow<PlaybackUiState> = playbackManager.uiState
    val currentPositionMs: StateFlow<Long> = playbackManager.currentPositionMs
    val shuffleEnabled: StateFlow<Boolean> = playbackManager.shuffleEnabled

    private val _confirmQueueJump = MutableStateFlow(preferences.confirmQueueJump)
    val confirmQueueJump: StateFlow<Boolean> = _confirmQueueJump.asStateFlow()

    fun setConfirmQueueJump(value: Boolean) {
        preferences.confirmQueueJump = value
        _confirmQueueJump.value = value
    }

    /** Convenience flow exposing just the currently-playing song id (or null). */
    val currentSongId: StateFlow<Long?> = playbackManager.currentSongId

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
