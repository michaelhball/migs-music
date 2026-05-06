package com.migsmusic.playback

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
import kotlinx.coroutines.withContext

class PlaybackManager(
    context: Context,
    private val libraryRepository: LibraryRepository,
    private val sessionRepository: PlaybackSessionRepository,
    private val preferences: AppPreferences,
) {
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

    private val _currentSongId = MutableStateFlow<Long?>(null)
    val currentSongId: StateFlow<Long?> = _currentSongId.asStateFlow()

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
                val duration = player.duration.takeIf { it > 0 }
                if (duration != null && duration != _uiState.value.durationMs) {
                    _uiState.update { it.copy(durationMs = duration) }
                }
                // Every ~10s of playback, snapshot the position so a process kill mid-song
                // doesn't lose more than ~10s of progress.
                tick++
                if (tick % PERSIST_EVERY_N_TICKS == 0) {
                    persistSnapshot()
                }
            }
        }
    }

    fun getOrCreateMediaSession(service: MediaSessionService): MediaSession {
        mediaSession?.let { return it }
        return MediaSession.Builder(service, player).build().also { mediaSession = it }
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
        player.seekTo(index, 0L)
        player.playWhenReady = true
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun skipToNext() {
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        }
    }

    fun skipToPrevious() {
        if (player.currentPosition > SKIP_PREV_REWIND_THRESHOLD_MS) {
            player.seekTo(0L)
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        }
    }

    fun seekTo(positionMs: Long) {
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

    private companion object {
        const val TAG = "PlaybackManager"

        /** How often the live position flow ticks while playing. */
        const val POSITION_TICK_MS = 500L

        /** Persist snapshot every N position ticks (≈ once per 10s of playback). */
        const val PERSIST_EVERY_N_TICKS = 20

        /** If we're past this point in the current song, "skip previous" rewinds to 0 instead of jumping to the previous track. */
        const val SKIP_PREV_REWIND_THRESHOLD_MS = 3_000L

        /** Window during which repeated `onPlayerError` events count as one burst. */
        const val ERROR_BURST_WINDOW_MS = 1_000L

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
        syncPlayer(
            queueState = sanitized,
            startPositionMs = restored.currentPositionMs,
            playWhenReady = restored.isPlaying,
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
        if (playerSnapshot.isEmpty()) return false
        if (playerSnapshot == newIds) return true

        val currentMediaIndex = withContext(Dispatchers.Main.immediate) { player.currentMediaItemIndex }

        // Single insertion: newIds is playerSnapshot with one extra entry inserted at index `at`.
        if (newIds.size == playerSnapshot.size + 1) {
            val at =
                (newIds.indices).firstOrNull { i ->
                    i >= playerSnapshot.size || playerSnapshot[i] != newIds[i]
                } ?: return false
            if (newIds.drop(at + 1) != playerSnapshot.drop(at)) return false
            // Don't insert at the current index — would shift the currently-playing item.
            if (at <= currentMediaIndex) return false
            val entry = newQueue[at]
            val song = songsById[entry.songId] ?: return false
            val mediaItem = buildMediaItem(entry, song)
            withContext(Dispatchers.Main.immediate) {
                player.addMediaItem(at, mediaItem)
            }
            return true
        }

        // Single removal: playerSnapshot is newIds with one extra entry at index `at`.
        if (newIds.size == playerSnapshot.size - 1) {
            val at =
                (playerSnapshot.indices).firstOrNull { i ->
                    i >= newIds.size || playerSnapshot[i] != newIds[i]
                } ?: return false
            if (playerSnapshot.drop(at + 1) != newIds.drop(at)) return false
            // Removing the currently-playing item requires the full prepare cascade.
            if (at == currentMediaIndex) return false
            withContext(Dispatchers.Main.immediate) {
                player.removeMediaItem(at)
            }
            return true
        }

        // Single move: same size, one entry slid from `from` to `to`.
        if (newIds.size == playerSnapshot.size) {
            val firstDiff = newIds.indices.firstOrNull { playerSnapshot[it] != newIds[it] } ?: return true
            val movedEntryId = playerSnapshot[firstDiff]
            val newPos = newIds.indexOf(movedEntryId)
            if (newPos == -1) return false
            val rebuilt =
                playerSnapshot.toMutableList().apply {
                    removeAt(firstDiff)
                    add(newPos, movedEntryId)
                }
            if (rebuilt != newIds) return false
            if (firstDiff == currentMediaIndex || newPos == currentMediaIndex) return false
            withContext(Dispatchers.Main.immediate) {
                player.moveMediaItem(firstDiff, newPos)
            }
            return true
        }

        return false
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
     * Loads album-art bytes via our own ContentResolver. Used to inject art into the
     * platform notification metadata when the OEM bitmap loader can't reach our URIs.
     * Called lazily for the current song only — loading bytes for the entire queue
     * would block syncPlayer for tens of seconds when the queue is large.
     */
    private suspend fun loadArtworkBytes(uri: String): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                applicationContext.contentResolver.openInputStream(uri.toUri())?.use { it.readBytes() }
            }.getOrNull()
        }

    /**
     * Loads the album art for whatever song is currently playing and replaces the
     * live MediaItem's metadata with the embedded bytes. The OEM lockscreen / notification
     * surface reads bytes directly via the MediaSession instead of opening our content URI,
     * which on this OnePlus device fails with "artworkBitmap is null".
     */
    private fun embedArtworkForCurrent() {
        scope.launch {
            val state = queueEngine.currentState() ?: return@launch
            val currentEntry = state.currentItem
            if (currentEntry.entryId == lastArtworkEntryId) return@launch

            val song = libraryRepository.getSongsByIds(listOf(currentEntry.songId)).firstOrNull() ?: return@launch
            val artUri =
                song.albumArtUri ?: run {
                    lastArtworkEntryId = currentEntry.entryId
                    return@launch
                }
            val artBytes = loadArtworkBytes(artUri) ?: return@launch

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
