package com.migsmusic

import com.migsmusic.playback.QueueEngine
import com.migsmusic.playback.QueueEntry
import com.migsmusic.playback.QueueState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QueueEngineTest {
    @Test
    fun playNextAndPlayLaterComeBeforeRemainingContext() {
        val engine = QueueEngine()
        val initial = engine.startContext(songIds = listOf(1, 2, 3, 4, 5), startIndex = 1)
        assertNotNull(initial)

        engine.addNext(listOf(10))
        engine.addLater(listOf(20))
        val updated = engine.addNext(listOf(30))

        assertEquals(
            listOf(10L, 30L, 20L, 3L, 4L, 5L),
            updated?.upcoming?.map { it.songId }
        )
    }

    @Test
    fun skippingAdvancesThroughNextThenLaterThenContext() {
        val engine = QueueEngine()
        engine.startContext(songIds = listOf(1, 2, 3, 4), startIndex = 0)
        engine.addNext(listOf(10, 11))
        engine.addLater(listOf(20))

        val firstSkip = engine.skipToNext()
        assertEquals(10L, firstSkip?.currentItem?.songId)

        val secondSkip = engine.skipToNext()
        assertEquals(11L, secondSkip?.currentItem?.songId)

        val thirdSkip = engine.skipToNext()
        assertEquals(20L, thirdSkip?.currentItem?.songId)

        val fourthSkip = engine.skipToNext()
        assertEquals(2L, fourthSkip?.currentItem?.songId)
    }

    @Test
    fun movingUpcomingFlattensManualPriorityIntoVisibleOrder() {
        val engine = QueueEngine()
        engine.startContext(songIds = listOf(1, 2, 3, 4), startIndex = 0)
        engine.addNext(listOf(10))
        engine.addLater(listOf(20))
        val state = engine.moveUpcoming(entryId = "entry-5", newIndex = 2)

        assertEquals(
            listOf(20L, 2L, 10L, 3L, 4L),
            state?.upcoming?.map { it.songId }
        )
    }

    @Test
    fun movingUpcomingToFrontUpdatesVisibleOrder() {
        val engine = QueueEngine()
        engine.startContext(songIds = listOf(1, 2, 3, 4), startIndex = 0)
        engine.addNext(listOf(10))
        engine.addLater(listOf(20))

        val state = engine.moveUpcoming(entryId = "entry-6", newIndex = 0)

        assertEquals(
            listOf(20L, 10L, 2L, 3L, 4L),
            state?.upcoming?.map { it.songId }
        )
    }

    @Test
    fun clearUpcomingRemovesAllFutureItems() {
        val engine = QueueEngine()
        engine.startContext(songIds = listOf(1, 2, 3, 4), startIndex = 0)
        engine.addNext(listOf(10))
        engine.addLater(listOf(20))

        val state = engine.clearUpcoming()

        assertEquals(emptyList<Long>(), state?.upcoming?.map { it.songId })
        assertEquals(1L, state?.currentItem?.songId)
    }

    @Test
    fun skipToPreviousRestoresCurrentSongToUpcoming() {
        val engine = QueueEngine()
        engine.startContext(songIds = listOf(1, 2, 3, 4), startIndex = 0)
        engine.addNext(listOf(10))
        engine.skipToNext()

        val previous = engine.skipToPrevious()

        assertEquals(1L, previous?.currentItem?.songId)
        assertEquals(listOf(10L, 2L, 3L, 4L), previous?.upcoming?.map { it.songId })
    }

    @Test
    fun sanitizePromotesFirstValidUpcomingWhenCurrentDisappears() {
        val state = QueueState(
            history = listOf(QueueEntry("h1", 1)),
            currentItem = QueueEntry("c1", 2),
            nextItems = listOf(QueueEntry("n1", 3)),
            laterItems = listOf(QueueEntry("l1", 4)),
            remainingContextItems = listOf(QueueEntry("r1", 5)),
        )

        val sanitized = state.sanitize(setOf(1L, 3L, 4L, 5L))

        assertEquals(3L, sanitized?.currentItem?.songId)
        assertEquals(listOf(1L), sanitized?.history?.map { it.songId })
        assertEquals(listOf(4L, 5L), sanitized?.upcoming?.map { it.songId })
    }

    @Test
    fun sanitizeReturnsNullWhenNothingIsPlayable() {
        val state = QueueState(
            history = listOf(QueueEntry("h1", 1)),
            currentItem = QueueEntry("c1", 2),
            nextItems = listOf(QueueEntry("n1", 3)),
            laterItems = emptyList(),
            remainingContextItems = emptyList(),
        )

        assertNull(state.sanitize(emptySet()))
    }
}
