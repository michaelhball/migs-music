package com.migsmusic.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    val musicFolderUri by playlistsViewModel.musicFolderUri.collectAsStateWithLifecycle()
    val availableM3uFiles by playlistsViewModel.availableM3uFiles.collectAsStateWithLifecycle()
    val playlistSortOrder by playlistsViewModel.playlistSortOrder.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableLongStateOf(-1L) }
    val context = LocalContext.current
    val snackbar = LocalSnackbarController.current

    // Surface auto-import errors via the app-wide snackbar. The ViewModel emits one event
    // per refresh that found problems (parse, IO, SAF revocation). "No matches" is filtered
    // out — those just live in the AvailableM3uList below.
    LaunchedEffect(playlistsViewModel) {
        playlistsViewModel.importErrors.collect { snackbar.show(it) }
    }

    val folderPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            // SAF grants are per-launch by default. takePersistableUriPermission keeps the
            // grant across process restarts. We need WRITE in addition to READ so the
            // auto-import flow can delete .m3u files after successfully importing them.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            playlistsViewModel.setMusicFolderUri(uri)
        }

    // Re-scan whenever the granted folder changes (initial load, fresh grant) and on every
    // entry to the Playlists tab. The ViewModel will no-op if the URI is null.
    //
    // Also re-asserts READ + WRITE persistence on the existing URI so users who granted
    // READ-only access in an earlier app version get upgraded silently — auto-import
    // needs write to delete consumed .m3u files. takePersistableUriPermission is
    // idempotent and overwrites prior flag sets.
    LaunchedEffect(musicFolderUri) {
        musicFolderUri?.let { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        playlistsViewModel.refreshAvailableM3uFiles(context, musicFolderUri)
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

    // Nested Scaffold (inside the app's outer Scaffold + NavHost padding) would double-apply
    // system-bar insets. Keep just the FAB by using a Box and aligning the button bottom-end.
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag(UiTestTags.PlaylistsScreen),
        ) {
            // Sort menu, right-aligned. "New playlist" is the FAB; M3U files are auto-imported
            // by the Mac sync flow + the AvailableM3uList fallback below, so no overflow menu
            // is needed.
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                SortMenu(
                    current = playlistSortOrder,
                    options = PlaylistSortOrder.entries,
                    labelOf = { it.label },
                    nameOf = { it.name },
                    onSelect = playlistsViewModel::setPlaylistSortOrder,
                )
            }
            // Auto-detected M3U files in the user's Music folder. Either:
            // - Folder not yet picked: show a one-time setup banner.
            // - Folder picked + scan found new M3Us: show "Available to import" section.
            // - Folder picked + nothing new: show nothing here (the user has the overflow ⋮
            //   "Import from M3U file" entry as a manual fallback).
            if (musicFolderUri == null) {
                M3uFolderSetupBanner(
                    onPickFolder = { folderPickerLauncher.launch(null) },
                )
            } else if (availableM3uFiles.isNotEmpty()) {
                AvailableM3uList(
                    files = availableM3uFiles,
                    onImport = { discovered ->
                        playlistsViewModel.importDiscoveredM3u(
                            context = context,
                            uri = discovered.uri,
                            defaultName = discovered.displayName.removeSuffix(".m3u").removeSuffix(".m3u8"),
                        )
                    },
                )
            }
            if (playlists.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    EmptyState("No playlists yet.\nTap + to create one.")
                }
                return@Column
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(playlists, key = { _, item -> item.id }) { _, playlist ->
                    PlaylistListRow(
                        playlist = playlist,
                        onOpen = { onOpenPlaylist(playlist.id) },
                        onRename = { renameTarget = playlist.id },
                        onDelete = { playlistsViewModel.deletePlaylist(playlist.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
        FloatingActionButton(
            onClick = { showDialog = true },
            modifier =
                Modifier
                    .align(androidx.compose.ui.Alignment.BottomEnd)
                    .padding(16.dp)
                    .testTag(UiTestTags.PlaylistFab),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Create playlist")
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

/**
 * One-time prompt shown when no Music folder URI has been granted yet. Tapping the button
 * opens the SAF folder picker; once the user picks a folder, the banner disappears and the
 * "Available imports" section takes its place.
 */
@Composable
private fun M3uFolderSetupBanner(onPickFolder: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(UiTestTags.M3uFolderSetupBanner),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Auto-detect playlists",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "Pick your Music folder once, and any .m3u files in it will appear here for one-tap import.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onPickFolder,
                modifier = Modifier.testTag(UiTestTags.M3uPickFolderButton),
            ) {
                Text("Pick Music folder")
            }
        }
    }
}

/**
 * The list of `.m3u` / `.m3u8` files found under the granted Music folder. One row per file;
 * tapping Import triggers the same dialog flow as the manual SAF picker.
 */
@Composable
private fun AvailableM3uList(
    files: List<com.migsmusic.playlistimport.DiscoveredM3u>,
    onImport: (com.migsmusic.playlistimport.DiscoveredM3u) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(UiTestTags.M3uAvailableSection),
    ) {
        SectionHeader("Available to import")
        files.forEach { file ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag(UiTestTags.M3uAvailableRow),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onImport(file) },
                    modifier = Modifier.testTag(UiTestTags.M3uAvailableImportButton),
                ) {
                    Text("Import")
                }
            }
        }
        // Visually separate the auto-detected files from the user's actual playlists below.
        // A simple divider + section header for the playlists makes the two groups distinct.
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        SectionHeader("Your playlists")
    }
}

@Composable
internal fun PlaylistDetailRoute(
    playlistId: Long,
    playlistsViewModel: PlaylistsViewModel,
    currentSongId: Long?,
    onGoBack: () -> Unit,
) {
    // Remember the cold Flow by playlistId so we keep the same subscription across
    // recompositions; collectAsStateWithLifecycle disposes it when the screen leaves.
    val songsFlow = remember(playlistId) { playlistsViewModel.playlistSongs(playlistId) }
    val songsRaw by songsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val playlists by playlistsViewModel.playlists.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarController.current
    var renameDialogVisible by remember { mutableStateOf(false) }
    // Display-only sort. DEFAULT preserves position order (the M3U import order, or the
    // order songs were added). Other options only change what's rendered — they don't mutate
    // playlist_songs.position, so picking DEFAULT always restores the canonical order.
    var contentSort by remember(playlistId) { mutableStateOf(PlaylistContentSortOrder.DEFAULT) }
    // Cache the sorted view; re-runs only when the upstream list reference or the chosen
    // sort changes. Comparators use String.CASE_INSENSITIVE_ORDER instead of `.lowercase()`
    // selectors to avoid per-comparison string allocation under frequent re-sorts.
    val songs =
        remember(songsRaw, contentSort) {
            val ci: Comparator<String> = String.CASE_INSENSITIVE_ORDER
            when (contentSort) {
                PlaylistContentSortOrder.DEFAULT -> songsRaw
                PlaylistContentSortOrder.TITLE_ASC ->
                    songsRaw.sortedWith(compareBy<PlaylistSong, String>(ci) { it.title })
                PlaylistContentSortOrder.TITLE_DESC ->
                    songsRaw.sortedWith(compareByDescending<PlaylistSong, String>(ci) { it.title })
                PlaylistContentSortOrder.ARTIST_ASC ->
                    songsRaw.sortedWith(compareBy<PlaylistSong, String>(ci) { it.artist }.thenBy(ci) { it.title })
                PlaylistContentSortOrder.DURATION_ASC -> songsRaw.sortedBy { it.durationMs }
                PlaylistContentSortOrder.DURATION_DESC -> songsRaw.sortedByDescending { it.durationMs }
            }
        }
    val canReorder = contentSort == PlaylistContentSortOrder.DEFAULT

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
        // Single compact header row: back, playlist name, action icons (play, shuffle,
        // rename, restore-order, sort), overflow menu. Replaces the previous three-row
        // layout (back + name, then full-text Buttons, then count) which ate ~30% of the
        // screen height before the song list got a single pixel.
        val playlistName = playlists.firstOrNull { it.id == playlistId }?.name.orEmpty()
        var headerOverflowOpen by remember { mutableStateOf(false) }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onGoBack,
                modifier = Modifier.testTag(UiTestTags.PlaylistDetailBack),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to playlists")
            }
            Text(
                text = playlistName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            IconButton(
                onClick = { playlistsViewModel.playPlaylist(songs, 0, shuffle = false) },
                enabled = songs.isNotEmpty(),
                modifier = Modifier.testTag(UiTestTags.PlaylistDetailPlay),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play playlist")
            }
            IconButton(
                onClick = { playlistsViewModel.playPlaylist(songs, 0, shuffle = true) },
                enabled = songs.isNotEmpty(),
                modifier = Modifier.testTag(UiTestTags.PlaylistDetailShuffle),
            ) {
                Icon(Icons.Default.Shuffle, contentDescription = "Shuffle playlist")
            }
            SortMenu(
                current = contentSort,
                options = PlaylistContentSortOrder.entries,
                labelOf = { it.label },
                nameOf = { it.name },
                onSelect = { contentSort = it },
            )
            Box {
                IconButton(onClick = { headerOverflowOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(
                    expanded = headerOverflowOpen,
                    onDismissRequest = { headerOverflowOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            headerOverflowOpen = false
                            renameDialogVisible = true
                        },
                        modifier = Modifier.testTag(UiTestTags.PlaylistDetailRename),
                    )
                    DropdownMenuItem(
                        text = { Text("Restore import order") },
                        enabled = songs.isNotEmpty(),
                        onClick = {
                            headerOverflowOpen = false
                            playlistsViewModel.restoreOriginalOrder(playlistId)
                            snackbar.show("Restored to import order")
                        },
                        modifier = Modifier.testTag(UiTestTags.PlaylistDetailRestoreOrder),
                    )
                }
            }
        }
        if (songs.isNotEmpty()) {
            Text(
                text = formatCountAndDuration(songs.size, songs.sumOf { it.durationMs }),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 4.dp),
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
                        {
                            playlistsViewModel.addSongToQueueNext(song.songId)
                            snackbar.show("Added to queue")
                        }
                    }
                val onLater =
                    remember(song.songId) {
                        {
                            playlistsViewModel.addSongToQueueLater(song.songId)
                            snackbar.show("Added to queue")
                        }
                    }
                ReorderableItem(playlistReorderState, key = song.playlistItemId) { _ ->
                    PlaylistSongRow(
                        song = song,
                        isCurrent = song.songId == currentSongId,
                        onPlay = onPlay,
                        onRemove = onRemove,
                        onPlayNext = onNext,
                        onPlayLater = onLater,
                        // Drag handle only appears in default order — dragging while sorted
                        // by something else would mutate position invisibly under a sorted view.
                        dragHandleModifier = if (canReorder) Modifier.draggableHandle() else null,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PlaylistListRow(
    playlist: com.migsmusic.data.local.model.PlaylistSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ListRow(
            title = playlist.name,
            subtitle = "${playlist.songCount} songs",
            modifier =
                Modifier
                    .testTag(UiTestTags.PlaylistRow)
                    .clickable(onClick = onOpen),
            actions = {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.testTag(UiTestTags.PlaylistRowMenu),
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
            },
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = {
                    menuOpen = false
                    onRename()
                },
                modifier = Modifier.testTag(UiTestTags.PlaylistRenameButton),
            )
            DropdownMenuItem(
                text = { Text("Delete playlist") },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
                modifier = Modifier.testTag(UiTestTags.PlaylistDeleteButton),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlaylistSongRow(
    song: PlaylistSong,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayLater: () -> Unit,
    dragHandleModifier: Modifier? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ListRow(
            title = song.title,
            subtitle = "${song.artist} • ${song.album}",
            modifier =
                Modifier
                    .testTag(UiTestTags.PlaylistSongRow)
                    .combinedClickable(
                        onClick = onPlay,
                        onLongClick = { menuOpen = true },
                    ),
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
            // Trailing area is intentionally minimal so the title + subtitle have room to
            // breathe. Drag handle when reorder is allowed, then a single ⋮ overflow that
            // hosts every action — matches the SongRow pattern elsewhere in the app.
            actions = {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    if (dragHandleModifier != null) {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = "Drag to reorder",
                            modifier = dragHandleModifier.padding(8.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.testTag(UiTestTags.PlaylistSongRowMenu),
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                }
            },
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("Play next") },
                onClick = {
                    menuOpen = false
                    onPlayNext()
                },
            )
            DropdownMenuItem(
                text = { Text("Play later") },
                onClick = {
                    menuOpen = false
                    onPlayLater()
                },
            )
            DropdownMenuItem(
                text = { Text("Remove from playlist") },
                onClick = {
                    menuOpen = false
                    onRemove()
                },
                modifier = Modifier.testTag(UiTestTags.PlaylistSongRemove),
            )
        }
    }
}
