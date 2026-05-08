package com.migsmusic.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * One row per song the user has hearted. Keyed on `songAbsolutePath` rather than
 * MediaStore `_ID` so a heart survives MediaScanner's tag-rescan _ID churn — see
 * [MIGRATION_6_7] for background. CASCADE means deleting a song from the library
 * (which now happens only when its file is genuinely gone, not on transient state)
 * auto-removes its heart.
 *
 * Hearts are local-only — never touched by the Mac sync flow — so they survive
 * every playlist replace, every orphan cleanup, and every full re-sync.
 *
 * `addedAtSeconds` is epoch-seconds of when the heart was added; "Loves" is sorted
 * most-recent-first so the user's latest favourites are at the top.
 */
@Entity(
    tableName = "loved_songs",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["absolutePath"],
            childColumns = ["songAbsolutePath"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class LovedSongEntity(
    @PrimaryKey val songAbsolutePath: String,
    val addedAtSeconds: Long,
)
