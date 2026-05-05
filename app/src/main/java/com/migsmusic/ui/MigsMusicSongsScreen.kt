package com.migsmusic.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.migsmusic.data.local.entity.SongEntity

@Composable
internal fun SongsRoute(
    libraryViewModel: LibraryViewModel,
    playlistsViewModel: PlaylistsViewModel,
    currentSongId: Long?,
    onOpenAlbums: () -> Unit,
    onOpenArtists: () -> Unit,
    onGoToAlbum: (album: String, artist: String) -> Unit,
    onGoToArtist: (artist: String) -> Unit,
) {
    val songs by libraryViewModel.visibleSongs.collectAsStateWithLifecycle()
    val scanState by libraryViewModel.libraryScanState.collectAsStateWithLifecycle()
    val sortOrder by libraryViewModel.songSortOrder.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val openAddToPlaylist = rememberAddToPlaylistTrigger(playlistsViewModel)

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .testTag(UiTestTags.SongsScreen),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                libraryViewModel.updateSearchQuery(it)
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .testTag(UiTestTags.SearchField),
            singleLine = true,
            label = { Text("Search songs") },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            query = ""
                            libraryViewModel.updateSearchQuery("")
                        },
                        modifier = Modifier.testTag(UiTestTags.SearchClear),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                }
            },
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Button(
                onClick = { libraryViewModel.playSongs(songs, 0, shuffle = true) },
                enabled = songs.isNotEmpty(),
            ) {
                Icon(Icons.Default.Shuffle, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Shuffle All")
            }
            Button(onClick = onOpenAlbums) { Text("Albums") }
            Button(onClick = onOpenArtists) { Text("Artists") }
            Spacer(modifier = Modifier.weight(1f))
            SortMenu(
                current = sortOrder,
                options = SongSortOrder.entries,
                labelOf = { it.label },
                nameOf = { it.name },
                onSelect = libraryViewModel::setSongSortOrder,
            )
        }
        // Scan happens automatically via cold-start + ContentObserver; the only thing worth
        // surfacing is an error if scan failed. Otherwise: empty.
        if (scanState.errorMessage != null) {
            Text(
                text = scanState.errorMessage.orEmpty(),
                modifier =
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag(UiTestTags.ScanStatus),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        when {
            songs.isEmpty() && query.isNotBlank() -> EmptyState("No songs match \"$query\"")
            songs.isEmpty() ->
                EmptyState(
                    "Your library is empty.\nCopy music to your phone — it'll appear here automatically.",
                )
            else ->
                SongList(
                    songs = songs,
                    currentSongId = currentSongId,
                    onPlaySong = { index -> libraryViewModel.playSongs(songs, index, shuffle = false) },
                    onPlayNext = { libraryViewModel.playNext(it) },
                    onPlayLater = { libraryViewModel.playLater(it) },
                    onAddToPlaylist = openAddToPlaylist,
                    onGoToAlbum = onGoToAlbum,
                    onGoToArtist = onGoToArtist,
                )
        }
    }
}

@Composable
internal fun SongList(
    songs: List<SongEntity>,
    currentSongId: Long?,
    onPlaySong: (Int) -> Unit,
    onPlayNext: (Long) -> Unit,
    onPlayLater: (Long) -> Unit,
    onAddToPlaylist: (Long) -> Unit,
    // Optional — pass null to hide the corresponding menu item on screens where navigating
    // there is redundant (Album-detail hides "Go to album", Artist-detail hides "Go to artist").
    onGoToAlbum: ((album: String, artist: String) -> Unit)? = null,
    onGoToArtist: ((artist: String) -> Unit)? = null,
) {
    val lazyListState = rememberLazyListState()
    // Auto-scroll to the currently-playing song once on entry, so the highlight is visible
    // without scrolling. Re-running on every currentSongId change would scroll while the
    // user is scrolling AND can race with measurement → layout-cycle crash on rapid skips.
    var didInitialScroll by remember { mutableStateOf(false) }
    LaunchedEffect(currentSongId, songs.size) {
        if (didInitialScroll || currentSongId == null) return@LaunchedEffect
        val idx = songs.indexOfFirst { it.id == currentSongId }
        if (idx < 0) return@LaunchedEffect
        val visible = lazyListState.layoutInfo.visibleItemsInfo
        val firstVisible = visible.firstOrNull()?.index ?: -1
        val lastVisible = visible.lastOrNull()?.index ?: -1
        if (idx !in firstVisible..lastVisible) {
            lazyListState.scrollToItem(idx)
        }
        didInitialScroll = true
    }
    val snackbar = LocalSnackbarController.current
    LazyColumn(
        state = lazyListState,
        modifier =
            Modifier
                .fillMaxSize()
                .testTag(UiTestTags.SongList),
    ) {
        itemsIndexed(songs, key = { _, item -> item.id }) { index, song ->
            // Memoize per-row lambdas so a state change at the parent (e.g. currentSongId
            // flipping) doesn't allocate four fresh closures per visible row + force every
            // SongRow to recompose from lambda inequality alone.
            val onPlay = remember(index) { { onPlaySong(index) } }
            val onNext =
                remember(song.id) {
                    {
                        onPlayNext(song.id)
                        snackbar.show("Added to queue")
                    }
                }
            val onLater =
                remember(song.id) {
                    {
                        onPlayLater(song.id)
                        snackbar.show("Added to queue")
                    }
                }
            val onAdd = remember(song.id) { { onAddToPlaylist(song.id) } }
            val onAlbum =
                remember(song.id, onGoToAlbum) {
                    onGoToAlbum?.let { f -> { f(song.album, song.artist) } }
                }
            val onArtist =
                remember(song.id, onGoToArtist) {
                    onGoToArtist?.let { f -> { f(song.artist) } }
                }
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SongRow(
    song: SongEntity,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayLater: () -> Unit,
    onAddToPlaylist: () -> Unit,
    // Optional — pass null on screens where navigating to that destination is redundant
    // (e.g. AlbumDetailRoute hides "Go to album", ArtistDetailRoute hides "Go to artist").
    onGoToAlbum: (() -> Unit)? = null,
    onGoToArtist: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ListRow(
            title = song.title,
            subtitle = "${song.artist} • ${song.album}",
            modifier =
                Modifier
                    .testTag(UiTestTags.SongRow)
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
            actions = {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.testTag(UiTestTags.SongRowMenu),
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
                modifier = Modifier.testTag(UiTestTags.SongActionNext),
                text = { Text("Play next") },
                onClick = {
                    menuOpen = false
                    onPlayNext()
                },
            )
            DropdownMenuItem(
                modifier = Modifier.testTag(UiTestTags.SongActionLater),
                text = { Text("Play later") },
                onClick = {
                    menuOpen = false
                    onPlayLater()
                },
            )
            DropdownMenuItem(
                modifier = Modifier.testTag(UiTestTags.SongActionAdd),
                text = { Text("Add to playlist…") },
                onClick = {
                    menuOpen = false
                    onAddToPlaylist()
                },
            )
            if (onGoToAlbum != null) {
                DropdownMenuItem(
                    modifier = Modifier.testTag(UiTestTags.SongActionGoToAlbum),
                    text = { Text("Go to album") },
                    onClick = {
                        menuOpen = false
                        onGoToAlbum()
                    },
                )
            }
            if (onGoToArtist != null) {
                DropdownMenuItem(
                    modifier = Modifier.testTag(UiTestTags.SongActionGoToArtist),
                    text = { Text("Go to artist") },
                    onClick = {
                        menuOpen = false
                        onGoToArtist()
                    },
                )
            }
        }
    }
}
