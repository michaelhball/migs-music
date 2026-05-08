package com.migsmusic.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.migsmusic.AppPreferences
import com.migsmusic.MainActivity
import com.migsmusic.data.local.entity.SongEntity
import com.migsmusic.data.repository.LibraryRepository
import com.migsmusic.data.repository.PlaybackSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class PlaybackManager(
    context: Context,
    private val libraryRepository: LibraryRepository,
    private val sessionRepository: PlaybackSessionRepository,
    private val preferences: AppPreferences,
) : PlaybackController {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val player =
        ExoPlayer.Builder(applicationContext)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                // handleAudioFocus =
                true,
            )
            .build()
    private val queueEngine = QueueEngine()
    private var mediaSession: MediaSession? = null

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    // Live position ticks every 500ms when playing — kept separate from uiState so that the
    // mini-player (which doesn't display position) isn't forced to recompose constantly.
    // Composables that DO display position must observe this flow explicitly.
    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(preferences.shuffleEnabled)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _crossfadeMs = MutableStateFlow(preferences.crossfadeMs)

    /** Crossfade duration in ms; 0 = disabled. Read by Settings UI for the slider. */
    val crossfadeMs: StateFlow<Long> = _crossfadeMs.asStateFlow()

    /**
     * Lazily-built second player used only for the fade-out audio during a crossfade.
     * Built on first crossfade and kept alive thereafter (rebuilding per crossfade burns
     * AudioTrack init time). Audio focus is intentionally NOT handled by this player —
     * the main player owns focus, and dual focus management would fight itself.
     */
    private var crossfadePlayer: ExoPlayer? = null

    /**
     * In-flight crossfade. Kept as a Job so user actions (skip, pause, queue change,
     * disabling crossfade entirely) can cancel it cleanly via [cancelCrossfade].
     */
    private var crossfadeJob: kotlinx.coroutines.Job? = null

    private val _currentSongId = MutableStateFlow<Long?>(null)
    override val currentSongId: StateFlow<Long?> = _currentSongId.asStateFlow()

    /**
     * Mirror of [ExoPlayer.isPlaying] as a flow so the position ticker can suspend until the
     * player is actually playing — instead of polling every 500ms while paused. Updated from
     * [Player.Listener.onIsPlayingChanged].
     */
    private val _isPlayerPlaying = MutableStateFlow(false)

    /**
     * Conflated channel funnels all snapshot-save requests through a single writer coroutine.
     * If many requests arrive in quick succession (e.g. rapid skip taps) only the latest one
     * actually runs — preventing last-writer-wins races where a stale `currentPositionMs`
     * could clobber a fresher save. Capacity 1 + DROP_OLDEST = "always keep the latest".
     */
    private val snapshotRequests = Channel<Unit>(capacity = Channel.CONFLATED)

    /**
     * Tracks consecutive `onPlayerError` events so a queue full of unplayable items doesn't
     * spin through every entry in milliseconds. Reset on successful media transition.
     */
    private val errorTracker =
        ConsecutiveErrorTracker(
            burstWindowMs = ERROR_BURST_WINDOW_MS,
            maxConsecutive = MAX_CONSECUTIVE_ERRORS,
        )

    /**
     * Tracks which queue entry's artwork we've already embedded into the live MediaItem.
     * Keyed on entryId (not songId) so that re-visiting the same songId via a different
     * QueueEntry (e.g. after a queue rebuild) re-embeds against the new MediaItem.
     */
    private var lastArtworkEntryId: String? = null

    /**
     * Cache of `songId -> SongEntity` for the current queue. Populated in [syncPlayer] (the
     * only place a queue rebuild happens); reused by [publishUiState] so listener-fired
     * publishes don't re-query Room every play/pause / item transition.
     * Invariant: covers every entry in `queueEngine.currentState().effectiveQueue`.
     */
    private var queueSongCache: Map<Long, SongEntity> = emptyMap()

    // Note: queue-mutating operations all use plain `scope.launch`. They run sequentially on
    // Main.immediate (cooperative scheduling), and `syncPlayer` always rebuilds the full player
    // media list from current queueEngine state — so even if rapid taps interleave, the last
    // operation determines the final state. Tried wrapping with cancel-prior-job pattern but
    // it caused intermittent failures in queueClearRemovesUpcomingButKeepsCurrent and others.

    init {
        player.addListener(
            object : Player.Listener {
                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    // A successful transition means whatever the previous item was, the player
                    // either played or was deliberately moved past it — clear the error counter.
                    errorTracker.reset()
                    val entryId = mediaItem?.mediaId ?: return
                    val currentState = queueEngine.currentState() ?: return
                    if (currentState.currentItem.entryId == entryId) {
                        scope.launch { publishUiState() }
                        embedArtworkForCurrent()
                        return
                    }

                    when {
                        currentState.upcoming.firstOrNull()?.entryId == entryId -> queueEngine.skipToNext()
                        currentState.history.lastOrNull()?.entryId == entryId -> queueEngine.skipToPrevious()
                        else -> queueEngine.moveToEntry(entryId)
                    }
                    persistSnapshot()
                    scope.launch { publishUiState() }
                    embedArtworkForCurrent()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _isPlayerPlaying.value = isPlaying
                    // Whenever the player transitions to playing, make sure the foreground
                    // service is up. Without this, paths that don't go through playContext
                    // (togglePlayPause, skipToNext, restoreLastSession auto-resume, etc.)
                    // leave us playing audio with no MediaSessionService — meaning no
                    // lock-screen widget, no notification-shade controls, and no system
                    // media integration. Idempotent: starting an already-running foreground
                    // service is a no-op.
                    if (isPlaying) ensureServiceStarted()
                    persistSnapshot()
                    scope.launch { publishUiState() }
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    persistSnapshot()
                    scope.launch { publishUiState() }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (errorTracker.recordError()) {
                        // The queue is in a bad state; stop trying to advance and let the user intervene.
                        Log.w(TAG, "Stopping after consecutive player errors burst")
                        player.pause()
                        player.playWhenReady = false
                        return
                    }

                    Log.w(TAG, "Player error code=${error.errorCode} msg=${error.message}; advancing past failed item")
                    // Skip past the failed media item rather than getting stuck in an error state.
                    if (player.hasNextMediaItem()) {
                        player.seekToNextMediaItem()
                        player.prepare()
                        player.playWhenReady = true
                    } else {
                        // No more items; clear and reset.
                        player.clearMediaItems()
                        queueEngine.clear()
                        persistSnapshot()
                        scope.launch { publishUiState() }
                    }
                }
            },
        )

        scope.launch {
            restoreLastSession()
            publishUiState()
        }

        // Single writer coroutine: drains the conflated snapshot-request channel. Each
        // received signal triggers exactly one IO save against the latest in-memory state,
        // so rapid producer events collapse to one save.
        scope.launch {
            for (signal in snapshotRequests) {
                writeSnapshotNow()
            }
        }

        scope.launch {
            var tick = 0
            while (isActive) {
                // Park until the player is actually playing — saves a wakeup every 500ms while paused.
                if (!_isPlayerPlaying.value) {
                    _isPlayerPlaying.first { it }
                }
                delay(POSITION_TICK_MS)
                if (!_isPlayerPlaying.value) continue
                if (queueEngine.currentState() == null || _uiState.value.currentSong == null) continue

                val pos = player.currentPosition.coerceAtLeast(0L)
                _currentPositionMs.value = pos
                // Suppress duration churn: ExoPlayer reports several intermediate values
                // during track-load (-MAX_LONG → real value), and minor drifts of a few ms
                // around real playback can also trip an "update". Each accepted update
                // re-renders the Slider's valueRange and forces a re-measure. Require a
                // ≥250ms delta to filter that.
                val duration = player.duration.takeIf { it > 0 } ?: continue
                val current = _uiState.value.durationMs
                if (kotlin.math.abs(duration - current) >= MIN_DURATION_DELTA_MS) {
                    _uiState.update { it.copy(durationMs = duration) }
                }
                // Every ~10s of playback, snapshot the position so a process kill mid-song
                // doesn't lose more than ~10s of progress.
                tick++
                if (tick % PERSIST_EVERY_N_TICKS == 0) {
                    persistSnapshot()
                }

                // Crossfade trigger: fire when remaining playback time crosses below the
                // user's configured fade duration. Guarded so the same trigger can't fire
                // twice for one transition, and so that ultra-short tracks (where the
                // fade window would eat the entire song) opt out automatically.
                val crossfadeWindow = _crossfadeMs.value
                if (
                    crossfadeWindow > 0L &&
                    crossfadeJob?.isActive != true &&
                    duration > crossfadeWindow * 2 &&
                    duration - pos in 1..crossfadeWindow &&
                    player.hasNextMediaItem() &&
                    player.repeatMode == Player.REPEAT_MODE_OFF
                ) {
                    crossfadeJob = scope.launch { runCrossfade(crossfadeWindow) }
                }
            }
        }
    }

    fun getOrCreateMediaSession(service: MediaSessionService): MediaSession {
        mediaSession?.let { return it }
        // setSessionActivity wires up the lock-screen / system-media widget so tapping
        // the body of the now-playing card opens our app at MainActivity. Without it the
        // tap is a no-op and the user has to manually re-open the launcher to get back
        // to the player route.
        val launchIntent =
            Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        val sessionActivity =
            PendingIntent.getActivity(
                applicationContext,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return MediaSession.Builder(service, player)
            .setSessionActivity(sessionActivity)
            .build()
            .also { mediaSession = it }
    }

    fun clearMediaSession() {
        mediaSession?.release()
        mediaSession = null
    }

    fun playContext(
        songIds: List<Long>,
        startIndex: Int,
        shuffle: Boolean = false,
    ) {
        // Honor the persisted shuffle preference: if the user has shuffle ON, every new
        // context shuffles, even when the caller didn't explicitly ask. The explicit `shuffle`
        // arg still wins for "Shuffle Playlist" / "Shuffle Folder" buttons.
        val effectiveShuffle = shuffle || _shuffleEnabled.value
        scope.launch {
            ensureServiceStarted()
            val queueState =
                queueEngine.startContext(
                    songIds = songIds,
                    startIndex = startIndex,
                    shuffle = effectiveShuffle,
                ) ?: return@launch
            syncPlayer(queueState = queueState, startPositionMs = 0L, playWhenReady = true)
        }
    }

    fun playNext(songId: Long) {
        scope.launch {
            ensureServiceStarted()
            val currentState = queueEngine.currentState()
            val updatedState =
                if (currentState == null) {
                    queueEngine.startContext(listOf(songId), 0)
                } else {
                    queueEngine.addNext(listOf(songId))
                } ?: return@launch

            syncPlayer(
                queueState = updatedState,
                startPositionMs = player.currentPosition.coerceAtLeast(0L),
                playWhenReady = player.playWhenReady,
            )
        }
    }

    fun playLater(songId: Long) {
        scope.launch {
            ensureServiceStarted()
            val currentState = queueEngine.currentState()
            val updatedState =
                if (currentState == null) {
                    queueEngine.startContext(listOf(songId), 0)
                } else {
                    queueEngine.addLater(listOf(songId))
                } ?: return@launch

            syncPlayer(
                queueState = updatedState,
                startPositionMs = player.currentPosition.coerceAtLeast(0L),
                playWhenReady = player.playWhenReady,
            )
        }
    }

    fun removeUpcoming(entryId: String) {
        scope.launch {
            val updated = queueEngine.removeUpcoming(entryId) ?: return@launch
            syncPlayer(
                queueState = updated,
                startPositionMs = player.currentPosition.coerceAtLeast(0L),
                playWhenReady = player.playWhenReady,
            )
        }
    }

    fun moveUpcoming(
        entryId: String,
        newIndex: Int,
    ) {
        scope.launch {
            val updated = queueEngine.moveUpcoming(entryId, newIndex) ?: return@launch
            syncPlayer(
                queueState = updated,
                startPositionMs = player.currentPosition.coerceAtLeast(0L),
                playWhenReady = player.playWhenReady,
            )
        }
    }

    /**
     * Stops playback, clears the queue, and resets the persisted snapshot. Used when the
     * user deletes the playlist whose songs are currently in the queue — continuing to
     * play (or showing the now-orphan track in the mini-player) would be confusing.
     */
    override fun stopAndClearQueue() {
        scope.launch {
            cancelCrossfade()
            withContext(Dispatchers.Main.immediate) {
                player.pause()
                player.clearMediaItems()
            }
            queueEngine.clear()
            queueSongCache = emptyMap()
            _uiState.value = PlaybackUiState()
            _currentSongId.value = null
            _currentPositionMs.value = 0L
            persistSnapshot()
        }
    }

    fun clearUpcoming() {
        scope.launch {
            val updated = queueEngine.clearUpcoming() ?: return@launch
            syncPlayer(
                queueState = updated,
                startPositionMs = player.currentPosition.coerceAtLeast(0L),
                playWhenReady = player.playWhenReady,
            )
        }
    }

    fun refreshLibraryState() {
        scope.launch {
            val currentState = queueEngine.currentState() ?: return@launch
            syncPlayer(
                queueState = currentState,
                startPositionMs = player.currentPosition.coerceAtLeast(0L),
                playWhenReady = player.playWhenReady,
            )
        }
    }

    fun jumpToEntry(entryId: String) {
        val queueState = queueEngine.currentState() ?: return
        val index = queueState.effectiveQueue.indexOfFirst { it.entryId == entryId }
        if (index == -1) return
        cancelCrossfade()
        player.seekTo(index, 0L)
        player.playWhenReady = true
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            cancelCrossfade()
            player.pause()
        } else {
            player.play()
        }
    }

    fun skipToNext() {
        cancelCrossfade()
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        }
    }

    fun skipToPrevious() {
        cancelCrossfade()
        if (player.currentPosition > SKIP_PREV_REWIND_THRESHOLD_MS) {
            player.seekTo(0L)
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        }
    }

    fun seekTo(positionMs: Long) {
        // Mid-track seek crosses or skips the fade window — abort any in-flight crossfade
        // so a user-triggered seek doesn't leave the volume stuck at a partial level.
        cancelCrossfade()
        player.seekTo(positionMs)
    }

    fun setRepeatMode(repeatMode: Int) {
        player.repeatMode = repeatMode
    }

    fun cycleRepeatMode() {
        player.repeatMode =
            when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
    }

    /**
     * Toggles shuffle for the natural-order portion of the current queue. Turning ON
     * re-shuffles the remaining context songs in place; turning OFF stops shuffling
     * future context starts but does not restore the prior order (we don't snapshot it).
     */
    fun toggleShuffle() {
        val newState = !_shuffleEnabled.value
        _shuffleEnabled.value = newState
        preferences.shuffleEnabled = newState
        if (newState) {
            scope.launch {
                val updated = queueEngine.shuffleRemainingContext() ?: return@launch
                syncPlayer(
                    queueState = updated,
                    startPositionMs = player.currentPosition.coerceAtLeast(0L),
                    playWhenReady = player.playWhenReady,
                )
            }
        }
    }

    fun release() {
        clearMediaSession()
        player.release()
    }

    /**
     * Test-only state reset. The PlaybackManager is a long-lived singleton on the AppContainer,
     * so back-to-back instrumentation tests inherit each other's queue, audio focus, and
     * cached state — that's the dominant source of suite-level flakiness. Tests call this in
     * `@Before` to start from a clean slate without restarting the process.
     *
     * Stops playback, clears the player media list, wipes the queue engine, resets caches and
     * counters, and zeroes the persisted snapshot so a subsequent cold-start doesn't restore
     * stale state from a previous test.
     */
    suspend fun resetForTest() =
        withContext(Dispatchers.Main.immediate) {
            player.pause()
            player.playWhenReady = false
            player.stop()
            player.clearMediaItems()
            player.repeatMode = Player.REPEAT_MODE_OFF
            queueEngine.clear()
            queueSongCache = emptyMap()
            lastArtworkEntryId = null
            errorTracker.reset()
            _shuffleEnabled.value = false
            preferences.shuffleEnabled = false
            // Also clear the player-route restore flag so the next Activity launch lands on
            // Songs (the default), not on the empty Player screen. Belt-and-suspenders with
            // the isInstrumentationRunning() gate in MigsMusicApp.
            preferences.wasOnPlayerRoute = false
            _isPlayerPlaying.value = false
            _uiState.value = PlaybackUiState()
            _currentPositionMs.value = 0L
            _currentSongId.value = null
            // Release the MediaSession + stop the foreground service so the next test starts from a
            // truly clean slate. Without this, leftover session/service state from the previous test
            // (e.g. one created via playContext) survives and intermittently blocks Compose idle.
            clearMediaSession()
            applicationContext.stopService(Intent(applicationContext, MediaPlaybackService::class.java))
            // Stays inside Main.immediate so writeSnapshotNow's player.currentPosition read is safe.
            writeSnapshotNow()
        }

    /**
     * Called when the user swipes the app away from Recents. Stops playback so audio
     * doesn't keep playing in the background, and persists the snapshot so the queue +
     * position survive the next cold start.
     */
    fun stopForTaskRemoval() {
        scope.launch {
            withContext(Dispatchers.Main.immediate) {
                player.pause()
                player.playWhenReady = false
            }
            // Persist synchronously while the position is still meaningful. We can't go through
            // the conflated channel here because `player.stop()` immediately after would zero
            // the position before the writer coroutine reads it.
            writeSnapshotNow()
            withContext(Dispatchers.Main.immediate) {
                player.stop()
            }
            publishUiState()
        }
    }

    fun setCrossfadeMs(ms: Long) {
        val clamped = ms.coerceIn(0L, 12_000L)
        preferences.crossfadeMs = clamped
        _crossfadeMs.value = clamped
        if (clamped == 0L) cancelCrossfade()
    }

    /**
     * Aborts any in-flight crossfade and returns the main player to full volume.
     * Idempotent — safe to call when nothing is happening. Called by every action that
     * either bypasses the natural track end (skip, jumpToEntry, queue rebuild via
     * playContext) or pauses the listening session (togglePlayPause → pause).
     */
    private fun cancelCrossfade() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        crossfadePlayer?.let { p ->
            p.stop()
            p.clearMediaItems()
        }
        player.volume = 1f
    }

    private fun getOrBuildCrossfadePlayer(): ExoPlayer =
        crossfadePlayer ?: ExoPlayer.Builder(applicationContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                // handleAudioFocus =
                false,
            )
            .build()
            .also { crossfadePlayer = it }

    /**
     * Plays the outgoing track on a secondary player at decreasing volume while
     * advancing the main player to the next track at increasing volume — for
     * `crossfadeMs` total. The main player owns the MediaSession + UI state, so the
     * lock-screen / mini-player switch to the new track at the moment of advance;
     * the user's ear hears the fade.
     *
     * Cancellation: if the Job is cancelled mid-fade, the finally block stops the
     * outgoing player and restores main volume to 1 — this is the common path for
     * "user hit skip during a fade".
     */
    private suspend fun runCrossfade(crossfadeMs: Long) {
        val outgoingItem = player.currentMediaItem ?: return
        val outgoingPosition = player.currentPosition.coerceAtLeast(0L)
        val outgoingPlayer = getOrBuildCrossfadePlayer()
        try {
            // Stage 1: load the outgoing track on the secondary player while it's still
            // muted. We don't flip the main player or start any volume animation yet —
            // doing so before the secondary player is decoded leaves a brief silent
            // window (main is muted-and-advanced; secondary is buffering) that's
            // perceived as a gap at the very start of the crossfade.
            withContext(Dispatchers.Main.immediate) {
                outgoingPlayer.volume = 0f
                outgoingPlayer.setMediaItem(outgoingItem)
                outgoingPlayer.prepare()
                outgoingPlayer.seekTo(outgoingPosition)
                outgoingPlayer.playWhenReady = true
            }

            // Stage 2: wait for the secondary player to actually be ready to emit audio.
            // Local file playback typically resolves in <50ms; the timeout is a safety
            // net so a stuck player can't hang the crossfade indefinitely.
            awaitPlayerReady(outgoingPlayer, timeoutMs = CROSSFADE_READY_TIMEOUT_MS)

            // Stage 3: cross over. Both players are now producing audio (one muted, one
            // at full); the volume animation drives the actual fade.
            withContext(Dispatchers.Main.immediate) {
                outgoingPlayer.volume = 1f
                player.volume = 0f
                player.seekToNextMediaItem()
            }

            val steps = STEPS_PER_CROSSFADE
            val stepDelay = (crossfadeMs / steps).coerceAtLeast(MIN_STEP_DELAY_MS)
            for (i in 1..steps) {
                delay(stepDelay)
                val progress = i.toFloat() / steps
                withContext(Dispatchers.Main.immediate) {
                    outgoingPlayer.volume = (1f - progress).coerceAtLeast(0f)
                    player.volume = progress.coerceAtMost(1f)
                }
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
                outgoingPlayer.stop()
                outgoingPlayer.clearMediaItems()
                player.volume = 1f
            }
        }
    }

    /**
     * Suspends until [p] reaches Player.STATE_READY or the timeout elapses. Returns
     * true if it became ready, false on timeout. Detaches its listener on every exit
     * path including cancellation, so a cancelled crossfade never leaks a Listener.
     */
    private suspend fun awaitPlayerReady(
        p: ExoPlayer,
        timeoutMs: Long,
    ): Boolean {
        if (p.playbackState == Player.STATE_READY) return true
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Unit> { cont ->
                val listener =
                    object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) {
                                p.removeListener(this)
                                if (cont.isActive) cont.resume(Unit)
                            }
                        }
                    }
                p.addListener(listener)
                cont.invokeOnCancellation { p.removeListener(listener) }
                // Edge case: state flipped to READY between the check above and addListener.
                if (p.playbackState == Player.STATE_READY) {
                    p.removeListener(listener)
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        } != null
    }

    private companion object {
        const val TAG = "PlaybackManager"

        /** How often the live position flow ticks while playing. */
        const val POSITION_TICK_MS = 500L

        /** Crossfade animation: 30 steps over the fade window = ~33ms apart at 1s. */
        const val STEPS_PER_CROSSFADE = 30
        const val MIN_STEP_DELAY_MS = 16L

        /**
         * Cap on how long we wait for the secondary player to reach STATE_READY before
         * starting the cross-over. Local file playback is normally <50ms; this timeout
         * is the worst-case fallback so a stuck player can't hang the crossfade.
         */
        const val CROSSFADE_READY_TIMEOUT_MS = 1_500L

        /** Persist snapshot every N position ticks (≈ once per 10s of playback). */
        const val PERSIST_EVERY_N_TICKS = 20

        /** If we're past this point in the current song, "skip previous" rewinds to 0 instead of jumping to the previous track. */
        const val SKIP_PREV_REWIND_THRESHOLD_MS = 3_000L

        /** Window during which repeated `onPlayerError` events count as one burst. */
        const val ERROR_BURST_WINDOW_MS = 1_000L

        /**
         * Below this threshold we ignore duration updates. ExoPlayer reports duration in a
         * staircase as the player sets up; without this the Slider's valueRange thrashes
         * several times per track and the slider thumb jitters during track-loads.
         */
        const val MIN_DURATION_DELTA_MS = 250L

        /** Stop auto-advancing once we hit this many errors inside one burst window. */
        const val MAX_CONSECUTIVE_ERRORS = 3
    }

    private suspend fun restoreLastSession() {
        val restored = sessionRepository.load() ?: return
        // Sanitize against the live song table BEFORE seeding queueEngine, so a brief window
        // never exposes stale (deleted) songIds via currentState(). Symmetric with syncPlayer.
        val songsById = loadSongsById(restored.queueState.effectiveQueue.map { it.songId })
        val sanitized = restored.queueState.sanitize(songsById.keys) ?: return
        queueEngine.restore(sanitized, restored.nextEntrySeed)
        player.repeatMode = restored.repeatMode
        // Restore queue and position, but never auto-resume. A cold-start surprise of music
        // suddenly playing is jarring — the user opened the app to do something, not because
        // they explicitly want playback. Tapping play is one tap; an unwanted blast of audio
        // in a quiet room is worse.
        syncPlayer(
            queueState = sanitized,
            startPositionMs = restored.currentPositionMs,
            playWhenReady = false,
        )
    }

    private suspend fun syncPlayer(
        queueState: QueueState,
        startPositionMs: Long,
        playWhenReady: Boolean,
    ) {
        val songsById = loadSongsById(queueState.effectiveQueue.map { it.songId })
        queueSongCache = songsById
        val sanitizedQueue = queueState.sanitize(songsById.keys)

        if (sanitizedQueue == null) {
            queueEngine.clear()
            queueSongCache = emptyMap()
            withContext(Dispatchers.Main.immediate) {
                player.stop()
                player.clearMediaItems()
            }
            persistSnapshot()
            publishUiState()
            return
        }

        if (sanitizedQueue != queueEngine.currentState()) {
            queueEngine.restore(sanitizedQueue, queueEngine.nextEntrySeed())
        }

        val effectiveQueue = sanitizedQueue.effectiveQueue

        // Try a single-item-diff fast path first (add/remove/move one upcoming entry without
        // touching the currently-playing item). Avoids a full setMediaItems rebuild on every
        // playNext/playLater/removeUpcoming/moveUpcoming — which is otherwise O(N) media-item
        // construction + an internal ExoPlayer prepare cascade.
        val appliedIncremental = tryIncrementalSync(sanitizedQueue, songsById)
        if (!appliedIncremental) {
            val mediaItems =
                effectiveQueue.mapNotNull { entry ->
                    val song = songsById[entry.songId] ?: return@mapNotNull null
                    buildMediaItem(entry = entry, song = song)
                }
            if (mediaItems.isEmpty()) return

            val currentIndex =
                effectiveQueue.indexOfFirst { it.entryId == sanitizedQueue.currentItem.entryId }
                    .coerceAtLeast(0)
            withContext(Dispatchers.Main.immediate) {
                player.setMediaItems(mediaItems, currentIndex, startPositionMs)
                player.prepare()
                player.playWhenReady = playWhenReady
            }
            // Reset the artwork cache key whenever the queue is rebuilt so we re-embed for whatever's now current.
            lastArtworkEntryId = null
        }

        persistSnapshot()
        publishUiState()
        embedArtworkForCurrent()
    }

    /**
     * Detects a single insertion/removal/move between the player's current media list and
     * [newQueueState]'s effective queue. Applies it via [ExoPlayer.addMediaItem] /
     * [ExoPlayer.removeMediaItem] / [ExoPlayer.moveMediaItem] when the diff is exactly one
     * such mutation that doesn't touch the currently-playing item. Returns true on success;
     * false if the caller should fall back to a full [ExoPlayer.setMediaItems] rebuild.
     */
    private suspend fun tryIncrementalSync(
        newQueueState: QueueState,
        songsById: Map<Long, SongEntity>,
    ): Boolean {
        val newQueue = newQueueState.effectiveQueue
        val newIds = newQueue.map { it.entryId }
        val playerSnapshot: List<String> =
            withContext(Dispatchers.Main.immediate) {
                val n = player.mediaItemCount
                if (n == 0) return@withContext emptyList()
                (0 until n).map { i -> player.getMediaItemAt(i).mediaId }
            }
        val currentMediaIndex = withContext(Dispatchers.Main.immediate) { player.currentMediaItemIndex }

        return when (val diff = computeIncrementalDiff(playerSnapshot, newIds, currentMediaIndex)) {
            IncrementalDiff.NoOp -> true
            IncrementalDiff.FullRebuild -> false
            is IncrementalDiff.Insert -> {
                val entry = newQueue[diff.index]
                val song = songsById[entry.songId] ?: return false
                val mediaItem = buildMediaItem(entry, song)
                withContext(Dispatchers.Main.immediate) { player.addMediaItem(diff.index, mediaItem) }
                true
            }
            is IncrementalDiff.Remove -> {
                withContext(Dispatchers.Main.immediate) { player.removeMediaItem(diff.index) }
                true
            }
            is IncrementalDiff.Move -> {
                withContext(Dispatchers.Main.immediate) { player.moveMediaItem(diff.from, diff.to) }
                true
            }
        }
    }

    private suspend fun publishUiState() {
        val rawQueueState = queueEngine.currentState()
        // Reuse the cached song map populated by syncPlayer. Falls back to a Room query only
        // if the cache doesn't cover the current queue (e.g. after a library rescan that added
        // entries). Avoids 2 Room round-trips on every play/pause / item transition.
        val songIds = rawQueueState?.effectiveQueue?.map { it.songId } ?: emptyList()
        val cacheCovers = songIds.all { queueSongCache.containsKey(it) }
        val songsById =
            if (cacheCovers && songIds.isNotEmpty()) {
                queueSongCache
            } else if (rawQueueState != null) {
                loadSongsById(songIds).also { queueSongCache = it }
            } else {
                emptyMap()
            }
        val queueState = rawQueueState?.sanitize(songsById.keys)
        if (queueState == null) {
            if (rawQueueState != null) {
                queueEngine.clear()
                queueSongCache = emptyMap()
            }
            _uiState.value = PlaybackUiState()
            _currentSongId.value = null
            return
        }

        if (queueState != rawQueueState) {
            queueEngine.restore(queueState, queueEngine.nextEntrySeed())
        }

        val current = songsById[queueState.currentItem.songId]
        val upcoming =
            queueState.upcoming.mapNotNull { entry ->
                songsById[entry.songId]?.toUiSong(entry)
            }
        val history =
            queueState.history.mapNotNull { entry ->
                songsById[entry.songId]?.toUiSong(entry)
            }

        _uiState.value =
            PlaybackUiState(
                isPlaying = player.isPlaying,
                currentSong = current?.toUiSong(queueState.currentItem),
                history = history,
                upcoming = upcoming,
                durationMs = player.duration.takeIf { it > 0 } ?: current?.durationMs ?: 0L,
                repeatMode = player.repeatMode,
            )
        _currentPositionMs.value = player.currentPosition.coerceAtLeast(0L)
        _currentSongId.value = current?.id
    }

    private suspend fun loadSongsById(ids: List<Long>): Map<Long, SongEntity> =
        libraryRepository.getSongsByIds(ids.distinct()).associateBy { it.id }

    private fun buildMediaItem(
        entry: QueueEntry,
        song: SongEntity,
    ): MediaItem =
        MediaItem.Builder()
            .setMediaId(entry.entryId)
            .setUri(Uri.parse(song.contentUri))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .setArtworkUri(song.albumArtUri?.toUri())
                    .build(),
            )
            .build()

    /**
     * Loads album-art bytes for a song. Tries two paths in order:
     *
     * 1. The MediaStore album-art URI (`song.albumArtUri`), which is the cheap path —
     *    a single content-resolver call. Works when the device's MediaScanner has
     *    extracted album art into MediaStore.Audio.Albums. Returns null on devices
     *    where the OEM provider doesn't expose those bytes (observed on OnePlus).
     *
     * 2. `MediaMetadataRetriever.embeddedPicture` against the audio file itself.
     *    Slower (decodes the file's headers) but reliable: as long as the song has
     *    embedded ID3v2/MP4/Vorbis cover art, we get it back as raw bytes.
     *
     * Either way, the bytes come back as raw JPEG/PNG/etc. — exactly what
     * `MediaMetadata.setArtworkData` expects.
     */
    private suspend fun loadArtworkBytes(song: SongEntity): ByteArray? =
        withContext(Dispatchers.IO) {
            // Path 1: MediaStore album URI (fast).
            song.albumArtUri?.let { uri ->
                runCatching {
                    applicationContext.contentResolver.openInputStream(uri.toUri())?.use { it.readBytes() }
                }.getOrNull()
            }?.let { return@withContext it }

            // Path 2: read embedded picture from the audio file. MediaMetadataRetriever
            // can take a content URI directly; the platform handles the read.
            runCatching {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(applicationContext, song.contentUri.toUri())
                    mmr.embeddedPicture
                } finally {
                    mmr.release()
                }
            }.getOrNull()
        }

    /**
     * Loads the album art for whatever song is currently playing and replaces the
     * live MediaItem's metadata with the embedded bytes. The system lockscreen /
     * notification / Quick Settings media controls all read bytes directly via the
     * MediaSession's metadata; setArtworkData is what makes them show art, not
     * setArtworkUri (which the OEM bitmap loader sometimes can't resolve from a
     * background context).
     */
    private fun embedArtworkForCurrent() {
        scope.launch {
            val state = queueEngine.currentState() ?: return@launch
            val currentEntry = state.currentItem
            if (currentEntry.entryId == lastArtworkEntryId) return@launch

            // Prefer the in-memory queue cache (already loaded by syncPlayer) over re-querying
            // Room — same song, same row, but skips an IO hop on every track transition.
            val song =
                queueSongCache[currentEntry.songId]
                    ?: libraryRepository.getSongsByIds(listOf(currentEntry.songId)).firstOrNull()
                    ?: return@launch
            val artBytes =
                loadArtworkBytes(song) ?: run {
                    // No art recoverable from any path — mark this entry as resolved so we
                    // don't keep retrying on every metadata callback.
                    lastArtworkEntryId = currentEntry.entryId
                    return@launch
                }

            withContext(Dispatchers.Main.immediate) {
                val playerCurrent = player.currentMediaItem ?: return@withContext
                if (playerCurrent.mediaId != currentEntry.entryId) return@withContext
                val updatedMetadata =
                    playerCurrent.mediaMetadata.buildUpon()
                        .setArtworkData(artBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .build()
                val updatedItem = playerCurrent.buildUpon().setMediaMetadata(updatedMetadata).build()
                player.replaceMediaItem(player.currentMediaItemIndex, updatedItem)
                lastArtworkEntryId = currentEntry.entryId
            }
        }
    }

    private fun ensureServiceStarted() {
        val intent = Intent(applicationContext, MediaPlaybackService::class.java)
        ContextCompat.startForegroundService(applicationContext, intent)
    }

    private fun persistSnapshot() {
        // Conflated channel: if the writer is busy, this just overwrites the pending signal —
        // the next save reads whatever the latest in-memory state is at that moment.
        snapshotRequests.trySend(Unit)
    }

    private suspend fun writeSnapshotNow() {
        val queueState = queueEngine.currentState()
        val currentPositionMs = player.currentPosition.coerceAtLeast(0L)
        val isPlaying = player.isPlaying
        val repeatMode = player.repeatMode
        val nextEntrySeed = queueEngine.nextEntrySeed()

        withContext(Dispatchers.IO) {
            sessionRepository.save(
                queueState = queueState,
                currentPositionMs = currentPositionMs,
                isPlaying = isPlaying,
                repeatMode = repeatMode,
                nextEntrySeed = nextEntrySeed,
            )
        }
    }

    private fun SongEntity.toUiSong(entry: QueueEntry) =
        PlaybackSongUiModel(
            entryId = entry.entryId,
            songId = id,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            albumArtUri = albumArtUri,
        )
}

data class PlaybackUiState(
    val isPlaying: Boolean = false,
    val currentSong: PlaybackSongUiModel? = null,
    val history: List<PlaybackSongUiModel> = emptyList(),
    val upcoming: List<PlaybackSongUiModel> = emptyList(),
    val durationMs: Long = 0L,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
)

data class PlaybackSongUiModel(
    val entryId: String,
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val albumArtUri: String? = null,
)
