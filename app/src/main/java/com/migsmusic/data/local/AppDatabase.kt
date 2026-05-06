package com.migsmusic.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.migsmusic.data.local.dao.PlaybackSnapshotDao
import com.migsmusic.data.local.dao.PlaylistDao
import com.migsmusic.data.local.dao.SongDao
import com.migsmusic.data.local.entity.PlaybackSnapshotEntity
import com.migsmusic.data.local.entity.PlaylistEntity
import com.migsmusic.data.local.entity.PlaylistSongEntity
import com.migsmusic.data.local.entity.SongEntity

@Database(
    entities = [
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        PlaybackSnapshotEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao

    abstract fun playlistDao(): PlaylistDao

    abstract fun playbackSnapshotDao(): PlaybackSnapshotDao
}

/**
 * v3 adds `playlist_songs.originalPosition` so users can revert a manually-reordered playlist
 * to the order it was imported / added in. We backfill from the existing `position` so any
 * playlists already on the device get a sensible default (i.e. their current order at the
 * time of upgrade becomes the new "original").
 */
val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE playlist_songs ADD COLUMN originalPosition INTEGER")
            db.execSQL("UPDATE playlist_songs SET originalPosition = position")
        }
    }

/**
 * v4 adds `playlists.syncedFromMac` so the upcoming sync feature can distinguish playlists
 * that mirror a Mac source (replaceable on each sync) from manually-created playlists
 * (never touched by sync). Existing rows backfill to 0 (false) — anything that existed
 * before the feature is by definition not synced.
 */
val MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE playlists ADD COLUMN syncedFromMac INTEGER NOT NULL DEFAULT 0")
        }
    }

/**
 * v5 adds `songs.absolutePath` so the next scan can use it as the stable identity of each
 * track across MediaStore _ID reassignment. Existing rows get an empty string; the next
 * `scanDevice()` pass populates it. Until then the remap logic in scanDevice can't help —
 * but the `@Upsert` change earlier in the migration sequence already prevents the worst
 * failure mode (CASCADE-wiping `playlist_songs` on every scan).
 */
val MIGRATION_4_5 =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE songs ADD COLUMN absolutePath TEXT NOT NULL DEFAULT ''")
        }
    }
