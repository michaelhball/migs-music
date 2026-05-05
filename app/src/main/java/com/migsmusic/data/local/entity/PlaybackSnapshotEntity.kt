package com.migsmusic.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playback_snapshot")
data class PlaybackSnapshotEntity(
    @PrimaryKey val snapshotId: Int = 0,
    val currentEntryId: String?,
    val currentSongId: Long?,
    val currentPositionMs: Long,
    val isPlaying: Boolean,
    val repeatMode: Int,
    val historyEntries: String,
    val nextEntries: String,
    val laterEntries: String,
    val remainingEntries: String,
    val nextEntrySeed: Long,
)
