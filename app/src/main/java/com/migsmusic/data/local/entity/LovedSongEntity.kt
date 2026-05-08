package com.migsmusic.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * One row per song the user has hearted. The CASCADE FK means deleting a song from
 * the library auto-removes its heart. Hearts are local-only — never touched by the
 * Mac sync flow — so they survive every playlist replace, every orphan cleanup, and
 * every full re-sync.
 *
 * `addedAtSeconds` is epoch-seconds of when the heart was added; "Loves" is sorted
 * most-recent-first so the user's latest favourites are at the top.
 */
@Entity(
    tableName = "loved_songs",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class LovedSongEntity(
    @PrimaryKey val songId: Long,
    val addedAtSeconds: Long,
)
