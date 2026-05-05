package com.migsmusic.data.repository

import com.migsmusic.data.local.dao.PlaybackSnapshotDao
import com.migsmusic.data.local.entity.PlaybackSnapshotEntity
import com.migsmusic.playback.QueueEntry
import com.migsmusic.playback.QueueState

/**
 * Persists the playback queue + position so it survives cold starts.
 *
 * Storage format: each section's `String` column is a comma-separated list of song IDs.
 * (Longs only — no separators in their text repr — so no escaping needed.) On load, fresh
 * `entryId`s are minted from the resumed seed; the user-visible behaviour is identical, and
 * we don't have to defend against a runtime-mutable separator scheme.
 */
class PlaybackSessionRepository(
    private val playbackSnapshotDao: PlaybackSnapshotDao,
) {
    suspend fun save(
        queueState: QueueState?,
        currentPositionMs: Long,
        isPlaying: Boolean,
        repeatMode: Int,
        nextEntrySeed: Long,
    ) {
        val snapshot = PlaybackSnapshotEntity(
            currentEntryId = queueState?.currentItem?.entryId,
            currentSongId = queueState?.currentItem?.songId,
            currentPositionMs = currentPositionMs,
            isPlaying = isPlaying,
            repeatMode = repeatMode,
            historyEntries = serializeIds(queueState?.history.orEmpty()),
            nextEntries = serializeIds(queueState?.nextItems.orEmpty()),
            laterEntries = serializeIds(queueState?.laterItems.orEmpty()),
            remainingEntries = serializeIds(queueState?.remainingContextItems.orEmpty()),
            nextEntrySeed = nextEntrySeed,
        )
        playbackSnapshotDao.upsertSnapshot(snapshot)
    }

    suspend fun load(): RestoredPlaybackSession? {
        val snapshot = playbackSnapshotDao.getSnapshot() ?: return null
        val currentSongId = snapshot.currentSongId ?: return null

        // Mint fresh entryIds from the saved seed forward — entryIds are session-local
        // identifiers, regenerable from (seed, songId) and don't need to round-trip through DB.
        var seed = snapshot.nextEntrySeed
        fun mint(songId: Long): QueueEntry {
            seed += 1
            return QueueEntry(entryId = "entry-$seed", songId = songId)
        }

        val history = parseIds(snapshot.historyEntries).map(::mint)
        val current = mint(currentSongId)
        val nextItems = parseIds(snapshot.nextEntries).map(::mint)
        val laterItems = parseIds(snapshot.laterEntries).map(::mint)
        val remaining = parseIds(snapshot.remainingEntries).map(::mint)

        return RestoredPlaybackSession(
            queueState = QueueState(
                history = history,
                currentItem = current,
                nextItems = nextItems,
                laterItems = laterItems,
                remainingContextItems = remaining,
            ),
            currentPositionMs = snapshot.currentPositionMs,
            isPlaying = snapshot.isPlaying,
            repeatMode = snapshot.repeatMode,
            nextEntrySeed = seed,
        )
    }

    private fun serializeIds(entries: List<QueueEntry>): String =
        entries.joinToString(separator = ",") { it.songId.toString() }

    private fun parseIds(serialized: String): List<Long> {
        if (serialized.isBlank()) return emptyList()
        return serialized.split(',').mapNotNull { it.toLongOrNull() }
    }
}

data class RestoredPlaybackSession(
    val queueState: QueueState,
    val currentPositionMs: Long,
    val isPlaying: Boolean,
    val repeatMode: Int,
    val nextEntrySeed: Long,
)
