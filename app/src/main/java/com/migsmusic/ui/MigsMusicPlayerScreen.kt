package com.migsmusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.migsmusic.playback.PlaybackUiState
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun PlayerRoute(
    playerViewModel: PlayerViewModel,
    playlistsViewModel: PlaylistsViewModel,
    onOpenQueue: () -> Unit,
    onDismiss: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (album: String, artist: String) -> Unit,
) {
    val state by playerViewModel.playbackUiState.collectAsStateWithLifecycle()
    val currentPositionMs by playerViewModel.currentPositionMs.collectAsStateWithLifecycle()
    val currentSong = state.currentSong
    val openAddToPlaylist = rememberAddToPlaylistTrigger(playlistsViewModel)
    val snackbar = LocalSnackbarController.current
    var saveQueueDialogOpen by remember { mutableStateOf(false) }
    var contextMenuOpen by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember(state.currentSong?.entryId) { mutableFloatStateOf(0f) }
    // While the user is dragging the slider, show their finger position; otherwise track the
    // live playback clock. derivedStateOf keeps this in the snapshot graph — no per-tick
    // LaunchedEffect re-run just to copy a Float.
    val sliderPosition by remember(state.currentSong?.entryId) {
        derivedStateOf { if (dragging) dragValue else currentPositionMs.toFloat() }
    }

    // Thresholds: drag down >= 120dp dismisses; left/right >= 80dp skips track. We commit on
    // crossing the threshold (no rubber-band animation; simpler + avoids fighting the sliders below).
    val dismissThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }
    val skipThresholdPx = with(LocalDensity.current) { 80.dp.toPx() }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragCommitted by remember { mutableStateOf(false) }

    if (saveQueueDialogOpen) {
        NameDialog(
            title = "Save queue as playlist",
            initialValue = "",
            onDismiss = { saveQueueDialogOpen = false },
            onConfirm = { name ->
                val ids =
                    buildList {
                        addAll(state.history.map { it.songId })
                        state.currentSong?.let { add(it.songId) }
                        addAll(state.upcoming.map { it.songId })
                    }
                playlistsViewModel.createPlaylistFromSongs(name, ids)
                saveQueueDialogOpen = false
                snackbar.show("Saved as “$name”")
            },
        )
    }

    // Drag gestures are scoped to the album art only, NOT the whole screen. Previously they
    // sat on the outer Box, which meant a horizontal drag starting near the Slider's edge
    // could be claimed by the outer detector before the Slider — slider stayed at its old
    // value while the queue advanced. Restricting drag to the album-art region keeps the
    // "swipe to skip / dismiss" UX without competing with the slider hit area.
    val albumArtDragModifier =
        Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = {
                    dragX = 0f
                    dragY = 0f
                    dragCommitted = false
                },
                onDragEnd = {
                    dragX = 0f
                    dragY = 0f
                    dragCommitted = false
                },
                onDragCancel = {
                    dragX = 0f
                    dragY = 0f
                    dragCommitted = false
                },
                onDrag = { _, drag ->
                    if (dragCommitted) return@detectDragGestures
                    dragX += drag.x
                    dragY += drag.y
                    // Pick whichever axis crossed its threshold first.
                    when {
                        dragY >= dismissThresholdPx && dragY > kotlin.math.abs(dragX) -> {
                            dragCommitted = true
                            onDismiss()
                        }
                        dragX <= -skipThresholdPx && kotlin.math.abs(dragX) > kotlin.math.abs(dragY) -> {
                            dragCommitted = true
                            playerViewModel.skipToNext()
                        }
                        dragX >= skipThresholdPx && kotlin.math.abs(dragX) > kotlin.math.abs(dragY) -> {
                            dragCommitted = true
                            playerViewModel.skipToPrevious()
                        }
                    }
                },
            )
        }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .testTag(UiTestTags.PlayerScreen)
                // Long-press anywhere on the player opens the context menu. detectTapGestures
                // doesn't claim drags, so this happily coexists with the Slider below and the
                // album-art drag handler above.
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { contextMenuOpen = true },
                    )
                },
    ) {
        DropdownMenu(
            expanded = contextMenuOpen,
            onDismissRequest = { contextMenuOpen = false },
            modifier = Modifier.testTag(UiTestTags.PlayerContextMenu),
        ) {
            DropdownMenuItem(
                text = { Text("Save queue as playlist") },
                onClick = {
                    contextMenuOpen = false
                    saveQueueDialogOpen = true
                },
                modifier = Modifier.testTag(UiTestTags.PlayerActionSaveQueueAsPlaylist),
            )
            currentSong?.let { song ->
                DropdownMenuItem(
                    text = { Text("Add to playlist…") },
                    onClick = {
                        contextMenuOpen = false
                        openAddToPlaylist(song.songId)
                    },
                    modifier = Modifier.testTag(UiTestTags.PlayerActionAddToPlaylist),
                )
                DropdownMenuItem(
                    text = { Text("Go to album") },
                    onClick = {
                        contextMenuOpen = false
                        onOpenAlbum(song.album, song.artist)
                    },
                    modifier = Modifier.testTag(UiTestTags.PlayerActionGoToAlbum),
                )
                DropdownMenuItem(
                    text = { Text("Go to artist") },
                    onClick = {
                        contextMenuOpen = false
                        onOpenArtist(song.artist)
                    },
                    modifier = Modifier.testTag(UiTestTags.PlayerActionGoToArtist),
                )
            }
        }
        if (currentSong == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Nothing is playing yet.",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            return@Box
        }

        // Layout: album art fills the available area at the top; controls hug the bottom.
        // The art's aspectRatio(1f) keeps it square — a too-wide screen leaves vertical
        // breathing room above/below the art rather than cropping it.
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                AlbumArtImage(
                    uri = currentSong.albumArtUri,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.large)
                            .then(albumArtDragModifier)
                            .testTag(UiTestTags.PlayerAlbumArt),
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = currentSong.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = currentSong.artist,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary,
                    modifier =
                        Modifier
                            .testTag(UiTestTags.PlayerArtistLink)
                            .clickable { onOpenArtist(currentSong.artist) },
                )
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = currentSong.album,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary,
                    modifier =
                        Modifier
                            .weight(1f, fill = false)
                            .testTag(UiTestTags.PlayerAlbumLink)
                            .clickable { onOpenAlbum(currentSong.album, currentSong.artist) },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Cache the float-coerced duration so the Slider's valueRange object is only
            // re-allocated when durationMs actually changes — without this remember,
            // every recomposition (twice per second under the position ticker) builds a
            // fresh ClosedFloatingPointRange, forcing the slider to re-measure.
            val durationFloat by remember(state.durationMs) {
                mutableStateOf(state.durationMs.coerceAtLeast(1L).toFloat())
            }
            Slider(
                value = sliderPosition.coerceIn(0f, durationFloat),
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    playerViewModel.seekTo(dragValue.toLong())
                    dragging = false
                },
                valueRange = 0f..durationFloat,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatDuration(sliderPosition.toLong()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatDuration(state.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            val shuffleOn by playerViewModel.shuffleEnabled.collectAsStateWithLifecycle()
            // Main transport row: shuffle, prev, big play/pause, next, repeat. Shuffle and
            // repeat tint primary when active, surface-variant when not — the highlight is
            // the only signal of state, so it has to read at a glance.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = playerViewModel::toggleShuffle,
                    modifier = Modifier.testTag(UiTestTags.PlayerShuffle),
                ) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = if (shuffleOn) "Shuffle on" else "Shuffle off",
                        tint =
                            if (shuffleOn) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
                IconButton(
                    onClick = playerViewModel::skipToPrevious,
                    modifier = Modifier.testTag(UiTestTags.PlayerPrevious),
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        modifier = Modifier.size(36.dp),
                    )
                }
                FilledIconButton(
                    onClick = playerViewModel::togglePlayPause,
                    modifier =
                        Modifier
                            .size(72.dp)
                            .testTag(UiTestTags.PlayerPlayPause),
                    colors =
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(
                    onClick = playerViewModel::skipToNext,
                    modifier = Modifier.testTag(UiTestTags.PlayerNext),
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Next",
                        modifier = Modifier.size(36.dp),
                    )
                }
                // Repeat icon: greyed when off, primary when one or all. Uses RepeatOne for
                // single-track repeat (the "1" badge embedded in the icon makes the mode
                // glanceable without a label).
                val repeatIcon =
                    if (state.repeatMode == 1) Icons.Default.RepeatOne else Icons.Default.Repeat
                val repeatActive = state.repeatMode != 0
                IconButton(
                    onClick = playerViewModel::cycleRepeatMode,
                    modifier = Modifier.testTag(UiTestTags.PlayerRepeat),
                ) {
                    Icon(
                        repeatIcon,
                        contentDescription = "Repeat",
                        tint =
                            if (repeatActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Secondary row: queue access only. Right-aligned so it doesn't compete with
            // the transport row visually. "Clear Up Next" lives on the queue screen now —
            // not a daily action, so a button on the player was overkill.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(
                    onClick = onOpenQueue,
                    modifier = Modifier.testTag(UiTestTags.PlayerOpenQueue),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "Open queue",
                    )
                }
            }
        }
    }
}

@Composable
internal fun MiniPlayer(
    playbackState: PlaybackUiState,
    positionFlow: StateFlow<Long>,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val current = playbackState.currentSong ?: return
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag(UiTestTags.MiniPlayer)
                .clickable(onClick = onOpenPlayer),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        // Thin progress indicator at the top — observes the position flow in isolation so
        // the rest of MiniPlayer doesn't recompose on every 500ms tick.
        MiniPlayerProgressBar(
            positionFlow = positionFlow,
            durationMs = playbackState.durationMs.takeIf { it > 0 } ?: current.durationMs,
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumArtImage(
                uri = current.albumArtUri,
                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(MaterialTheme.shapes.small),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = current.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = current.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(
                modifier = Modifier.testTag(UiTestTags.MiniPlayerPrevious),
                onClick = onSkipPrevious,
            ) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous")
            }
            IconButton(
                modifier = Modifier.testTag(UiTestTags.MiniPlayerPlayPause),
                onClick = onTogglePlayPause,
            ) {
                Icon(
                    if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                )
            }
            IconButton(
                modifier = Modifier.testTag(UiTestTags.MiniPlayerNext),
                onClick = onSkipNext,
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next")
            }
        }
    }
}

@Composable
internal fun MiniPlayerProgressBar(
    positionFlow: StateFlow<Long>,
    durationMs: Long,
) {
    val position by positionFlow.collectAsStateWithLifecycle()
    // Static (non-animated) progress bar — keeps Espresso idle predictable across tests.
    // M3 LinearProgressIndicator runs an internal animation that makes Compose perpetually
    // busy, which broke unrelated UI tests when playback was active.
    // Painted via drawBehind so 500ms position updates stay in the draw phase only — no
    // layout pass per tick (which the previous fillMaxWidth(progress) approach forced).
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val fillColor = MaterialTheme.colorScheme.primary
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .testTag(UiTestTags.MiniPlayerProgress)
                .drawBehind {
                    drawRect(color = trackColor)
                    val progress =
                        if (durationMs > 0) {
                            (position.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                    if (progress > 0f) {
                        drawRect(
                            color = fillColor,
                            topLeft = Offset.Zero,
                            size = Size(width = size.width * progress, height = size.height),
                        )
                    }
                },
    )
}
