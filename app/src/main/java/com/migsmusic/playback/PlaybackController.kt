package com.migsmusic.playback

import kotlinx.coroutines.flow.StateFlow

/**
 * Minimal contract that [com.migsmusic.playlistimport.AutoImportService] needs from playback,
 * so it can stop playback when the song that's currently playing comes from a playlist
 * that's about to be pruned. Extracting this slim interface lets AutoImportService be
 * tested without instantiating a full [PlaybackManager] (which spins up coroutines that
 * outlive a quick unit test and tries to query a closed Room DB).
 */
interface PlaybackController {
    val currentSongId: StateFlow<Long?>

    fun stopAndClearQueue()
}
