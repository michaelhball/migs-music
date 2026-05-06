package com.migsmusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.migsmusic.data.local.model.AlbumSummary
import com.migsmusic.data.local.model.ArtistSummary

@Composable
internal fun AlbumsRoute(
    libraryViewModel: LibraryViewModel,
    onOpenAlbum: (AlbumSummary) -> Unit,
) {
    val albums by libraryViewModel.albums.collectAsStateWithLifecycle()
    val sortOrder by libraryViewModel.albumSortOrder.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            SortMenu(
                current = sortOrder,
                options = AlbumSortOrder.entries,
                labelOf = { it.label },
                nameOf = { it.name },
                onSelect = libraryViewModel::setAlbumSortOrder,
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(albums, key = { _, item -> item.key }) { _, album ->
                ListRow(
                    title = album.title,
                    subtitle = "${album.artist} • ${album.songCount} songs",
                    modifier = Modifier.clickable { onOpenAlbum(album) },
                    leading = {
                        AlbumArtImage(
                            uri = album.albumArtUri,
                            modifier =
                                Modifier
                                    .size(48.dp)
                                    .clip(MaterialTheme.shapes.small),
                        )
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
internal fun AlbumDetailRoute(
    albumKey: String,
    libraryViewModel: LibraryViewModel,
    playlistsViewModel: PlaylistsViewModel,
    currentSongId: Long?,
    onGoToArtist: (artist: String) -> Unit,
) {
    val songs by libraryViewModel.songsByAlbum(albumKey).collectAsStateWithLifecycle(initialValue = emptyList())
    val openAddToPlaylist = rememberAddToPlaylistTrigger(playlistsViewModel)

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = songs.firstOrNull()?.album ?: "Album",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp),
        )
        Text(
            text = songs.firstOrNull()?.artist ?: "",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = { libraryViewModel.playSongs(songs, 0, shuffle = false) }, enabled = songs.isNotEmpty()) {
                Text("Play Album")
            }
            Button(onClick = { libraryViewModel.playSongs(songs, 0, shuffle = true) }, enabled = songs.isNotEmpty()) {
                Text("Shuffle Album")
            }
        }
        SongList(
            songs = songs,
            currentSongId = currentSongId,
            onPlaySong = { index -> libraryViewModel.playSongs(songs, index, shuffle = false) },
            onPlayNext = { libraryViewModel.playNext(it) },
            onPlayLater = { libraryViewModel.playLater(it) },
            onAddToPlaylist = openAddToPlaylist,
            // We're already on the album detail screen — only "Go to artist" is meaningful here.
            onGoToAlbum = null,
            onGoToArtist = onGoToArtist,
        )
    }
}

@Composable
internal fun ArtistsRoute(
    libraryViewModel: LibraryViewModel,
    onOpenArtist: (ArtistSummary) -> Unit,
) {
    val artists by libraryViewModel.artists.collectAsStateWithLifecycle()
    val sortOrder by libraryViewModel.artistSortOrder.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            SortMenu(
                current = sortOrder,
                options = ArtistSortOrder.entries,
                labelOf = { it.label },
                nameOf = { it.name },
                onSelect = libraryViewModel::setArtistSortOrder,
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(artists, key = { _, item -> item.name }) { _, artist ->
                ListRow(
                    title = artist.name,
                    subtitle = "${artist.albumCount} albums • ${artist.songCount} songs",
                    modifier = Modifier.clickable { onOpenArtist(artist) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
internal fun ArtistDetailRoute(
    artist: String,
    libraryViewModel: LibraryViewModel,
    playlistsViewModel: PlaylistsViewModel,
    currentSongId: Long?,
    onGoToAlbum: (album: String, artist: String) -> Unit,
) {
    val songs by libraryViewModel
        .sortedSongsByArtist(artist)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val sortOrder by libraryViewModel.artistDetailSongSortOrder.collectAsStateWithLifecycle()
    val openAddToPlaylist = rememberAddToPlaylistTrigger(playlistsViewModel)

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = artist,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp),
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Button(onClick = { libraryViewModel.playSongs(songs, 0, shuffle = false) }, enabled = songs.isNotEmpty()) {
                Text("Play Artist")
            }
            Button(onClick = { libraryViewModel.playSongs(songs, 0, shuffle = true) }, enabled = songs.isNotEmpty()) {
                Text("Shuffle Artist")
            }
            Spacer(modifier = Modifier.weight(1f))
            SortMenu(
                current = sortOrder,
                options = SongSortOrder.entries,
                labelOf = { it.label },
                nameOf = { it.name },
                onSelect = libraryViewModel::setArtistDetailSongSortOrder,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        SongList(
            songs = songs,
            currentSongId = currentSongId,
            onPlaySong = { index -> libraryViewModel.playSongs(songs, index, shuffle = false) },
            onPlayNext = { libraryViewModel.playNext(it) },
            onPlayLater = { libraryViewModel.playLater(it) },
            onAddToPlaylist = openAddToPlaylist,
            // We're already on the artist detail screen — only "Go to album" is meaningful here.
            onGoToAlbum = onGoToAlbum,
            onGoToArtist = null,
        )
    }
}
