package com.migsmusic.data.local.model

data class PlaylistSong(
    val playlistItemId: Long,
    val playlistId: Long,
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val position: Int,
    val albumArtUri: String?,
)
