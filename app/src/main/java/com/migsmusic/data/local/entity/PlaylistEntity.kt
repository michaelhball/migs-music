package com.migsmusic.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /**
     * True if this playlist's contents are mirrored from a sync source (currently the Mac
     * companion app). The sync flow replaces the contents of synced playlists only —
     * manual playlists the user created directly on the phone are never touched, even if a
     * synced playlist arrives with the same name (they're stored as separate rows).
     *
     * Defaults to false on insert and on the v3→v4 migration backfill, so every playlist
     * that existed before the sync feature was added is correctly classified as manual.
     */
    val syncedFromMac: Boolean = false,
)
