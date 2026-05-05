package com.migsmusic.playback

data class QueueEntry(
    val entryId: String,
    val songId: Long,
)

data class QueueState(
    val history: List<QueueEntry>,
    val currentItem: QueueEntry,
    val nextItems: List<QueueEntry>,
    val laterItems: List<QueueEntry>,
    val remainingContextItems: List<QueueEntry>,
) {
    val upcoming: List<QueueEntry>
        get() = nextItems + laterItems + remainingContextItems

    val effectiveQueue: List<QueueEntry>
        get() = history + currentItem + upcoming

    fun sanitize(availableSongIds: Set<Long>): QueueState? {
        if (availableSongIds.isEmpty()) return null

        val validHistory = history.filter { it.songId in availableSongIds }
        val validCurrent = currentItem.takeIf { it.songId in availableSongIds }
        val validNext = nextItems.filter { it.songId in availableSongIds }
        val validLater = laterItems.filter { it.songId in availableSongIds }
        val validRemaining = remainingContextItems.filter { it.songId in availableSongIds }

        if (validCurrent != null) {
            return copy(
                history = validHistory,
                currentItem = validCurrent,
                nextItems = validNext,
                laterItems = validLater,
                remainingContextItems = validRemaining,
            )
        }

        validNext.firstOrNull()?.let { promoted ->
            return QueueState(
                history = validHistory,
                currentItem = promoted,
                nextItems = validNext.drop(1),
                laterItems = validLater,
                remainingContextItems = validRemaining,
            )
        }

        validLater.firstOrNull()?.let { promoted ->
            return QueueState(
                history = validHistory,
                currentItem = promoted,
                nextItems = emptyList(),
                laterItems = validLater.drop(1),
                remainingContextItems = validRemaining,
            )
        }

        validRemaining.firstOrNull()?.let { promoted ->
            return QueueState(
                history = validHistory,
                currentItem = promoted,
                nextItems = emptyList(),
                laterItems = emptyList(),
                remainingContextItems = validRemaining.drop(1),
            )
        }

        validHistory.lastOrNull()?.let { promoted ->
            return QueueState(
                history = validHistory.dropLast(1),
                currentItem = promoted,
                nextItems = emptyList(),
                laterItems = emptyList(),
                remainingContextItems = emptyList(),
            )
        }

        return null
    }
}
