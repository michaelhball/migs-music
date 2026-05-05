package com.migsmusic.playback

import kotlin.random.Random

/**
 * Pure in-memory queue model. **Single-threaded contract:** all reads and mutations must
 * happen on `Dispatchers.Main.immediate`. PlaybackManager owns this object and drives every
 * operation via Main.immediate-dispatched coroutines, which guarantees serial access without
 * a lock. Callers from other threads (e.g. an IO continuation) must `withContext(Main.immediate)`
 * before touching this object — otherwise there's no Java memory model happens-before edge
 * between mutations and subsequent reads.
 */
class QueueEngine(
    private var nextEntrySeed: Long = 0,
) {
    private var state: QueueState? = null

    fun currentState(): QueueState? = state

    fun nextEntrySeed(): Long = nextEntrySeed

    fun clear() {
        state = null
    }

    fun restore(
        restoredState: QueueState,
        restoredSeed: Long,
    ) {
        state = restoredState
        nextEntrySeed = restoredSeed
    }

    fun startContext(
        songIds: List<Long>,
        startIndex: Int,
        shuffle: Boolean = false,
    ): QueueState? {
        if (songIds.isEmpty() || startIndex !in songIds.indices) {
            state = null
            return null
        }

        val playbackOrder =
            if (shuffle) {
                buildShuffledOrder(songIds, startIndex)
            } else {
                songIds
            }

        val currentSongId = if (shuffle) songIds[startIndex] else playbackOrder[startIndex]
        val currentPlaybackIndex = playbackOrder.indexOf(currentSongId)
        val entries = playbackOrder.map(::newEntry)

        state =
            QueueState(
                history = entries.take(currentPlaybackIndex),
                currentItem = entries[currentPlaybackIndex],
                nextItems = emptyList(),
                laterItems = emptyList(),
                remainingContextItems = entries.drop(currentPlaybackIndex + 1),
            )
        return state
    }

    fun addNext(songIds: List<Long>): QueueState? {
        val current = state ?: return null
        state =
            current.copy(
                nextItems = current.nextItems + songIds.map(::newEntry),
            )
        return state
    }

    fun addLater(songIds: List<Long>): QueueState? {
        val current = state ?: return null
        state =
            current.copy(
                laterItems = current.laterItems + songIds.map(::newEntry),
            )
        return state
    }

    fun removeUpcoming(entryId: String): QueueState? {
        val current = state ?: return null
        state =
            current.copy(
                nextItems = current.nextItems.filterNot { it.entryId == entryId },
                laterItems = current.laterItems.filterNot { it.entryId == entryId },
                remainingContextItems = current.remainingContextItems.filterNot { it.entryId == entryId },
            )
        return state
    }

    fun moveUpcoming(
        entryId: String,
        newIndex: Int,
    ): QueueState? {
        val current = state ?: return null
        val mutableUpcoming = current.upcoming.toMutableList()
        val currentIndex = mutableUpcoming.indexOfFirst { it.entryId == entryId }
        if (currentIndex == -1) return current

        val item = mutableUpcoming.removeAt(currentIndex)
        val destination = newIndex.coerceIn(0, mutableUpcoming.size)
        mutableUpcoming.add(destination, item)

        state =
            current.copy(
                nextItems = mutableUpcoming,
                laterItems = emptyList(),
                remainingContextItems = emptyList(),
            )
        return state
    }

    fun clearUpcoming(): QueueState? {
        val current = state ?: return null
        state =
            current.copy(
                nextItems = emptyList(),
                laterItems = emptyList(),
                remainingContextItems = emptyList(),
            )
        return state
    }

    /**
     * Re-shuffles only the natural-order tail (`remainingContextItems`). Preserves the
     * manually-queued Play-Next / Play-Later blocks and the current item.
     */
    fun shuffleRemainingContext(): QueueState? {
        val current = state ?: return null
        if (current.remainingContextItems.size < 2) return current
        state = current.copy(remainingContextItems = current.remainingContextItems.shuffled())
        return state
    }

    fun skipToNext(): QueueState? {
        val current = state ?: return null
        val nextItem =
            current.nextItems.firstOrNull()
                ?: current.laterItems.firstOrNull()
                ?: current.remainingContextItems.firstOrNull()
                ?: return current

        val nextItems = current.nextItems.dropWhile { it.entryId == nextItem.entryId }
        val laterItems =
            if (current.nextItems.isEmpty()) {
                current.laterItems.dropWhile { it.entryId == nextItem.entryId }
            } else {
                current.laterItems
            }
        val remainingItems =
            if (current.nextItems.isEmpty() && current.laterItems.isEmpty()) {
                current.remainingContextItems.dropWhile { it.entryId == nextItem.entryId }
            } else {
                current.remainingContextItems
            }

        state =
            QueueState(
                history = current.history + current.currentItem,
                currentItem = nextItem,
                nextItems = nextItems,
                laterItems = laterItems,
                remainingContextItems = remainingItems,
            )
        return state
    }

    fun skipToPrevious(): QueueState? {
        val current = state ?: return null
        val previousItem = current.history.lastOrNull() ?: return current
        val newUpcoming =
            buildList {
                add(current.currentItem)
                addAll(current.nextItems)
                addAll(current.laterItems)
                addAll(current.remainingContextItems)
            }
        state =
            QueueState(
                history = current.history.dropLast(1),
                currentItem = previousItem,
                nextItems = emptyList(),
                laterItems = emptyList(),
                remainingContextItems = newUpcoming,
            )
        return state
    }

    fun moveToEntry(entryId: String): QueueState? {
        val current = state ?: return null
        if (current.currentItem.entryId == entryId) return current

        val effective = current.effectiveQueue
        val index = effective.indexOfFirst { it.entryId == entryId }
        if (index == -1) return current

        state =
            QueueState(
                history = effective.take(index),
                currentItem = effective[index],
                nextItems = emptyList(),
                laterItems = emptyList(),
                remainingContextItems = effective.drop(index + 1),
            )
        return state
    }

    private fun newEntry(songId: Long): QueueEntry {
        nextEntrySeed += 1
        return QueueEntry(entryId = "entry-$nextEntrySeed", songId = songId)
    }

    private fun buildShuffledOrder(
        songIds: List<Long>,
        startIndex: Int,
    ): List<Long> {
        val anchor = songIds[startIndex]
        val rest = songIds.toMutableList().also { it.removeAt(startIndex) }
        rest.shuffle(Random(anchor))
        return listOf(anchor) + rest
    }
}
