package com.migsmusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.migsmusic.AppContainer
import com.migsmusic.isInstrumentationRunning
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigsMusicApp(
    appContainer: AppContainer,
    hasLibraryPermission: Boolean,
    onRequestPermission: () -> Unit,
) {
    val libraryViewModel: LibraryViewModel =
        viewModel(
            factory =
                ViewModelFactory {
                    LibraryViewModel(
                        libraryRepository = appContainer.libraryRepository,
                        playbackManager = appContainer.playbackManager,
                        preferences = appContainer.preferences,
                    )
                },
        )
    val playlistsViewModel: PlaylistsViewModel =
        viewModel(
            factory =
                ViewModelFactory {
                    PlaylistsViewModel(
                        playlistRepository = appContainer.playlistRepository,
                        libraryRepository = appContainer.libraryRepository,
                        playbackManager = appContainer.playbackManager,
                        preferences = appContainer.preferences,
                    )
                },
        )
    val playerViewModel: PlayerViewModel =
        viewModel(
            factory = ViewModelFactory { PlayerViewModel(appContainer.playbackManager) },
        )

    val navController = rememberNavController()
    val playbackState by playerViewModel.playbackUiState.collectAsStateWithLifecycle()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val snackbarController =
        remember(snackbarHostState, snackbarScope) {
            SnackbarController(snackbarHostState, snackbarScope)
        }

    // Cold-start populate when permission is granted, but only if the DB is empty —
    // avoids hammering MediaStore on every Activity creation.
    LaunchedEffect(hasLibraryPermission) {
        if (hasLibraryPermission) {
            libraryViewModel.ensureScanned()
        }
    }

    // Snapshot the persisted "was on player" flag at composition start, then act on it once
    // the NavController graph is ready (currentRoute becoming non-null is the signal). We
    // read via `remember` so the route-tracking write below can't overwrite the pref before
    // we observe it on a cold start that lands on Songs first.
    //
    // Disabled under instrumentation: tests recreate the Activity many times without
    // navigating, so a stale `true` pref would land every test on the Player screen with
    // "Nothing playing yet" — which made the smoke suite hang on assertions that expect
    // the default Songs route.
    val shouldRestorePlayerRoute =
        remember {
            !isInstrumentationRunning() && appContainer.preferences.wasOnPlayerRoute
        }
    var didRestorePlayerRoute by remember { mutableStateOf(false) }

    // Single effect: gates on `currentRoute != null` (i.e. the NavHost has registered its
    // graph), then both restores the player route once and keeps the pref in sync.
    // Calling navController.navigate(...) before the graph is set throws — that's why we
    // wait for currentRoute rather than firing on `Unit`.
    LaunchedEffect(currentRoute) {
        if (currentRoute == null) return@LaunchedEffect
        if (!didRestorePlayerRoute && shouldRestorePlayerRoute) {
            didRestorePlayerRoute = true
            navController.navigate("player") { launchSingleTop = true }
            return@LaunchedEffect
        }
        didRestorePlayerRoute = true
        appContainer.preferences.wasOnPlayerRoute = currentRoute == "player"
    }

    val onPlayerRoute = currentRoute == "player"

    CompositionLocalProvider(LocalSnackbarController provides snackbarController) {
        Scaffold(
            modifier = Modifier.testTag(UiTestTags.AppRoot),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            // No app-level TopAppBar: the system's task switcher / status bar already
            // shows the app name, and individual screens that need their own titles
            // (e.g. PlaylistDetailRoute) render them inline. A redundant "MIGS Music"
            // bar here just duplicated the system label and ate ~64dp of vertical space.
            bottomBar = {
                // Same: hide the mini-player + nav bar while the full-screen player is up.
                // It's redundant (the player is already showing) and consumes ~120dp of room.
                if (!onPlayerRoute) {
                    Column {
                        MiniPlayer(
                            playbackState = playbackState,
                            positionFlow = playerViewModel.currentPositionMs,
                            onTogglePlayPause = playerViewModel::togglePlayPause,
                            onSkipNext = playerViewModel::skipToNext,
                            onSkipPrevious = playerViewModel::skipToPrevious,
                            onOpenPlayer = { navController.navigate("player") },
                        )
                        NavigationBar {
                            listOf(
                                TopLevelDestination("songs", "Songs", Icons.Default.MusicNote, UiTestTags.SongsTab),
                                TopLevelDestination(
                                    "playlists",
                                    "Playlists",
                                    Icons.AutoMirrored.Filled.PlaylistPlay,
                                    UiTestTags.PlaylistsTab,
                                ),
                                TopLevelDestination("folders", "Folders", Icons.Default.Folder, UiTestTags.FoldersTab),
                                TopLevelDestination(
                                    "queue",
                                    "Queue",
                                    Icons.AutoMirrored.Filled.QueueMusic,
                                    UiTestTags.QueueTab,
                                ),
                            ).forEach { destination ->
                                NavigationBarItem(
                                    modifier = Modifier.testTag(destination.testTag),
                                    selected = currentRoute == destination.route,
                                    onClick = {
                                        navController.navigate(destination.route) {
                                            launchSingleTop = true
                                            restoreState = true
                                            popUpTo(navController.graph.startDestinationId) {
                                                saveState = true
                                            }
                                        }
                                    },
                                    icon = { Icon(destination.icon, contentDescription = destination.label) },
                                    label = { Text(destination.label) },
                                )
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            if (!hasLibraryPermission) {
                PermissionGate(
                    modifier = Modifier.padding(innerPadding),
                    onRequestPermission = onRequestPermission,
                )
                return@Scaffold
            }

            NavHost(
                navController = navController,
                startDestination = "songs",
                modifier = Modifier.padding(innerPadding),
            ) {
                composable("songs") {
                    SongsRoute(
                        libraryViewModel = libraryViewModel,
                        playlistsViewModel = playlistsViewModel,
                        currentSongId = playbackState.currentSong?.songId,
                        onOpenAlbums = { navController.navigate("albums") },
                        onOpenArtists = { navController.navigate("artists") },
                        onGoToAlbum = { album, artist ->
                            val key = "$album|||$artist"
                            navController.navigate("album/${java.net.URLEncoder.encode(key, "UTF-8")}")
                        },
                        onGoToArtist = { artist ->
                            navController.navigate("artist/${java.net.URLEncoder.encode(artist, "UTF-8")}")
                        },
                    )
                }
                composable("albums") {
                    AlbumsRoute(
                        libraryViewModel = libraryViewModel,
                        onOpenAlbum = { album ->
                            navController.navigate("album/${album.encodedKey()}")
                        },
                    )
                }
                composable(
                    route = "album/{albumKey}",
                    arguments = listOf(navArgument("albumKey") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val decodedKey =
                        backStackEntry.arguments?.getString("albumKey")
                            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.toString()) }
                            .orEmpty()
                    AlbumDetailRoute(
                        albumKey = decodedKey,
                        libraryViewModel = libraryViewModel,
                        playlistsViewModel = playlistsViewModel,
                        currentSongId = playbackState.currentSong?.songId,
                        onGoToArtist = { artist ->
                            navController.navigate("artist/${java.net.URLEncoder.encode(artist, "UTF-8")}")
                        },
                    )
                }
                composable("artists") {
                    ArtistsRoute(
                        libraryViewModel = libraryViewModel,
                        onOpenArtist = { artist ->
                            navController.navigate("artist/${artist.encodedName()}")
                        },
                    )
                }
                composable(
                    route = "artist/{artistName}",
                    arguments = listOf(navArgument("artistName") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val decodedArtist =
                        backStackEntry.arguments?.getString("artistName")
                            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.toString()) }
                            .orEmpty()
                    ArtistDetailRoute(
                        artist = decodedArtist,
                        libraryViewModel = libraryViewModel,
                        playlistsViewModel = playlistsViewModel,
                        currentSongId = playbackState.currentSong?.songId,
                        onGoToAlbum = { album, artist ->
                            val key = "$album|||$artist"
                            navController.navigate("album/${java.net.URLEncoder.encode(key, "UTF-8")}")
                        },
                    )
                }
                composable("folders") {
                    FoldersRoute(
                        libraryViewModel = libraryViewModel,
                        currentSongId = playbackState.currentSong?.songId,
                        onOpenFolder = { folder ->
                            navController.navigate("folder/${folder.encodedPath()}")
                        },
                        onNavigateToFolder = { path ->
                            // Tapping a breadcrumb ancestor pops back to it if it's on the
                            // stack rather than pushing a duplicate. Fresh navigate as fallback.
                            val target = "folder/${java.net.URLEncoder.encode(path, "UTF-8")}"
                            if (!navController.popBackStack(route = target, inclusive = false)) {
                                navController.navigate(target)
                            }
                        },
                        onGoToAlbum = { album, artist ->
                            val key = "$album|||$artist"
                            navController.navigate("album/${java.net.URLEncoder.encode(key, "UTF-8")}")
                        },
                        onGoToArtist = { artist ->
                            navController.navigate("artist/${java.net.URLEncoder.encode(artist, "UTF-8")}")
                        },
                    )
                }
                composable(
                    route = "folder/{path}",
                    arguments = listOf(navArgument("path") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val decodedPath =
                        backStackEntry.arguments?.getString("path")
                            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.toString()) }
                            .orEmpty()
                    FolderDetailRoute(
                        folderPath = decodedPath,
                        libraryViewModel = libraryViewModel,
                        playlistsViewModel = playlistsViewModel,
                        currentSongId = playbackState.currentSong?.songId,
                        onOpenFolder = { folder ->
                            navController.navigate("folder/${folder.encodedPath()}")
                        },
                        onGoUp = { navController.popBackStack() },
                        onNavigateToFolder = { path ->
                            val target = "folder/${java.net.URLEncoder.encode(path, "UTF-8")}"
                            if (!navController.popBackStack(route = target, inclusive = false)) {
                                navController.navigate(target)
                            }
                        },
                        onGoToAlbum = { album, artist ->
                            val key = "$album|||$artist"
                            navController.navigate("album/${java.net.URLEncoder.encode(key, "UTF-8")}")
                        },
                        onGoToArtist = { artist ->
                            navController.navigate("artist/${java.net.URLEncoder.encode(artist, "UTF-8")}")
                        },
                    )
                }
                composable("playlists") {
                    PlaylistsRoute(
                        playlistsViewModel = playlistsViewModel,
                        onOpenPlaylist = { playlistId ->
                            navController.navigate("playlist/$playlistId")
                        },
                    )
                }
                composable(
                    route = "playlist/{playlistId}",
                    arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
                ) { backStackEntry ->
                    val playlistId = backStackEntry.arguments?.getLong("playlistId") ?: return@composable
                    PlaylistDetailRoute(
                        playlistId = playlistId,
                        playlistsViewModel = playlistsViewModel,
                        currentSongId = playbackState.currentSong?.songId,
                        onGoBack = { navController.popBackStack() },
                    )
                }
                composable("queue") {
                    QueueRoute(
                        playerViewModel = playerViewModel,
                        playlistsViewModel = playlistsViewModel,
                    )
                }
                composable("player") {
                    PlayerRoute(
                        playerViewModel = playerViewModel,
                        playlistsViewModel = playlistsViewModel,
                        onOpenQueue = { navController.navigate("queue") },
                        onDismiss = { navController.popBackStack() },
                        onOpenArtist = { artist ->
                            navController.popBackStack()
                            navController.navigate("artist/${java.net.URLEncoder.encode(artist, "UTF-8")}")
                        },
                        onOpenAlbum = { album, artist ->
                            // Reuse the same album-key construction as AlbumsRoute → AlbumDetailRoute.
                            val key = "$album|||$artist"
                            navController.popBackStack()
                            navController.navigate("album/${java.net.URLEncoder.encode(key, "UTF-8")}")
                        },
                    )
                }
            }
        }
    }
}

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val testTag: String,
)

@Composable
private fun PermissionGate(
    modifier: Modifier = Modifier,
    onRequestPermission: () -> Unit,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Library access is required to scan and play your local music.",
                style = MaterialTheme.typography.titleMedium,
            )
            Button(
                modifier = Modifier.testTag(UiTestTags.PermissionButton),
                onClick = onRequestPermission,
            ) {
                Text(text = "Allow music access")
            }
        }
    }
}
