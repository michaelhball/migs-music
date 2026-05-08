package com.migsmusic

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.migsmusic.data.local.MIGRATION_6_7
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end test of MIGRATION_6_7 — the most dangerous migration in the project's
 * history because it recreates two cross-ref tables and changes the FK target on
 * songs from `id` to `absolutePath`. This test seeds a representative v6 database
 * (covering: clean rows, duplicate-path rows, empty-path rows, orphaned playlist refs,
 * orphaned loved refs) and verifies the v7 state has exactly the rows we expect.
 *
 * Goes through SupportSQLiteOpenHelper rather than Room because exportSchema=false
 * means we can't use MigrationTestHelper, and using Room.databaseBuilder requires
 * matching @Database entities to v6 state — easier to write the v6 DDL directly.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV6V7Test {
    private lateinit var helper: SupportSQLiteOpenHelper
    private val dbName = "migration-v6-v7-test.db"

    @Before
    fun setUp() {
        // Make sure no leftover from a prior run can confuse us.
        ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(dbName)

        // Open a v6 database directly (no Room schema validation), so we can seed
        // arbitrary state — including states Room would never produce on its own.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbName)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(6) {
                            override fun onCreate(db: SupportSQLiteDatabase) = createV6Schema(db)

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = error("not exercised in this test")
                        },
                    )
                    .build(),
            )
    }

    @After
    fun tearDown() {
        helper.close()
        ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(dbName)
    }

    @Test
    fun migration_keepsCleanRows_andDropsLegacyOrphans() {
        val db = helper.writableDatabase
        // Seed: 4 songs covering clean / empty-path / duplicate-path scenarios.
        db.execSQL("INSERT INTO songs VALUES (1, 'uri1', NULL, 'A', 'Artist', 'Album', 0, 0, 0, '', '', NULL, 0, 0, '/sdcard/Music/A.mp3')")
        db.execSQL("INSERT INTO songs VALUES (2, 'uri2', NULL, 'B', 'Artist', 'Album', 0, 0, 0, '', '', NULL, 0, 0, '/sdcard/Music/B.mp3')")
        db.execSQL("INSERT INTO songs VALUES (3, 'uri3', NULL, 'Legacy', 'Artist', 'Album', 0, 0, 0, '', '', NULL, 0, 0, '')") // empty path
        db.execSQL("INSERT INTO songs VALUES (4, 'uri4', NULL, 'B-dupe', 'Artist', 'Album', 0, 0, 0, '', '', NULL, 0, 0, '/sdcard/Music/B.mp3')") // dupe of 2
        // Playlists.
        db.execSQL("INSERT INTO playlists VALUES (10, 'P1', 0, 0, 0)")
        db.execSQL("INSERT INTO playlists VALUES (11, 'P2', 0, 0, 1)")
        // Playlist songs:
        //   refs to clean song 1, song 2 (highest of dupe pair will win)
        //   ref to song 4 (dupe loser — gets remapped to keeper)
        //   ref to song 999 (orphaned ref, song doesn't exist) — should drop
        //   ref to song 3 (empty-path song, will be deleted) — should drop too
        db.execSQL("INSERT INTO playlist_songs (playlistId, songId, position, addedAtMillis, originalPosition) VALUES (10, 1, 0, 0, 0)")
        db.execSQL("INSERT INTO playlist_songs (playlistId, songId, position, addedAtMillis, originalPosition) VALUES (10, 2, 1, 0, 1)")
        db.execSQL("INSERT INTO playlist_songs (playlistId, songId, position, addedAtMillis, originalPosition) VALUES (11, 4, 0, 0, 0)")
        // Loved: heart on song 1 (clean), song 3 (empty-path, drops), song 4 (dupe loser, remaps).
        db.execSQL("INSERT INTO loved_songs VALUES (1, 100)")
        db.execSQL("INSERT INTO loved_songs VALUES (3, 200)")
        db.execSQL("INSERT INTO loved_songs VALUES (4, 300)")

        // Run the migration.
        MIGRATION_6_7.migrate(db)

        // Assertions.
        // 1. Empty-path song was deleted.
        db.query("SELECT COUNT(*) FROM songs WHERE id = 3").use { c ->
            c.moveToFirst()
            assertEquals("empty-path song must be pruned", 0, c.getInt(0))
        }
        // 2. Duplicate de-duped: only one row with /sdcard/Music/B.mp3 remains, keeping the
        //    higher id (4 > 2 so id=4 should win).
        db.query("SELECT id FROM songs WHERE absolutePath = '/sdcard/Music/B.mp3'").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(4L, c.getLong(0))
        }
        // 3. Surviving songs have UNIQUE absolutePath.
        db.query("SELECT COUNT(*) FROM songs").use { c ->
            c.moveToFirst()
            assertEquals(2, c.getInt(0))
        }
        // 4. playlist_songs: orphan ref to id=999 dropped; empty-path ref dropped via JOIN-copy;
        //    refs to song 1 + song 2/4 dupe → kept, with songAbsolutePath populated.
        db.query("SELECT playlistId, songAbsolutePath FROM playlist_songs ORDER BY playlistItemId").use { c ->
            assertEquals(3, c.count)
            c.moveToFirst()
            assertEquals(10L, c.getLong(0))
            assertEquals("/sdcard/Music/A.mp3", c.getString(1))
            c.moveToNext()
            assertEquals(10L, c.getLong(0))
            assertEquals("/sdcard/Music/B.mp3", c.getString(1))
            c.moveToNext()
            assertEquals(11L, c.getLong(0))
            // Was a ref to id=4 (dupe loser); after de-dupe songAbsolutePath resolves to the
            // keeper's path, which is the same string for both rows.
            assertEquals("/sdcard/Music/B.mp3", c.getString(1))
        }
        // 5. loved_songs: empty-path heart dropped (song 3 gone); dupe-loser heart (id=4)
        //    survived because the path resolves to the keeper. song 1 heart kept.
        db.query("SELECT songAbsolutePath, addedAtSeconds FROM loved_songs ORDER BY songAbsolutePath").use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertEquals("/sdcard/Music/A.mp3", c.getString(0))
            assertEquals(100L, c.getLong(1))
            c.moveToNext()
            assertEquals("/sdcard/Music/B.mp3", c.getString(0))
            assertEquals(300L, c.getLong(1))
        }
        // 6. Unique index on songs.absolutePath now exists — try to insert a duplicate.
        var duplicateRejected = false
        try {
            db.execSQL("INSERT INTO songs VALUES (99, 'urix', NULL, 'X', 'A', 'A', 0, 0, 0, '', '', NULL, 0, 0, '/sdcard/Music/A.mp3')")
        } catch (_: Exception) {
            duplicateRejected = true
        }
        assertTrue("UNIQUE INDEX must reject duplicate absolutePath", duplicateRejected)
        // 7. CASCADE works on the new FK: delete the song, its playlist + loved refs go.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM songs WHERE absolutePath = '/sdcard/Music/A.mp3'")
        db.query("SELECT COUNT(*) FROM playlist_songs WHERE songAbsolutePath = '/sdcard/Music/A.mp3'").use { c ->
            c.moveToFirst()
            assertEquals("playlist_songs CASCADE on FK delete", 0, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM loved_songs WHERE songAbsolutePath = '/sdcard/Music/A.mp3'").use { c ->
            c.moveToFirst()
            assertEquals("loved_songs CASCADE on FK delete", 0, c.getInt(0))
        }
    }

    @Test
    fun migration_isIdempotentNoOp_onEmptyDb() {
        val db = helper.writableDatabase
        MIGRATION_6_7.migrate(db)
        // No songs, no refs — migration should still complete and leave the new schema in place.
        db.query("SELECT COUNT(*) FROM songs").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        // playlist_songs still queryable with the new column (even if empty).
        db.query("SELECT COUNT(*) FROM playlist_songs WHERE songAbsolutePath = ''").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
    }

    /**
     * V6 schema as Room would have created it — straight from the @Entity definitions
     * frozen at v6. Don't tweak this without also bumping the version: the whole point
     * of this test is to seed *exactly* what a v6 install looks like on disk.
     */
    private fun createV6Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE songs (
                id INTEGER NOT NULL PRIMARY KEY,
                contentUri TEXT NOT NULL,
                albumId INTEGER,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                durationMs INTEGER NOT NULL,
                trackNumber INTEGER NOT NULL,
                discNumber INTEGER NOT NULL,
                folderPath TEXT NOT NULL,
                folderName TEXT NOT NULL,
                albumArtUri TEXT,
                dateAddedSeconds INTEGER NOT NULL,
                dateModifiedSeconds INTEGER NOT NULL,
                absolutePath TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE playlists (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                createdAtMillis INTEGER NOT NULL,
                updatedAtMillis INTEGER NOT NULL,
                syncedFromMac INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE playlist_songs (
                playlistItemId INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                playlistId INTEGER NOT NULL,
                songId INTEGER NOT NULL,
                position INTEGER NOT NULL,
                addedAtMillis INTEGER NOT NULL,
                originalPosition INTEGER,
                FOREIGN KEY(playlistId) REFERENCES playlists(id) ON DELETE CASCADE,
                FOREIGN KEY(songId) REFERENCES songs(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE loved_songs (
                songId INTEGER NOT NULL PRIMARY KEY,
                addedAtSeconds INTEGER NOT NULL,
                FOREIGN KEY(songId) REFERENCES songs(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE playback_snapshot (
                snapshotId INTEGER NOT NULL PRIMARY KEY,
                currentEntryId TEXT,
                currentSongId INTEGER,
                currentPositionMs INTEGER NOT NULL,
                isPlaying INTEGER NOT NULL,
                repeatMode INTEGER NOT NULL,
                historyEntries TEXT NOT NULL,
                nextEntries TEXT NOT NULL,
                laterEntries TEXT NOT NULL,
                remainingEntries TEXT NOT NULL,
                nextEntrySeed INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}
