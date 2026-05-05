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
)
