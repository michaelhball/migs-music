package com.migsmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    onOpenQueue: () -> Unit,
    onDismiss: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (album: String, artist: String) -> Unit,
) {
    val state by playerViewModel.playbackUiState.collectAsStateWithLifecycle()
    val currentPositionMs by playerViewModel.currentPositionMs.collectAsStateWithLifecycle()
    val currentSong = state.currentSong
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTestTags.PlayerScreen)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        dragX = 0f; dragY = 0f; dragCommitted = false
                    },
                    onDragEnd = {
                        dragX = 0f; dragY = 0f; dragCommitted = false
                    },
                    onDragCancel = {
                        dragX = 0f; dragY = 0f; dragCommitted = false
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
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (currentSong == null) {
            Text(
                text = "Nothing is playing yet.",
                style = MaterialTheme.typography.titleMedium,
            )
            return@Box
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AlbumArtImage(
                uri = currentSong.albumArtUri,
                modifier = Modifier
                    .size(220.dp)
                    .clip(MaterialTheme.shapes.large)
                    .testTag(UiTestTags.PlayerAlbumArt),
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = currentSong.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = currentSong.artist,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .testTag(UiTestTags.PlayerArtistLink)
                            .clickable { onOpenArtist(currentSong.artist) },
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = currentSong.album,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .testTag(UiTestTags.PlayerAlbumLink)
                            .clickable { onOpenAlbum(currentSong.album, currentSong.artist) },
                    )
                }
            }
            Slider(
                value = sliderPosition.coerceIn(0f, state.durationMs.coerceAtLeast(1L).toFloat()),
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    playerViewModel.seekTo(dragValue.toLong())
                    dragging = false
                },
                valueRange = 0f..state.durationMs.coerceAtLeast(1L).toFloat(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatDuration(sliderPosition.toLong()))
                Text(formatDuration(state.durationMs))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = playerViewModel::skipToPrevious,
                    modifier = Modifier.testTag(UiTestTags.PlayerPrevious),
                ) {
                    Text("Prev")
                }
                Button(
                    modifier = Modifier.testTag(UiTestTags.PlayerPlayPause),
                    onClick = playerViewModel::togglePlayPause,
                ) {
                    Text(if (state.isPlaying) "Pause" else "Play")
                }
                Button(
                    onClick = playerViewModel::skipToNext,
                    modifier = Modifier.testTag(UiTestTags.PlayerNext),
                ) {
                    Text("Next")
                }
            }
            val shuffleOn by playerViewModel.shuffleEnabled.collectAsStateWithLifecycle()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = playerViewModel::toggleShuffle,
                    modifier = Modifier.testTag(UiTestTags.PlayerShuffle),
                ) {
                    Text(if (shuffleOn) "Shuffle: On" else "Shuffle: Off")
                }
                TextButton(
                    onClick = playerViewModel::cycleRepeatMode,
                    modifier = Modifier.testTag(UiTestTags.PlayerRepeat),
                ) {
                    Text("Repeat: ${repeatModeLabel(state.repeatMode)}")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (state.upcoming.isNotEmpty()) {
                    TextButton(onClick = playerViewModel::clearUpcoming) {
                        Text("Clear Up Next")
                    }
                }
                TextButton(
                    onClick = onOpenQueue,
                    modifier = Modifier.testTag(UiTestTags.PlayerOpenQueue),
                ) {
                    Text("Open Queue")
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
        modifier = Modifier
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumArtImage(
                uri = current.albumArtUri,
                modifier = Modifier
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
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .testTag(UiTestTags.MiniPlayerProgress)
            .drawBehind {
                drawRect(color = trackColor)
                val progress = if (durationMs > 0) {
                    (position.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f
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
