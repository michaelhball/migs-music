package com.migsmusic.data.local.model

data class AlbumSummary(
    val key: String,
    val title: String,
    val artist: String,
    val songCount: Int,
    val albumArtUri: String? = null,
)
