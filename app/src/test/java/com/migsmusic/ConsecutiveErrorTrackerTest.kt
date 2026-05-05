package com.migsmusic

import com.migsmusic.playback.ConsecutiveErrorTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsecutiveErrorTrackerTest {
    @Test
    fun firstErrorAloneDoesNotTripStop() {
        val clock = MutableClock()
        val tracker = ConsecutiveErrorTracker(burstWindowMs = 1_000L, maxConsecutive = 3, clock = clock::now)

        // One error: counter goes to 1, threshold is 3, so don't stop.
        assertFalse("First error should not trip stop", tracker.recordError())
    }

    @Test
    fun threeErrorsInsideBurstWindowDoNotTripButFourthDoes() {
        val clock = MutableClock()
        val tracker = ConsecutiveErrorTracker(burstWindowMs = 1_000L, maxConsecutive = 3, clock = clock::now)

        // 3 errors arrive in 600ms — under the 1000ms window. Threshold is "more than 3", so 3 is fine.
        assertFalse(tracker.recordError())
        clock.advance(200)
        assertFalse(tracker.recordError())
        clock.advance(200)
        assertFalse(tracker.recordError())
        // 4th still inside the window — now we're at 4 > 3, stop.
        clock.advance(200)
        assertTrue("Fourth consecutive error should trip stop", tracker.recordError())
    }

    @Test
    fun gapLongerThanBurstWindowResetsCounter() {
        val clock = MutableClock()
        val tracker = ConsecutiveErrorTracker(burstWindowMs = 1_000L, maxConsecutive = 3, clock = clock::now)

        // Build up 3 errors close together.
        repeat(3) {
            tracker.recordError()
            clock.advance(100)
        }
        // Wait past the window — next error counts as a fresh burst.
        clock.advance(2_000)
        assertFalse("Error after long gap should not trip stop", tracker.recordError())
    }

    @Test
    fun explicitResetWipesCounterMidBurst() {
        val clock = MutableClock()
        val tracker = ConsecutiveErrorTracker(burstWindowMs = 1_000L, maxConsecutive = 3, clock = clock::now)

        repeat(3) {
            tracker.recordError()
            clock.advance(100)
        }
        // A successful media transition resets — next error should not trip stop even though
        // it lands inside the original burst window.
        tracker.reset()
        clock.advance(50)
        assertFalse("After reset, next error should not trip stop", tracker.recordError())
    }

    @Test
    fun bursts3errorsInWindowOnlyTripsOnFourth() {
        // Documents the boundary: maxConsecutive=3 means "tolerate 3, stop on the 4th."
        val clock = MutableClock()
        val tracker = ConsecutiveErrorTracker(burstWindowMs = 500L, maxConsecutive = 3, clock = clock::now)

        assertFalse(tracker.recordError())
        assertFalse(tracker.recordError())
        assertFalse(tracker.recordError())
        assertTrue(tracker.recordError())
    }
}

private class MutableClock(private var nowMs: Long = 0L) {
    fun now(): Long = nowMs

    fun advance(deltaMs: Long) {
        nowMs += deltaMs
    }
}
