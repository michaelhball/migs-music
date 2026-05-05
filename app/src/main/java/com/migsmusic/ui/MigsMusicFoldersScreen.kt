package com.migsmusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.migsmusic.data.local.model.FolderSummary

@Composable
internal fun FoldersRoute(
    libraryViewModel: LibraryViewModel,
    currentSongId: Long?,
    onOpenFolder: (FolderSummary) -> Unit,
) {
    HierarchicalFolderView(
        parentPath = "",
        title = null,
        libraryViewModel = libraryViewModel,
        playlistsViewModel = null,
        currentSongId = currentSongId,
        onOpenFolder = onOpenFolder,
        onGoUp = null,
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
) {
    HierarchicalFolderView(
        parentPath = folderPath,
        title = folderPath.substringAfterLast('/').ifEmpty { folderPath },
        libraryViewModel = libraryViewModel,
        playlistsViewModel = playlistsViewModel,
        currentSongId = currentSongId,
        onOpenFolder = onOpenFolder,
        onGoUp = onGoUp,
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
    screenTag: String,
) {
    val subfolders by libraryViewModel.subfoldersOf(parentPath)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val directSongs by libraryViewModel.directSongsIn(parentPath)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val recursiveSongs by libraryViewModel.songsRecursivelyIn(parentPath)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val openAddToPlaylist = rememberAddToPlaylistTrigger(playlistsViewModel)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(screenTag),
    ) {
        if (title != null) {
            item {
                Row(
                    modifier = Modifier
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
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
        if (recursiveSongs.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
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
                    modifier = Modifier
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
                val onPlay = remember(index, directSongs) {
                    { libraryViewModel.playSongs(directSongs, index, shuffle = false) }
                }
                val onNext = remember(song.id) { { libraryViewModel.playNext(song.id) } }
                val onLater = remember(song.id) { { libraryViewModel.playLater(song.id) } }
                val onAdd = remember(song.id) { { openAddToPlaylist(song.id) } }
                SongRow(
                    song = song,
                    isCurrent = song.id == currentSongId,
                    onPlay = onPlay,
                    onPlayNext = onNext,
                    onPlayLater = onLater,
                    onAddToPlaylist = onAdd,
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
