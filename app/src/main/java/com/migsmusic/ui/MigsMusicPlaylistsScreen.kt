package com.migsmusic.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
    val importStaging by playlistsViewModel.importStaging.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableLongStateOf(-1L) }
    var overflowOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            // Read the file synchronously here; it's a small text file. Pass parsed content
            // (not the URI) to the VM so the VM stays Android-context-free.
            val content =
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                }.getOrNull().orEmpty()
            if (content.isBlank()) return@rememberLauncherForActivityResult
            val defaultName = filenameFromUri(uri).removeSuffix(".m3u").removeSuffix(".m3u8")
            playlistsViewModel.previewM3uImport(content, defaultName)
        }

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

    importStaging?.let { staging ->
        M3uImportDialog(
            staging = staging,
            onCancel = { playlistsViewModel.cancelM3uImport() },
            onConfirm = { name -> playlistsViewModel.commitM3uImport(name) },
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
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .testTag(UiTestTags.PlaylistsScreen),
        ) {
            // Overflow ⋮ in the screen header — host for "Import from M3U file". Only the
            // import action lives here for now; "New playlist" remains the FAB so the
            // primary action stays one tap away.
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Box {
                    IconButton(
                        onClick = { overflowOpen = true },
                        modifier = Modifier.testTag(UiTestTags.PlaylistOverflow),
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = overflowOpen,
                        onDismissRequest = { overflowOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Import from M3U file") },
                            onClick = {
                                overflowOpen = false
                                importLauncher.launch(
                                    arrayOf(
                                        "audio/x-mpegurl",
                                        "audio/mpegurl",
                                        "application/vnd.apple.mpegurl",
                                        "application/x-mpegurl",
                                        "*/*",
                                    ),
                                )
                            },
                            modifier = Modifier.testTag(UiTestTags.PlaylistImportFromM3u),
                        )
                    }
                }
            }
            if (playlists.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    EmptyState("No playlists yet.\nTap + to create one.")
                }
                return@Column
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(playlists, key = { _, item -> item.id }) { _, playlist ->
                    ListRow(
                        title = playlist.name,
                        subtitle = "${playlist.songCount} songs",
                        modifier =
                            Modifier
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
}

@Composable
private fun M3uImportDialog(
    staging: M3uImportStaging,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(staging) { mutableStateOf(staging.defaultName) }
    var unmatchedExpanded by remember(staging) { mutableStateOf(false) }
    val canConfirm = staging.matched.isNotEmpty() && name.isNotBlank()

    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag(UiTestTags.M3uImportDialog),
        title = { Text("Import playlist") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Playlist name") },
                    singleLine = true,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(UiTestTags.M3uImportNameField),
                )
                Text(
                    text = "Found ${staging.matched.size} of ${staging.matched.size + staging.unmatched.size} songs in your library.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (staging.unmatched.isNotEmpty()) {
                    TextButton(
                        onClick = { unmatchedExpanded = !unmatchedExpanded },
                        modifier = Modifier.testTag(UiTestTags.M3uImportUnmatchedToggle),
                    ) {
                        Text(
                            if (unmatchedExpanded) {
                                "Hide unmatched"
                            } else {
                                "Show ${staging.unmatched.size} unmatched"
                            },
                        )
                    }
                    if (unmatchedExpanded) {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            staging.unmatched.forEach { entry ->
                                val label =
                                    when {
                                        !entry.artist.isNullOrBlank() && !entry.title.isNullOrBlank() ->
                                            "${entry.artist} — ${entry.title}"
                                        !entry.title.isNullOrBlank() -> entry.title
                                        else -> entry.rawPath.substringAfterLast('/').substringAfterLast('\\')
                                    }
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Copy these MP3s to your phone and re-import to add them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = canConfirm,
                modifier = Modifier.testTag(UiTestTags.M3uImportConfirm),
            ) {
                Text("Create Playlist")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag(UiTestTags.M3uImportCancel),
            ) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Best-effort filename extraction from a content URI. SAF returns opaque content URIs whose
 * `lastPathSegment` typically looks like `primary:Music/MyPlaylist.m3u` — we just take the
 * last segment after `/`. Falls back to "Imported playlist" if anything is missing.
 */
private fun filenameFromUri(uri: Uri): String {
    val raw = uri.lastPathSegment.orEmpty().substringAfterLast('/').substringAfterLast(':')
    return raw.ifBlank { "Imported playlist" }
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
            modifier =
                Modifier
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
        val playlistReorderState =
            rememberReorderableLazyListState(playlistLazyState) { from, to ->
                if (from.index in songs.indices && to.index in songs.indices && from.index != to.index) {
                    playlistsViewModel.moveSong(playlistId, from.index, to.index)
                }
            }
        LazyColumn(state = playlistLazyState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(songs, key = { _, item -> item.playlistItemId }) { index, song ->
                val onPlay =
                    remember(index, songs) {
                        { playlistsViewModel.playPlaylist(songs, index, shuffle = false) }
                    }
                val onRemove =
                    remember(song.playlistItemId) {
                        { playlistsViewModel.removeSong(song.playlistItemId) }
                    }
                val onNext =
                    remember(song.songId) {
                        { playlistsViewModel.addSongToQueueNext(song.songId) }
                    }
                val onLater =
                    remember(song.songId) {
                        { playlistsViewModel.addSongToQueueLater(song.songId) }
                    }
                val onMoveUp =
                    remember(index, playlistId) {
                        { playlistsViewModel.moveSong(playlistId, index, index - 1) }
                    }
                val onMoveDown =
                    remember(index, playlistId) {
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
        modifier =
            Modifier
                .testTag(UiTestTags.PlaylistSongRow)
                .clickable(onClick = onPlay),
        containerColor =
            if (isCurrent) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        titleFontWeight = if (isCurrent) FontWeight.Bold else null,
        leading = {
            AlbumArtImage(
                uri = song.albumArtUri,
                modifier =
                    Modifier
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
