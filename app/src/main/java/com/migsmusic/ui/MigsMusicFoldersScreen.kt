package com.migsmusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.migsmusic.data.local.model.FolderSummary

@Composable
internal fun FoldersRoute(
    libraryViewModel: LibraryViewModel,
    currentSongId: Long?,
    onOpenFolder: (FolderSummary) -> Unit,
    onNavigateToFolder: (path: String) -> Unit,
    onGoToAlbum: (album: String, artist: String) -> Unit,
    onGoToArtist: (artist: String) -> Unit,
) {
    HierarchicalFolderView(
        parentPath = "",
        title = null,
        libraryViewModel = libraryViewModel,
        playlistsViewModel = null,
        currentSongId = currentSongId,
        onOpenFolder = onOpenFolder,
        onGoUp = null,
        onNavigateToFolder = onNavigateToFolder,
        onGoToAlbum = onGoToAlbum,
        onGoToArtist = onGoToArtist,
        screenTag = UiTestTags.FoldersScreen,
    )
}

@Composable
internal fun FolderDetailRoute(
    folderPath: String,
    libraryViewModel: LibraryViewModel,
    playlistsViewModel: PlaylistsViewModel,
    currentSongId: Long?,
    onOpenFolder: (FolderSummary) -> Unit,
    onGoUp: () -> Unit,
    onNavigateToFolder: (path: String) -> Unit,
    onGoToAlbum: (album: String, artist: String) -> Unit,
    onGoToArtist: (artist: String) -> Unit,
) {
    HierarchicalFolderView(
        parentPath = folderPath,
        title = folderPath.substringAfterLast('/').ifEmpty { folderPath },
        libraryViewModel = libraryViewModel,
        playlistsViewModel = playlistsViewModel,
        currentSongId = currentSongId,
        onOpenFolder = onOpenFolder,
        onGoUp = onGoUp,
        onNavigateToFolder = onNavigateToFolder,
        onGoToAlbum = onGoToAlbum,
        onGoToArtist = onGoToArtist,
        screenTag = UiTestTags.FolderDetailScreen,
    )
}

@Composable
internal fun HierarchicalFolderView(
    parentPath: String,
    title: String?,
    libraryViewModel: LibraryViewModel,
    playlistsViewModel: PlaylistsViewModel?,
    currentSongId: Long?,
    onOpenFolder: (FolderSummary) -> Unit,
    onGoUp: (() -> Unit)?,
    onNavigateToFolder: (path: String) -> Unit,
    onGoToAlbum: (album: String, artist: String) -> Unit,
    onGoToArtist: (artist: String) -> Unit,
    screenTag: String,
) {
    val subfolders by libraryViewModel.subfoldersOf(parentPath)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val directSongs by libraryViewModel.directSongsIn(parentPath)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val recursiveSongs by libraryViewModel.songsRecursivelyIn(parentPath)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val openAddToPlaylist = rememberAddToPlaylistTrigger(playlistsViewModel)
    val snackbar = LocalSnackbarController.current

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .testTag(screenTag),
    ) {
        if (title != null) {
            item {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onGoUp != null) {
                        IconButton(
                            onClick = onGoUp,
                            modifier = Modifier.testTag(UiTestTags.FolderGoUp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go up to parent folder")
                        }
                    }
                    FolderBreadcrumb(
                        path = parentPath,
                        onNavigateToFolder = onNavigateToFolder,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
        if (recursiveSongs.isNotEmpty()) {
            item {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { libraryViewModel.playSongs(recursiveSongs, 0, shuffle = false) }) {
                        Text("Play Folder")
                    }
                    Button(onClick = { libraryViewModel.playSongs(recursiveSongs, 0, shuffle = true) }) {
                        Text("Shuffle Folder")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = formatCountAndDuration(recursiveSongs.size, recursiveSongs.sumOf { it.durationMs }),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (subfolders.isNotEmpty()) {
            item { SectionHeader("Folders") }
            itemsIndexed(subfolders, key = { _, item -> "f:" + item.path }) { _, folder ->
                ListRow(
                    title = folder.name,
                    subtitle = "${folder.songCount} songs",
                    modifier =
                        Modifier
                            .testTag(UiTestTags.FolderRow)
                            .clickable { onOpenFolder(folder) },
                    actions = { Icon(Icons.Default.Folder, contentDescription = null) },
                )
                HorizontalDivider()
            }
        }
        if (directSongs.isNotEmpty()) {
            item { SectionHeader("Songs") }
            itemsIndexed(directSongs, key = { _, item -> "s:" + item.id }) { index, song ->
                val onPlay =
                    remember(index, directSongs) {
                        { libraryViewModel.playSongs(directSongs, index, shuffle = false) }
                    }
                val onNext =
                    remember(song.id) {
                        {
                            libraryViewModel.playNext(song.id)
                            snackbar.show("Added to queue")
                        }
                    }
                val onLater =
                    remember(song.id) {
                        {
                            libraryViewModel.playLater(song.id)
                            snackbar.show("Added to queue")
                        }
                    }
                val onAdd = remember(song.id) { { openAddToPlaylist(song.id) } }
                val onAlbum = remember(song.id) { { onGoToAlbum(song.album, song.artist) } }
                val onArtist = remember(song.id) { { onGoToArtist(song.artist) } }
                SongRow(
                    song = song,
                    isCurrent = song.id == currentSongId,
                    onPlay = onPlay,
                    onPlayNext = onNext,
                    onPlayLater = onLater,
                    onAddToPlaylist = onAdd,
                    onGoToAlbum = onAlbum,
                    onGoToArtist = onArtist,
                )
                HorizontalDivider()
            }
        }
        if (subfolders.isEmpty() && directSongs.isEmpty()) {
            item {
                Text(
                    text = "Empty folder",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

/**
 * Renders a tappable breadcrumb of the folder hierarchy: e.g. for `Music/Beatles/Hey Jude`,
 * shows `Music ▸ Beatles ▸ Hey Jude`. Each segment except the last navigates to that ancestor.
 * Horizontal scroll keeps deep paths from clipping.
 */
@Composable
private fun FolderBreadcrumb(
    path: String,
    onNavigateToFolder: (path: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments = remember(path) { path.split('/').filter { it.isNotEmpty() } }
    if (segments.isEmpty()) return

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { index, segment ->
            val isLast = index == segments.lastIndex
            // Reconstruct the full path for this ancestor — `Music`, `Music/Beatles`, etc.
            val pathToHere = remember(path, index) { segments.take(index + 1).joinToString("/") }
            Text(
                text = segment,
                style = MaterialTheme.typography.titleMedium,
                color =
                    if (isLast) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                fontWeight = if (isLast) FontWeight.SemiBold else null,
                modifier =
                    Modifier
                        .testTag(UiTestTags.FolderBreadcrumbSegment)
                        .let { base ->
                            if (isLast) base else base.clickable { onNavigateToFolder(pathToHere) }
                        }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
            )
            if (!isLast) {
                Text(
                    text = "▸",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
