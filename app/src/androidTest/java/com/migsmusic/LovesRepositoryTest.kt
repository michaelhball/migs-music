package com.migsmusic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.migsmusic.data.local.AppDatabase
import com.migsmusic.data.local.entity.SongEntity
import com.migsmusic.data.repository.LovesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the Loves feature end-to-end at the data layer:
 * - love / unlove toggling
 * - reactive observeIsLoved
 * - observeAll ordering (most-recent first)
 * - cascade delete: removing a song from `songs` drops its heart row
 *
 * Uses a real Room in-memory DB (no mocks) so the schema, FK CASCADE, and SQL
 * are actually exercised — same fidelity bar as the rest of our DAO tests.
 */
@RunWith(AndroidJUnit4::class)
class LovesRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: LovesRepository
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repo = LovesRepository(db.lovedSongDao())
        // Seed a few songs so love/unlove has real FK targets.
        runBlocking {
            db.songDao().upsertAll(
                listOf(
                    fakeSong(1L),
                    fakeSong(2L),
                    fakeSong(3L),
                ),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun freshDatabaseHasNoLoves() =
        runBlocking {
            assertEquals(0, repo.observeCount().first())
            assertTrue(repo.observeAll().first().isEmpty())
            assertFalse(repo.observeIsLoved(1L).first())
        }

    @Test
    fun loveSetsTheFlagAndUnloveClearsIt() =
        runBlocking {
            repo.love(1L)
            assertTrue(repo.observeIsLoved(1L).first())
            repo.unlove(1L)
            assertFalse(repo.observeIsLoved(1L).first())
        }

    @Test
    fun loveIsIdempotent() =
        runBlocking {
            // Insert with onConflict=IGNORE — calling love() twice should not double-count
            // and should not throw on the FK constraint.
            repo.love(1L)
            repo.love(1L)
            assertEquals(1, repo.observeCount().first())
        }

    @Test
    fun unloveOnNotLovedIsNoOp() =
        runBlocking {
            // No row to delete; should silently succeed and leave count at 0.
            repo.unlove(1L)
            assertEquals(0, repo.observeCount().first())
        }

    @Test
    fun observeAllReturnsMostRecentFirst() =
        runBlocking {
            // Manually space out the addedAtSeconds via the DAO so we know the order
            // independent of clock granularity in the repository's love() helper.
            db.lovedSongDao().insert(loved(1L, addedAt = 100L))
            db.lovedSongDao().insert(loved(2L, addedAt = 300L))
            db.lovedSongDao().insert(loved(3L, addedAt = 200L))
            val ordered = repo.observeAll().first().map { it.id }
            assertEquals(listOf(2L, 3L, 1L), ordered)
        }

    @Test
    fun cascadeDeleteRemovesHeartWhenSongIsDeleted() =
        runBlocking {
            repo.love(1L)
            repo.love(2L)
            assertEquals(2, repo.observeCount().first())

            // Drop song 1 from the library — its heart row should follow via FK CASCADE.
            // This is the path AutoImportService takes when a song becomes orphan and gets
            // deleted from `songs`; we never want stale love rows pointing at nothing.
            db.songDao().deleteByIds(listOf(1L))
            assertEquals(1, repo.observeCount().first())
            assertFalse(repo.observeIsLoved(1L).first())
            assertTrue(repo.observeIsLoved(2L).first())
        }

    @Test
    fun observeIsLovedIsReactive() =
        runBlocking {
            // Subscribe before mutating — first emission is "false", then after love() flips
            // to "true". Confirms the Flow really observes the table, isn't a snapshot.
            val flow = repo.observeIsLoved(1L)
            assertFalse(flow.first())
            repo.love(1L)
            assertTrue(flow.first())
            repo.unlove(1L)
            assertFalse(flow.first())
        }

    private fun loved(
        songId: Long,
        addedAt: Long,
    ) = com.migsmusic.data.local.entity.LovedSongEntity(songId, addedAt)

    private fun fakeSong(id: Long) =
        SongEntity(
            id = id,
            contentUri = "content://media/external/audio/media/$id",
            albumId = null,
            title = "Song $id",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000L,
            trackNumber = 1,
            discNumber = 1,
            folderPath = "Music",
            folderName = "Music",
            albumArtUri = null,
            dateAddedSeconds = 0L,
            dateModifiedSeconds = 0L,
            absolutePath = "/storage/emulated/0/Music/song$id.mp3",
        )
}
