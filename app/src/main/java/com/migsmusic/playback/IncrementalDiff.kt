package com.migsmusic.playback

/**
 * Pure-logic description of how to update an ExoPlayer media list to match a new queue,
 * without touching the player itself. Computed by [computeIncrementalDiff]; consumed by
 * [PlaybackManager.tryIncrementalSync] which translates it into player API calls.
 *
 * The diff is intentionally narrow: only handles single insert / single removal / single
 * move that *don't touch the currently-playing item*. Anything else falls through to a
 * full rebuild via `ExoPlayer.setMediaItems` — the safer fallback.
 *
 * Why bother: queue mutations from drag-to-reorder, "play next", "remove from queue" all
 * arrive frequently. A full rebuild stops + re-prepares the player and re-seeks, causing
 * audible glitches on the currently-playing track. Incremental ops avoid that for the
 * common case.
 */
sealed interface IncrementalDiff {
    /** No change between current and new queue — caller can short-circuit, no player call needed. */
    data object NoOp : IncrementalDiff

    /** Insert the new item at [index]. */
    data class Insert(val index: Int) : IncrementalDiff

    /** Remove the item at [index]. */
    data class Remove(val index: Int) : IncrementalDiff

    /** Move an item from [from] to [to]. */
    data class Move(val from: Int, val to: Int) : IncrementalDiff

    /** Diff is more complex than a single mutation; caller should fall back to full rebuild. */
    data object FullRebuild : IncrementalDiff
}

/**
 * Computes the [IncrementalDiff] between the player's current entryId list and the desired
 * one. Returns [IncrementalDiff.FullRebuild] if the diff is anything other than:
 *
 * - identical lists ([IncrementalDiff.NoOp]),
 * - exactly one insertion at an index strictly above [currentIndex],
 * - exactly one removal at an index that's not [currentIndex],
 * - exactly one move where neither the source nor the destination is [currentIndex].
 *
 * Pure function — no Player, no IO, no side effects. Safe to test as a JVM unit.
 */
fun computeIncrementalDiff(
    playerSnapshot: List<String>,
    newIds: List<String>,
    currentIndex: Int,
): IncrementalDiff {
    if (playerSnapshot.isEmpty()) return IncrementalDiff.FullRebuild
    if (playerSnapshot == newIds) return IncrementalDiff.NoOp

    // Single insertion: newIds is playerSnapshot with one extra entry inserted at index `at`.
    if (newIds.size == playerSnapshot.size + 1) {
        val at =
            (newIds.indices).firstOrNull { i ->
                i >= playerSnapshot.size || playerSnapshot[i] != newIds[i]
            } ?: return IncrementalDiff.FullRebuild
        if (newIds.drop(at + 1) != playerSnapshot.drop(at)) return IncrementalDiff.FullRebuild
        // Don't insert at-or-before the current index — would shift the currently-playing item.
        if (at <= currentIndex) return IncrementalDiff.FullRebuild
        return IncrementalDiff.Insert(at)
    }

    // Single removal: playerSnapshot is newIds with one extra entry at index `at`.
    if (newIds.size == playerSnapshot.size - 1) {
        val at =
            (playerSnapshot.indices).firstOrNull { i ->
                i >= newIds.size || playerSnapshot[i] != newIds[i]
            } ?: return IncrementalDiff.FullRebuild
        if (playerSnapshot.drop(at + 1) != newIds.drop(at)) return IncrementalDiff.FullRebuild
        // Removing the currently-playing item requires the full prepare cascade.
        if (at == currentIndex) return IncrementalDiff.FullRebuild
        return IncrementalDiff.Remove(at)
    }

    // Single move: same size, one entry slid from `from` to `to`. Handles both directions
    // (forward shifts and backward shifts), including multi-position moves like 3→7 in a
    // 10-item queue. Try both interpretations of the disagreement range; if neither
    // reconstructs newIds, fall back to FullRebuild.
    if (newIds.size == playerSnapshot.size) {
        val firstDiff =
            newIds.indices.firstOrNull { playerSnapshot[it] != newIds[it] }
                ?: return IncrementalDiff.NoOp
        val lastDiff = newIds.indices.reversed().first { playerSnapshot[it] != newIds[it] }

        // Interpretation 1: the item at playerSnapshot[firstDiff] moved forward to lastDiff,
        // which shifts everything in between back by one.
        val movedForward = playerSnapshot[firstDiff]
        val rebuiltForward =
            playerSnapshot.toMutableList().apply {
                removeAt(firstDiff)
                add(lastDiff, movedForward)
            }
        if (rebuiltForward == newIds) {
            // The shift range is [firstDiff..lastDiff] inclusive. If currentIndex falls
            // anywhere inside, the playing track moves position — full rebuild is safer.
            if (currentIndex in firstDiff..lastDiff) return IncrementalDiff.FullRebuild
            return IncrementalDiff.Move(from = firstDiff, to = lastDiff)
        }

        // Interpretation 2: the item at playerSnapshot[lastDiff] moved backward to firstDiff,
        // shifting everything in between forward by one.
        val movedBackward = playerSnapshot[lastDiff]
        val rebuiltBackward =
            playerSnapshot.toMutableList().apply {
                removeAt(lastDiff)
                add(firstDiff, movedBackward)
            }
        if (rebuiltBackward == newIds) {
            if (currentIndex in firstDiff..lastDiff) return IncrementalDiff.FullRebuild
            return IncrementalDiff.Move(from = lastDiff, to = firstDiff)
        }

        return IncrementalDiff.FullRebuild
    }

    return IncrementalDiff.FullRebuild
}
