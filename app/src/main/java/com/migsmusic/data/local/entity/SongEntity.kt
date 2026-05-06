package com.migsmusic.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: Long,
    val contentUri: String,
    val albumId: Long?,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val trackNumber: Int,
    val discNumber: Int,
    val folderPath: String,
    val folderName: String,
    val albumArtUri: String?,
    val dateAddedSeconds: Long,
    val dateModifiedSeconds: Long,
    /**
     * Absolute filesystem path (MediaStore.Audio.Media.DATA). Used as the *stable* identity
     * of the song across MediaStore _ID changes — when MediaScanner re-reads tags it can
     * reassign a file's _ID, which previously orphaned the song from any playlist that
     * referenced it. Now [com.migsmusic.data.repository.LibraryRepository.scanDevice]
     * detects (oldId, newId) for the same absolutePath and remaps `playlist_songs.songId`
     * before any cleanup, so playlists survive _ID churn.
     *
     * Empty string for rows from before the v5 migration; populated on next scan.
     */
    val absolutePath: String = "",
)
