package com.migsmusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.migsmusic.playback.PlaybackSongUiModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
internal fun QueueRoute(
    playerViewModel: PlayerViewModel,
    playlistsViewModel: PlaylistsViewModel,
) {
    val state by playerViewModel.playbackUiState.collectAsStateWithLifecycle()
    val lazyListState = rememberLazyListState()
    var saveDialogVisible by remember { mutableStateOf(false) }
    if (saveDialogVisible) {
        NameDialog(
            title = "Save queue as playlist",
            initialValue = "",
            onDismiss = { saveDialogVisible = false },
            onConfirm = { name ->
                val ids = buildList {
                    addAll(state.history.map { it.songId })
                    state.currentSong?.let { add(it.songId) }
                    addAll(state.upcoming.map { it.songId })
                }
                playlistsViewModel.createPlaylistFromSongs(name, ids)
                saveDialogVisible = false
            },
        )
    }

    // Compute the LazyColumn index where upcoming items start so the reorderable callback
    // can translate full-list indices back to upcoming-sublist indices.
    val headerCount = 1
    val historyCount = if (state.history.isNotEmpty()) 1 + state.history.size else 0
    val currentCount = if (state.currentSong != null) 2 else 0
    val upNextHeaderCount = if (state.upcoming.isNotEmpty()) 1 else 0
    val upcomingStart = headerCount + historyCount + currentCount + upNextHeaderCount
    val upcoming = state.upcoming

    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromIdx = from.index - upcomingStart
        val toIdx = to.index - upcomingStart
        if (fromIdx in upcoming.indices && toIdx in upcoming.indices && fromIdx != toIdx) {
            playerViewModel.moveUpcoming(upcoming[fromIdx].entryId, toIdx)
        }
    }

    // Auto-scroll to the Current row once on entry. Re-running on every entryId change
    // (e.g. rapid skip-next) re-enters scrollToItem during measurement → layout-cycle crash.
    val currentRowIndex = if (state.currentSong != null) headerCount + historyCount else -1
    var didInitialQueueScroll by remember { mutableStateOf(false) }
    LaunchedEffect(state.currentSong?.entryId) {
        if (didInitialQueueScroll || currentRowIndex < 0) return@LaunchedEffect
        val visible = lazyListState.layoutInfo.visibleItemsInfo
        val firstVisible = visible.firstOrNull()?.index ?: -1
        val lastVisible = visible.lastOrNull()?.index ?: -1
        if (currentRowIndex !in firstVisible..lastVisible) {
            lazyListState.scrollToItem(currentRowIndex.coerceAtLeast(0))
        }
        didInitialQueueScroll = true
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTestTags.QueueScreen),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Now Playing Queue",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.currentSong != null || state.upcoming.isNotEmpty() || state.history.isNotEmpty()) {
                            TextButton(
                                modifier = Modifier.testTag(UiTestTags.QueueSaveAsPlaylist),
                                onClick = { saveDialogVisible = true },
                            ) {
                                Text("Save")
                            }
                        }
                        if (state.upcoming.isNotEmpty()) {
                            TextButton(
                                modifier = Modifier.testTag(UiTestTags.QueueClear),
                                onClick = playerViewModel::clearUpcoming,
                            ) {
                                Text("Clear Up Next")
                            }
                        }
                    }
                }
                if (state.upcoming.isNotEmpty()) {
                    Text(
                        text = "Up next: " + formatCountAndDuration(
                            state.upcoming.size,
                            state.upcoming.sumOf { it.durationMs },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (state.history.isEmpty() && state.currentSong == null && state.upcoming.isEmpty()) {
            item {
                EmptyState(
                    text = "Nothing playing.\nTap a song to start.",
                    fillSize = false,
                )
            }
        }
        if (state.history.isNotEmpty()) {
            item {
                SectionHeader("History")
            }
            itemsIndexed(state.history, key = { _, item -> item.entryId }) { _, song ->
                QueueRow(
                    song = song,
                    onJump = { playerViewModel.jumpToEntry(song.entryId) },
                    rowTag = UiTestTags.QueueHistoryRow,
                )
            }
        }
        state.currentSong?.let { current ->
            item { SectionHeader("Current") }
            item {
                QueueRow(
                    song = current,
                    onJump = {},
                    highlight = true,
                    rowTag = UiTestTags.QueueCurrentRow,
                )
            }
        }
        if (state.upcoming.isNotEmpty()) {
            item { SectionHeader("Up Next") }
            itemsIndexed(state.upcoming, key = { _, item -> item.entryId }) { index, song ->
                val lastIndex = state.upcoming.lastIndex
                val onJump = remember(song.entryId) { { playerViewModel.jumpToEntry(song.entryId) } }
                val onRemove = remember(song.entryId) { { playerViewModel.removeUpcoming(song.entryId) } }
                val onMoveUp = remember(song.entryId, index) {
                    {
                        if (index > 0) playerViewModel.moveUpcoming(song.entryId, index - 1)
                    }
                }
                val onMoveDown = remember(song.entryId, index, lastIndex) {
                    {
                        if (index < lastIndex) playerViewModel.moveUpcoming(song.entryId, index + 1)
                    }
                }
                val rowTag = remember(index) { UiTestTags.queueUpcomingRow(index) }
                ReorderableItem(reorderState, key = song.entryId) { isDragging ->
                    // Note: SwipeToDismissBox + ReorderableItem nested together causes a Compose
                    // layout-cycle crash during rapid list updates. We use the trash IconButton
                    // for remove and the drag handle for reorder — no swipe-to-remove on queue rows.
                    QueueRow(
                        song = song,
                        onJump = onJump,
                        onRemove = onRemove,
                        onMoveUp = onMoveUp,
                        onMoveDown = onMoveDown,
                        canMoveUp = index > 0,
                        canMoveDown = index < lastIndex,
                        rowTag = rowTag,
                        dragHandleModifier = Modifier.draggableHandle(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun QueueRow(
    song: PlaybackSongUiModel,
    onJump: () -> Unit,
    onRemove: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    highlight: Boolean = false,
    rowTag: String? = null,
    dragHandleModifier: Modifier? = null,
) {
    val baseModifier = if (rowTag != null) {
        Modifier.testTag(rowTag)
    } else {
        Modifier
    }
    ListRow(
        title = song.title,
        subtitle = song.artist,
        modifier = baseModifier.clickable(onClick = onJump),
        containerColor = if (highlight) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        actions = {
            if (onMoveUp != null) {
                SmallActionButton(
                    label = "Up",
                    modifier = Modifier.testTag(UiTestTags.QueueRowMoveUp),
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                )
            }
            if (onMoveDown != null) {
                SmallActionButton(
                    label = "Down",
                    modifier = Modifier.testTag(UiTestTags.QueueRowMoveDown),
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                )
            }
            if (onRemove != null) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.testTag(UiTestTags.QueueRowRemove),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove from queue")
                }
            }
            if (dragHandleModifier != null) {
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = "Drag to reorder",
                    modifier = dragHandleModifier
                        .testTag(UiTestTags.QueueRowDragHandle)
                        .padding(8.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
