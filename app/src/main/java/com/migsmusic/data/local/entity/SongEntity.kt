package com.migsmusic.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "songs",
    indices = [Index(value = ["absolutePath"], unique = true)],
)
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
     * Absolute filesystem path (MediaStore.Audio.Media.DATA). The *cross-table identity*
     * for songs: `playlist_songs.songAbsolutePath` and `loved_songs.songAbsolutePath`
     * FK against this column (see [MIGRATION_6_7]). Stable across MediaStore _ID churn,
     * which is what makes playlists and hearts survive ID3-tag rescans.
     *
     * The MediaStore `_ID` still lives in [id] and is used by playback to build content
     * URIs; UI/playback resolve [id] from absolutePath via JOIN at query time so they
     * always see the current _ID even right after a rescan.
     */
    val absolutePath: String = "",
)
