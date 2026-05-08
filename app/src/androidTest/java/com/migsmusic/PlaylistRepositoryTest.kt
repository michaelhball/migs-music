package com.migsmusic

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.migsmusic.data.local.AppDatabase
import com.migsmusic.data.local.entity.SongEntity
import com.migsmusic.data.repository.PlaylistRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Direct-Room tests for [PlaylistRepository.upsertSyncedPlaylist] — the heart of the Mac
 * sync feature. Verifies the replace-vs-create branching, that manual playlists with the
 * same name are never touched, and that re-sync resets the order back to the manifest's.
 *
 * Uses an in-memory Room DB so the test is hermetic and parallel-safe — no shared state
 * with the real app's library / playlists.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: PlaylistRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries() // safe in tests; keeps the assertions linear
                .build()
        repository = PlaylistRepository(db.playlistDao(), db.songDao())
        // Seed the songs table so the FK on playlist_songs.songId can be satisfied.
        runBlocking {
            db.songDao().upsertAll(
                listOf(
                    fakeSong(1L, title = "One"),
                    fakeSong(2L, title = "Two"),
                    fakeSong(3L, title = "Three"),
                    fakeSong(4L, title = "Four"),
                ),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun upsertSyncedPlaylist_createsRowWhenNoneExists() =
        runBlocking {
            val id = repository.upsertSyncedPlaylist("Workout", listOf(1L, 2L, 3L))

            val songs = repository.observePlaylistSongs(id).first()
            assertEquals(listOf(1L, 2L, 3L), songs.map { it.songId })
            // The created row should be marked synced.
            val syncedNames = repository.getSyncedPlaylists().map { it.name }
            assertEquals(listOf("Workout"), syncedNames)
        }

    @Test
    fun upsertSyncedPlaylist_replacesContentsOfExistingSyncedPlaylist() =
        runBlocking {
            val firstId = repository.upsertSyncedPlaylist("Workout", listOf(1L, 2L, 3L))
            val secondId = repository.upsertSyncedPlaylist("Workout", listOf(4L, 1L))

            // Same row id — we replaced contents in-place, didn't create a new row.
            assertEquals(firstId, secondId)
            val songs = repository.observePlaylistSongs(secondId).first()
            assertEquals(listOf(4L, 1L), songs.map { it.songId })
        }

    @Test
    fun upsertSyncedPlaylist_doesNotTouchManualPlaylistWithSameName() =
        runBlocking {
            // Manual playlist created by the user via the in-app + button (syncedFromMac=false
            // by default).
            val manualId = repository.createPlaylist("Workout")
            repository.addSong(manualId, 1L)
            repository.addSong(manualId, 2L)

            // Mac sync arrives with a Workout.m3u — should land in a NEW synced row, not
            // merge into the manual one.
            val syncedId = repository.upsertSyncedPlaylist("Workout", listOf(3L, 4L))

            assertNotEquals(manualId, syncedId)

            val manualSongs = repository.observePlaylistSongs(manualId).first().map { it.songId }
            val syncedSongs = repository.observePlaylistSongs(syncedId).first().map { it.songId }
            assertEquals(listOf(1L, 2L), manualSongs)
            assertEquals(listOf(3L, 4L), syncedSongs)
        }

    @Test
    fun upsertSyncedPlaylist_resetsToManifestOrderAfterUserManualReorder() =
        runBlocking {
            val id = repository.upsertSyncedPlaylist("Workout", listOf(1L, 2L, 3L))

            // User drags song 1 to the bottom on the phone.
            repository.moveSong(id, fromIndex = 0, toIndex = 2)
            val afterManualMove = repository.observePlaylistSongs(id).first().map { it.songId }
            assertEquals(listOf(2L, 3L, 1L), afterManualMove)

            // Mac sync re-runs with the original order — replace wins.
            repository.upsertSyncedPlaylist("Workout", listOf(1L, 2L, 3L))
            val afterResync = repository.observePlaylistSongs(id).first().map { it.songId }
            assertEquals(listOf(1L, 2L, 3L), afterResync)
        }

    @Test
    fun upsertSyncedPlaylist_emptyIdsListClearsExistingButKeepsRow() =
        runBlocking {
            val id = repository.upsertSyncedPlaylist("Workout", listOf(1L, 2L))

            val emptyId = repository.upsertSyncedPlaylist("Workout", emptyList())
            assertEquals(id, emptyId)
            val songs = repository.observePlaylistSongs(id).first()
            assertTrue(songs.isEmpty())
        }

    @Test
    fun deletePlaylist_cascadesPlaylistSongsRows() =
        runBlocking {
            val id = repository.upsertSyncedPlaylist("Workout", listOf(1L, 2L, 3L))
            repository.deletePlaylist(id)

            // The synced playlist list shouldn't include it anymore.
            assertTrue(repository.getSyncedPlaylists().none { it.id == id })
            // And its playlist_songs rows should be CASCADE-deleted (zero songIds remain).
            assertEquals(emptyList<Long>(), repository.getPlaylistSongIds(id).toList())
        }

    @Test
    fun getOrphanSongIds_returnsOnlySongsExclusiveToRemovedPlaylists() =
        runBlocking {
            val a = repository.upsertSyncedPlaylist("A", listOf(1L, 2L))
            val b = repository.upsertSyncedPlaylist("B", listOf(2L, 3L))

            // Removing playlist A: song 1 is orphaned (only A had it), song 2 isn't (B still
            // has it).
            val orphans = repository.getOrphanSongIds(listOf(a))
            assertEquals(setOf(1L), orphans.toSet())

            // Removing both A and B: songs 1, 2, 3 all orphaned.
            assertEquals(setOf(1L, 2L, 3L), repository.getOrphanSongIds(listOf(a, b)).toSet())
        }

    @Test
    fun getOrphanSongIds_emptyInputReturnsEmpty() =
        runBlocking {
            assertEquals(emptyList<Long>(), repository.getOrphanSongIds(emptyList()))
        }

    @Test
    fun findSyncedPlaylistByName_returnsNullWhenOnlyManualWithThatName() =
        runBlocking {
            repository.createPlaylist("OnlyManual")
            // No DAO method exposed via the repo — read via getSyncedPlaylists indirectly.
            val syncedNames = repository.getSyncedPlaylists().map { it.name }
            assertFalse("OnlyManual" in syncedNames)
            // Upsert with the same name creates a separate synced row (covered by the
            // dedicated test above); here we just confirm there's no synced match.
            assertNull(db.playlistDao().findSyncedPlaylistByName("OnlyManual"))
        }

    private fun fakeSong(
        id: Long,
        title: String,
    ) = SongEntity(
        id = id,
        contentUri = "content://media/external/audio/media/$id",
        albumId = null,
        title = title,
        artist = "Artist $id",
        album = "Album $id",
        durationMs = 180_000L,
        trackNumber = 1,
        discNumber = 1,
        folderPath = "Music/Album $id",
        folderName = "Album $id",
        albumArtUri = null,
        dateAddedSeconds = 0L,
        dateModifiedSeconds = 0L,
        absolutePath = "/storage/emulated/0/Music/Album $id/$title.mp3",
    )
}
