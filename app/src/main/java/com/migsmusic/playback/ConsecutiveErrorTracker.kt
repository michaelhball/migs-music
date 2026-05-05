package com.migsmusic.playback

/**
 * Counts how many errors we've seen in a recent burst window. Used by [PlaybackManager] to
 * stop auto-advancing through a queue full of unplayable items — without that check, a corrupt
 * queue spins through every entry in milliseconds emitting one onPlayerError per item.
 *
 * Pure logic + injected clock so it's deterministically unit-testable.
 */
internal class ConsecutiveErrorTracker(
    private val burstWindowMs: Long,
    private val maxConsecutive: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var consecutive = 0
    private var lastAtMs = 0L

    /**
     * Records one error and returns true iff the caller should stop auto-advancing — i.e. more
     * than [maxConsecutive] errors have arrived inside the trailing [burstWindowMs] window.
     */
    fun recordError(): Boolean {
        val now = clock()
        consecutive = if (now - lastAtMs < burstWindowMs) consecutive + 1 else 1
        lastAtMs = now
        return consecutive > maxConsecutive
    }

    /** Call on a successful media transition — wipes the burst counter. */
    fun reset() {
        consecutive = 0
    }
}
