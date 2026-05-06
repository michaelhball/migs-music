package com.migsmusic

import com.migsmusic.playback.IncrementalDiff
import com.migsmusic.playback.computeIncrementalDiff
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic tests for the incremental queue diff. Covers every path the audit listed:
 * equal queues / single insert at-various-positions / single removal of current vs other
 * / single move skipping current vs touching current / unrelated multi-diff falls back.
 *
 * No Player, no Room — `computeIncrementalDiff` is a pure function so this is a JVM unit
 * test (fast, no instrumented setup).
 */
class IncrementalDiffTest {
    private val a = "a"
    private val b = "b"
    private val c = "c"
    private val d = "d"
    private val e = "e"

    @Test
    fun emptyPlayerSnapshotForcesFullRebuild() {
        val diff = computeIncrementalDiff(emptyList(), listOf(a, b), currentIndex = 0)
        assertEquals(IncrementalDiff.FullRebuild, diff)
    }

    @Test
    fun identicalQueuesReturnNoOp() {
        val list = listOf(a, b, c)
        val diff = computeIncrementalDiff(list, list, currentIndex = 1)
        assertEquals(IncrementalDiff.NoOp, diff)
    }

    @Test
    fun insertAtEndAfterCurrentReturnsInsert() {
        val diff = computeIncrementalDiff(listOf(a, b), listOf(a, b, c), currentIndex = 0)
        assertEquals(IncrementalDiff.Insert(2), diff)
    }

    @Test
    fun insertInMiddleAfterCurrentReturnsInsert() {
        val diff = computeIncrementalDiff(listOf(a, b, d), listOf(a, b, c, d), currentIndex = 0)
        assertEquals(IncrementalDiff.Insert(2), diff)
    }

    @Test
    fun insertBeforeCurrentForcesFullRebuild() {
        // Inserting at-or-before the current index would shift the playing track.
        val diff = computeIncrementalDiff(listOf(a, b, c), listOf(a, e, b, c), currentIndex = 2)
        assertEquals(IncrementalDiff.FullRebuild, diff)
    }

    @Test
    fun insertAtCurrentForcesFullRebuild() {
        // Inserting AT the current index also shifts the playing track.
        val diff = computeIncrementalDiff(listOf(a, b, c), listOf(a, e, b, c), currentIndex = 1)
        assertEquals(IncrementalDiff.FullRebuild, diff)
    }

    @Test
    fun removalElsewhereReturnsRemove() {
        // Currently playing index 0; removing index 1 is fine.
        val diff = computeIncrementalDiff(listOf(a, b, c), listOf(a, c), currentIndex = 0)
        assertEquals(IncrementalDiff.Remove(1), diff)
    }

    @Test
    fun removalOfCurrentForcesFullRebuild() {
        // Removing the currently-playing item is the case the diff explicitly bails on —
        // requires the full prepare cascade to kick playback to the next track.
        val diff = computeIncrementalDiff(listOf(a, b, c), listOf(a, c), currentIndex = 1)
        assertEquals(IncrementalDiff.FullRebuild, diff)
    }

    @Test
    fun moveSkippingCurrentReturnsMove() {
        // a b c d, current=0, swap b and c → a c b d. firstDiff lands at index 1 (b vs c)
        // so the algorithm interprets it as "b moved from 1 to 2" — same end-state as
        // "c moved from 2 to 1", which the player would handle identically.
        val diff = computeIncrementalDiff(listOf(a, b, c, d), listOf(a, c, b, d), currentIndex = 0)
        assertEquals(IncrementalDiff.Move(from = 1, to = 2), diff)
    }

    @Test
    fun moveFromCurrentForcesFullRebuild() {
        // Moving the currently-playing item to a new position would mid-stream restart it.
        val diff = computeIncrementalDiff(listOf(a, b, c, d), listOf(b, a, c, d), currentIndex = 0)
        assertEquals(IncrementalDiff.FullRebuild, diff)
    }

    @Test
    fun moveToCurrentIndexForcesFullRebuild() {
        // Moving an item TO the current index also disrupts playback.
        val diff = computeIncrementalDiff(listOf(a, b, c, d), listOf(a, c, d, b), currentIndex = 3)
        assertEquals(IncrementalDiff.FullRebuild, diff)
    }

    @Test
    fun unrelatedMultiDiffForcesFullRebuild() {
        // Two unrelated changes (insert + remove) — not a single op, fall back.
        val diff = computeIncrementalDiff(listOf(a, b, c, d), listOf(a, b, e), currentIndex = 0)
        assertEquals(IncrementalDiff.FullRebuild, diff)
    }

    @Test
    fun multiStepMoveBackwardReturnsMove() {
        // a b c d e → d a b c e: d moved from index 3 to index 0. currentIndex=4 (e),
        // not in the affected range, so the move applies cleanly.
        val diff = computeIncrementalDiff(listOf(a, b, c, d, e), listOf(d, a, b, c, e), currentIndex = 4)
        assertEquals(IncrementalDiff.Move(from = 3, to = 0), diff)
    }

    @Test
    fun multiStepMoveForwardReturnsMove() {
        // a b c d e → b c d a e: a moved from index 0 to index 3. currentIndex=4 (e),
        // not in the affected range.
        val diff = computeIncrementalDiff(listOf(a, b, c, d, e), listOf(b, c, d, a, e), currentIndex = 4)
        assertEquals(IncrementalDiff.Move(from = 0, to = 3), diff)
    }

    @Test
    fun multiStepMoveTouchingCurrentForcesFullRebuild() {
        // Same multi-step move shape but currentIndex is inside the affected range —
        // shifting the queue while the current track plays is a full-rebuild scenario.
        val diff = computeIncrementalDiff(listOf(a, b, c, d, e), listOf(d, a, b, c, e), currentIndex = 1)
        assertEquals(IncrementalDiff.FullRebuild, diff)
    }

    @Test
    fun unrelatedDoubleSwapForcesFullRebuild() {
        // Two unrelated swaps: a↔b at one end, d↔e at the other. Not interpretable as
        // a single move; should fall back.
        val diff = computeIncrementalDiff(listOf(a, b, c, d, e), listOf(b, a, c, e, d), currentIndex = 2)
        assertEquals(IncrementalDiff.FullRebuild, diff)
    }

    @Test
    fun insertAtEndWithCurrentNotZeroStillWorks() {
        // current=1, insert at index 3 (after current) is fine.
        val diff = computeIncrementalDiff(listOf(a, b, c), listOf(a, b, c, d), currentIndex = 1)
        assertEquals(IncrementalDiff.Insert(3), diff)
    }

    @Test
    fun removalOfFirstWhenCurrentIsLaterReturnsRemove() {
        // current=2 (item c), removing index 0 (item a) — currentIndex still points to c
        // after removal, just shifted. This IS allowed because the playing item itself
        // doesn't get removed.
        val diff = computeIncrementalDiff(listOf(a, b, c), listOf(b, c), currentIndex = 2)
        assertEquals(IncrementalDiff.Remove(0), diff)
    }
}
