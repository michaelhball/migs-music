package com.migsmusic

import com.migsmusic.data.local.dao.PlaybackSnapshotDao
import com.migsmusic.data.local.entity.PlaybackSnapshotEntity
import com.migsmusic.data.repository.PlaybackSessionRepository
import com.migsmusic.playback.QueueEntry
import com.migsmusic.playback.QueueState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePlaybackSnapshotDao : PlaybackSnapshotDao {
    var stored: PlaybackSnapshotEntity? = null

    override suspend fun getSnapshot(): PlaybackSnapshotEntity? = stored

    override suspend fun upsertSnapshot(snapshot: PlaybackSnapshotEntity) {
        stored = snapshot
    }
}

class PlaybackSessionRepositoryTest {
    @Test
    fun saveAndLoadRoundTripsSongIdsAndPositionAndModes() =
        runBlocking {
            val dao = FakePlaybackSnapshotDao()
            val repo = PlaybackSessionRepository(dao)

            val state =
                QueueState(
                    history = listOf(QueueEntry("h1", 11), QueueEntry("h2", 12)),
                    currentItem = QueueEntry("c1", 13),
                    nextItems = listOf(QueueEntry("n1", 14)),
                    laterItems = listOf(QueueEntry("l1", 15), QueueEntry("l2", 16)),
                    remainingContextItems = listOf(QueueEntry("r1", 17)),
                )

            repo.save(
                queueState = state,
                currentPositionMs = 12345L,
                isPlaying = true,
                repeatMode = 2,
                nextEntrySeed = 99L,
            )

            val restored = repo.load()
            assertNotNull(restored)
            // SongId order is preserved across save/load — the user-visible queue is identical.
            assertEquals(listOf(11L, 12L), restored!!.queueState.history.map { it.songId })
            assertEquals(13L, restored.queueState.currentItem.songId)
            assertEquals(listOf(14L), restored.queueState.nextItems.map { it.songId })
            assertEquals(listOf(15L, 16L), restored.queueState.laterItems.map { it.songId })
            assertEquals(listOf(17L), restored.queueState.remainingContextItems.map { it.songId })
            assertEquals(12345L, restored.currentPositionMs)
            assertEquals(true, restored.isPlaying)
            assertEquals(2, restored.repeatMode)
            // Seed is bumped by total entry count (history 2 + current 1 + next 1 + later 2 + remaining 1 = 7).
            assertEquals(99L + 7, restored.nextEntrySeed)
            // EntryIds are freshly minted from seed, so they're distinct + parseable.
            val allEntries =
                restored.queueState.history +
                    restored.queueState.currentItem +
                    restored.queueState.nextItems +
                    restored.queueState.laterItems +
                    restored.queueState.remainingContextItems
            assertEquals(7, allEntries.map { it.entryId }.toSet().size)
            allEntries.forEach { assertTrue(it.entryId.startsWith("entry-")) }
        }

    @Test
    fun loadReturnsNullWhenNoSnapshotExists() =
        runBlocking {
            val repo = PlaybackSessionRepository(FakePlaybackSnapshotDao())
            assertNull(repo.load())
        }

    @Test
    fun loadReturnsNullWhenCurrentSongIdMissing() =
        runBlocking {
            val dao = FakePlaybackSnapshotDao()
            dao.stored =
                PlaybackSnapshotEntity(
                    currentEntryId = null,
                    currentSongId = null,
                    currentPositionMs = 0,
                    isPlaying = false,
                    repeatMode = 0,
                    historyEntries = "",
                    nextEntries = "",
                    laterEntries = "",
                    remainingEntries = "",
                    nextEntrySeed = 0L,
                )
            assertNull(PlaybackSessionRepository(dao).load())
        }

    @Test
    fun saveWithEmptyQueueStateProducesEmptySerializedFields() =
        runBlocking {
            val dao = FakePlaybackSnapshotDao()
            val repo = PlaybackSessionRepository(dao)
            repo.save(
                queueState = null,
                currentPositionMs = 0,
                isPlaying = false,
                repeatMode = 0,
                nextEntrySeed = 0L,
            )
            val snap = dao.stored
            assertNotNull(snap)
            assertEquals("", snap!!.historyEntries)
            assertEquals("", snap.nextEntries)
            assertEquals("", snap.laterEntries)
            assertEquals("", snap.remainingEntries)
            assertNull(snap.currentSongId)
        }

    @Test
    fun loadIgnoresMalformedSongIdTokens() =
        runBlocking {
            val dao = FakePlaybackSnapshotDao()
            dao.stored =
                PlaybackSnapshotEntity(
                    currentEntryId = null,
                    currentSongId = 13,
                    currentPositionMs = 0,
                    isPlaying = false,
                    repeatMode = 0,
                    // 11 + bad chars + 12 — only the parseable longs come through.
                    historyEntries = "11,corrupt,,not-a-number,12",
                    nextEntries = "",
                    laterEntries = "",
                    remainingEntries = "",
                    nextEntrySeed = 0L,
                )

            val restored = PlaybackSessionRepository(dao).load()
            assertNotNull(restored)
            assertEquals(listOf(11L, 12L), restored!!.queueState.history.map { it.songId })
        }
}
