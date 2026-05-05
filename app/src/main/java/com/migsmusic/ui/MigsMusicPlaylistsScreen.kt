package com.migsmusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.migsmusic.data.local.model.PlaylistSong
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
internal fun PlaylistsRoute(
    playlistsViewModel: PlaylistsViewModel,
    onOpenPlaylist: (Long) -> Unit,
) {
    val playlists by playlistsViewModel.playlists.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableLongStateOf(-1L) }

    if (showDialog) {
        NameDialog(
            title = "Create playlist",
            onDismiss = { showDialog = false },
            onConfirm = {
                playlistsViewModel.createPlaylist(it)
                showDialog = false
            },
        )
    }

    if (renameTarget != -1L) {
        val existingName = playlists.firstOrNull { it.id == renameTarget }?.name.orEmpty()
        NameDialog(
            title = "Rename playlist",
            initialValue = existingName,
            onDismiss = { renameTarget = -1L },
            onConfirm = {
                playlistsViewModel.renamePlaylist(renameTarget, it)
                renameTarget = -1L
            },
        )
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showDialog = true },
                modifier = Modifier.testTag(UiTestTags.PlaylistFab),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create playlist")
            }
        }
    ) { innerPadding ->
        if (playlists.isEmpty()) {
            Box(modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag(UiTestTags.PlaylistsScreen)
            ) {
                EmptyState("No playlists yet.\nTap + to create one.")
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag(UiTestTags.PlaylistsScreen),
        ) {
            itemsIndexed(playlists, key = { _, item -> item.id }) { _, playlist ->
                ListRow(
                    title = playlist.name,
                    subtitle = "${playlist.songCount} songs",
                    modifier = Modifier
                        .testTag(UiTestTags.PlaylistRow)
                        .clickable { onOpenPlaylist(playlist.id) },
                    actions = {
                        SmallActionButton(
                            label = "Rename",
                            modifier = Modifier.testTag(UiTestTags.PlaylistRenameButton),
                            onClick = { renameTarget = playlist.id },
                        )
                        IconButton(
                            onClick = { playlistsViewModel.deletePlaylist(playlist.id) },
                            modifier = Modifier.testTag(UiTestTags.PlaylistDeleteButton),
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete playlist")
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
internal fun PlaylistDetailRoute(
    playlistId: Long,
    playlistsViewModel: PlaylistsViewModel,
    currentSongId: Long?,
) {
    // playlistSongs() creates a new StateFlow each call; remember by id so we don't
    // spin a recomposition loop (the new flow re-emits emptyList -> recompose -> new flow ...).
    val songsFlow = remember(playlistId) { playlistsViewModel.playlistSongs(playlistId) }
    val songs by songsFlow.collectAsStateWithLifecycle()
    val playlists by playlistsViewModel.playlists.collectAsStateWithLifecycle()
    var renameDialogVisible by remember { mutableStateOf(false) }

    if (renameDialogVisible) {
        NameDialog(
            title = "Rename playlist",
            initialValue = playlists.firstOrNull { it.id == playlistId }?.name.orEmpty(),
            onDismiss = { renameDialogVisible = false },
            onConfirm = {
                playlistsViewModel.renamePlaylist(playlistId, it)
                renameDialogVisible = false
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { renameDialogVisible = true },
                modifier = Modifier.testTag(UiTestTags.PlaylistDetailRename),
            ) {
                Text("Rename")
            }
            Button(
                onClick = { playlistsViewModel.playPlaylist(songs, 0, shuffle = false) },
                enabled = songs.isNotEmpty(),
                modifier = Modifier.testTag(UiTestTags.PlaylistDetailPlay),
            ) {
                Text("Play Playlist")
            }
            Button(
                onClick = { playlistsViewModel.playPlaylist(songs, 0, shuffle = true) },
                enabled = songs.isNotEmpty(),
                modifier = Modifier.testTag(UiTestTags.PlaylistDetailShuffle),
            ) {
                Text("Shuffle Playlist")
            }
        }
        if (songs.isNotEmpty()) {
            Text(
                text = formatCountAndDuration(songs.size, songs.sumOf { it.durationMs }),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (songs.isEmpty()) {
            EmptyState("This playlist is empty.\nAdd songs from the Songs tab.")
            return@Column
        }
        val playlistLazyState = rememberLazyListState()
        val playlistReorderState = rememberReorderableLazyListState(playlistLazyState) { from, to ->
            if (from.index in songs.indices && to.index in songs.indices && from.index != to.index) {
                playlistsViewModel.moveSong(playlistId, from.index, to.index)
            }
        }
        LazyColumn(state = playlistLazyState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(songs, key = { _, item -> item.playlistItemId }) { index, song ->
                val onPlay = remember(index, songs) {
                    { playlistsViewModel.playPlaylist(songs, index, shuffle = false) }
                }
                val onRemove = remember(song.playlistItemId) {
                    { playlistsViewModel.removeSong(song.playlistItemId) }
                }
                val onNext = remember(song.songId) {
                    { playlistsViewModel.addSongToQueueNext(song.songId) }
                }
                val onLater = remember(song.songId) {
                    { playlistsViewModel.addSongToQueueLater(song.songId) }
                }
                val onMoveUp = remember(index, playlistId) {
                    { playlistsViewModel.moveSong(playlistId, index, index - 1) }
                }
                val onMoveDown = remember(index, playlistId) {
                    { playlistsViewModel.moveSong(playlistId, index, index + 1) }
                }
                ReorderableItem(playlistReorderState, key = song.playlistItemId) { _ ->
                    PlaylistSongRow(
                        song = song,
                        isCurrent = song.songId == currentSongId,
                        onPlay = onPlay,
                        onRemove = onRemove,
                        onPlayNext = onNext,
                        onPlayLater = onLater,
                        onMoveUp = onMoveUp,
                        onMoveDown = onMoveDown,
                        canMoveUp = index > 0,
                        canMoveDown = index < songs.lastIndex,
                        dragHandleModifier = Modifier.draggableHandle(),
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
internal fun PlaylistSongRow(
    song: PlaylistSong,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayLater: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    dragHandleModifier: Modifier? = null,
) {
    ListRow(
        title = song.title,
        subtitle = "${song.artist} • ${song.album}",
        modifier = Modifier
            .testTag(UiTestTags.PlaylistSongRow)
            .clickable(onClick = onPlay),
        containerColor = if (isCurrent) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        titleFontWeight = if (isCurrent) FontWeight.Bold else null,
        leading = {
            AlbumArtImage(
                uri = song.albumArtUri,
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.small),
            )
        },
        actions = {
            Row {
                SmallActionButton(
                    label = "Up",
                    modifier = Modifier.testTag(UiTestTags.PlaylistSongMoveUp),
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                )
                SmallActionButton(
                    label = "Down",
                    modifier = Modifier.testTag(UiTestTags.PlaylistSongMoveDown),
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                )
                SmallActionButton(label = "Next", onClick = onPlayNext)
                SmallActionButton(label = "Later", onClick = onPlayLater)
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.testTag(UiTestTags.PlaylistSongRemove),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove song")
                }
                if (dragHandleModifier != null) {
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = "Drag to reorder",
                        modifier = dragHandleModifier.padding(8.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}
