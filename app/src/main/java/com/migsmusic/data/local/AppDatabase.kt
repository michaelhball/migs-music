package com.migsmusic.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.migsmusic.data.local.dao.LovedSongDao
import com.migsmusic.data.local.dao.PlaybackSnapshotDao
import com.migsmusic.data.local.dao.PlaylistDao
import com.migsmusic.data.local.dao.SongDao
import com.migsmusic.data.local.entity.LovedSongEntity
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
        LovedSongEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao

    abstract fun playlistDao(): PlaylistDao

    abstract fun playbackSnapshotDao(): PlaybackSnapshotDao

    abstract fun lovedSongDao(): LovedSongDao
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

/**
 * v6 adds the `loved_songs` table — local-only "hearts" that survive every
 * Mac sync. Cascade delete from `songs` so removing a song from the library
 * also removes its heart.
 */
val MIGRATION_5_6 =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE loved_songs (
                    songId INTEGER NOT NULL PRIMARY KEY,
                    addedAtSeconds INTEGER NOT NULL,
                    FOREIGN KEY(songId) REFERENCES songs(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
        }
    }

/**
 * v7 fixes the architectural smell where cross-table song references (in playlist_songs
 * and loved_songs) keyed off `songs.id` — which is MediaStore's `_ID`, a value that
 * MediaScanner can reassign for the same on-disk file when it re-reads ID3 tags. The
 * old CASCADE on songId would then wipe playlist contents during transient rescans
 * (the bug fixed defensively in d1d94f5 by disabling pruneMissingSongs).
 *
 * After v7:
 * - songs.absolutePath has a UNIQUE INDEX so it can be an FK target
 * - playlist_songs.songAbsolutePath replaces songId as the FK source
 * - loved_songs.songAbsolutePath replaces songId as the FK source
 * - CASCADE now fires only when a file is genuinely deleted (its row in songs goes
 *   away by absolutePath, which is stable across _ID churn)
 *
 * The MediaStore _ID still lives in songs.id — UI/playback resolve content URIs from
 * it via the JOIN at query time, so callers see the CURRENT _ID even after a rescan.
 *
 * Migration order matters:
 *   1. Scrub songs with empty absolutePath (legacy v4 rows that were never re-scanned).
 *      They can't take part in a unique index keyed on absolutePath.
 *   2. De-dupe songs sharing an absolutePath (shouldn't happen, but guard against it).
 *      Keep the row with the highest id (most-recent MediaStore reading); remap any
 *      cross-refs to the kept id before deleting losers.
 *   3. Add the unique index.
 *   4. Recreate playlist_songs with songAbsolutePath FK (SQLite can't ALTER an FK).
 *   5. Recreate loved_songs likewise.
 */
val MIGRATION_6_7 =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Step 1: drop legacy/empty-path rows. These came from pre-v5 installs
            // where absolutePath was a fresh column populated only on next scan;
            // any row with "" is a stale relic that's no longer playable anyway.
            // CASCADE on the old songId FK will clean up their cross-refs.
            db.execSQL("DELETE FROM songs WHERE absolutePath = ''")

            // Step 2: de-dupe by absolutePath. Keep the highest id (latest MediaStore
            // reading), remap cross-refs to the keeper, drop losers. In practice the
            // result set is empty — but a unique index will reject duplicates so we
            // must handle the theoretical case.
            db.execSQL(
                """
                CREATE TEMP TABLE _dedup AS
                SELECT MAX(id) AS keep_id, absolutePath
                FROM songs
                GROUP BY absolutePath
                HAVING COUNT(*) > 1
                """.trimIndent(),
            )
            // Remap playlist_songs to the keeper.
            db.execSQL(
                """
                UPDATE playlist_songs
                SET songId = (
                    SELECT keep_id FROM _dedup
                    WHERE absolutePath = (SELECT absolutePath FROM songs WHERE songs.id = playlist_songs.songId)
                )
                WHERE songId IN (
                    SELECT id FROM songs WHERE absolutePath IN (SELECT absolutePath FROM _dedup)
                ) AND songId NOT IN (SELECT keep_id FROM _dedup)
                """.trimIndent(),
            )
            // Remap loved_songs likewise.
            db.execSQL(
                """
                UPDATE loved_songs
                SET songId = (
                    SELECT keep_id FROM _dedup
                    WHERE absolutePath = (SELECT absolutePath FROM songs WHERE songs.id = loved_songs.songId)
                )
                WHERE songId IN (
                    SELECT id FROM songs WHERE absolutePath IN (SELECT absolutePath FROM _dedup)
                ) AND songId NOT IN (SELECT keep_id FROM _dedup)
                """.trimIndent(),
            )
            // Drop the loser rows.
            db.execSQL(
                """
                DELETE FROM songs WHERE absolutePath IN (SELECT absolutePath FROM _dedup)
                AND id NOT IN (SELECT keep_id FROM _dedup)
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE _dedup")

            // Step 3: unique index on absolutePath so it can be an FK target.
            db.execSQL("CREATE UNIQUE INDEX index_songs_absolutePath ON songs(absolutePath)")

            // Step 4: recreate playlist_songs with songAbsolutePath FK. SQLite can't
            // ALTER an existing FK, so the standard recipe is: create new table, copy
            // data, drop old, rename new.
            db.execSQL(
                """
                CREATE TABLE playlist_songs_new (
                    playlistItemId INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    playlistId INTEGER NOT NULL,
                    songAbsolutePath TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    addedAtMillis INTEGER NOT NULL,
                    originalPosition INTEGER,
                    FOREIGN KEY(playlistId) REFERENCES playlists(id) ON DELETE CASCADE,
                    FOREIGN KEY(songAbsolutePath) REFERENCES songs(absolutePath) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            // Copy by JOIN — anything whose songId no longer resolves to a song
            // (orphan refs from the disabled prune era) is silently dropped.
            db.execSQL(
                """
                INSERT INTO playlist_songs_new
                    (playlistItemId, playlistId, songAbsolutePath, position, addedAtMillis, originalPosition)
                SELECT
                    ps.playlistItemId, ps.playlistId, s.absolutePath, ps.position, ps.addedAtMillis, ps.originalPosition
                FROM playlist_songs ps
                INNER JOIN songs s ON s.id = ps.songId
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE playlist_songs")
            db.execSQL("ALTER TABLE playlist_songs_new RENAME TO playlist_songs")
            db.execSQL("CREATE INDEX index_playlist_songs_playlistId ON playlist_songs(playlistId)")
            db.execSQL("CREATE INDEX index_playlist_songs_songAbsolutePath ON playlist_songs(songAbsolutePath)")

            // Step 5: recreate loved_songs likewise.
            db.execSQL(
                """
                CREATE TABLE loved_songs_new (
                    songAbsolutePath TEXT NOT NULL PRIMARY KEY,
                    addedAtSeconds INTEGER NOT NULL,
                    FOREIGN KEY(songAbsolutePath) REFERENCES songs(absolutePath) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO loved_songs_new (songAbsolutePath, addedAtSeconds)
                SELECT s.absolutePath, l.addedAtSeconds
                FROM loved_songs l
                INNER JOIN songs s ON s.id = l.songId
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE loved_songs")
            db.execSQL("ALTER TABLE loved_songs_new RENAME TO loved_songs")
        }
    }
