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

    // Single move: same size, one entry slid from `from` to `to`.
    if (newIds.size == playerSnapshot.size) {
        val firstDiff =
            newIds.indices.firstOrNull { playerSnapshot[it] != newIds[it] }
                ?: return IncrementalDiff.NoOp
        val movedEntryId = playerSnapshot[firstDiff]
        val newPos = newIds.indexOf(movedEntryId)
        if (newPos == -1) return IncrementalDiff.FullRebuild
        val rebuilt =
            playerSnapshot.toMutableList().apply {
                removeAt(firstDiff)
                add(newPos, movedEntryId)
            }
        if (rebuilt != newIds) return IncrementalDiff.FullRebuild
        if (firstDiff == currentIndex || newPos == currentIndex) return IncrementalDiff.FullRebuild
        return IncrementalDiff.Move(from = firstDiff, to = newPos)
    }

    return IncrementalDiff.FullRebuild
}
