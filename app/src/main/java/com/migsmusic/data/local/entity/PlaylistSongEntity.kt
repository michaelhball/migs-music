package com.migsmusic.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One song's membership in one playlist. The cross-table key is the song's
 * `absolutePath` (not its MediaStore _ID), so playlist contents survive when
 * MediaScanner reassigns _IDs during ID3-tag rescans — see [MIGRATION_6_7] for
 * background.
 */
@Entity(
    tableName = "playlist_songs",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["absolutePath"],
            childColumns = ["songAbsolutePath"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("playlistId"),
        Index("songAbsolutePath"),
    ],
)
data class PlaylistSongEntity(
    @PrimaryKey(autoGenerate = true) val playlistItemId: Long = 0,
    val playlistId: Long,
    val songAbsolutePath: String,
    val position: Int,
    val addedAtMillis: Long,
    /**
     * The position this song held when first added to the playlist. Captured at insert time
     * (M3U-import order, or manual-add order) and never mutated by reorder operations. Lets
     * the user "Restore import order" after manually reordering. Nullable for forward compat
     * with rows from before the v3 migration; the migration backfills this to equal `position`.
     */
    val originalPosition: Int? = null,
)
