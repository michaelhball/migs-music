package com.migsmusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * "Loves" screen — every song the user has hearted, most-recent first. Local-only:
 * Mac sync never replaces or removes anything from this list. Reuses [SongList]
 * for the row layout so context menus / play-next / play-later all work the same
 * as the main Songs screen.
 */
@Composable
internal fun LovesRoute(
    lovesViewModel: LovesViewModel,
    playlistsViewModel: PlaylistsViewModel,
    playerViewModel: PlayerViewModel,
    onGoToAlbum: (album: String, artist: String) -> Unit,
    onGoToArtist: (artist: String) -> Unit,
) {
    val songs by lovesViewModel.songs.collectAsStateWithLifecycle()
    val currentSongId by playerViewModel.currentSongId.collectAsStateWithLifecycle()
    val openAddToPlaylist = rememberAddToPlaylistTrigger(playlistsViewModel)

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .testTag(UiTestTags.LovesScreen),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Favorite,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "loves",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text =
                    formatCountAndDuration(
                        songs.size,
                        songs.sumOf { it.durationMs },
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { lovesViewModel.playSongs(songs, 0, shuffle = false) },
                enabled = songs.isNotEmpty(),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Play")
            }
            Button(
                onClick = { lovesViewModel.playSongs(songs, 0, shuffle = true) },
                enabled = songs.isNotEmpty(),
            ) {
                Icon(Icons.Default.Shuffle, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Shuffle")
            }
        }

        if (songs.isEmpty()) {
            EmptyState(
                "No loves yet.\nTap the heart on the player to add a song.",
            )
        } else {
            SongList(
                songs = songs,
                currentSongId = currentSongId,
                onPlaySong = { index -> lovesViewModel.playSongs(songs, index, shuffle = false) },
                onPlayNext = lovesViewModel::playNext,
                onPlayLater = lovesViewModel::playLater,
                onAddToPlaylist = openAddToPlaylist,
                onGoToAlbum = onGoToAlbum,
                onGoToArtist = onGoToArtist,
            )
        }
    }
}
