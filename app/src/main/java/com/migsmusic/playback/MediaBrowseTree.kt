package com.migsmusic.playback

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.migsmusic.data.local.entity.SongEntity
import com.migsmusic.data.repository.LibraryRepository
import com.migsmusic.data.repository.PlaylistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * [MediaLibrarySession.Callback] for external MediaBrowser clients — primarily Android Auto,
 * but also Bluetooth AVRCP with browse support and Wear OS. Exposes the same content model
 * the in-app UI uses (Playlists / Albums / Artists / Songs) through the platform browse
 * protocol, and resolves the "user tapped a song in the browse tree" event into a proper
 * queue on the shared [Player] so skip-next / skip-previous / auto-advance all work.
 *
 * ## mediaId scheme
 *
 * All browse ids are `<type>:<key>[@<parent>]`:
 *
 * | id                  | meaning                                                       |
 * |---------------------|---------------------------------------------------------------|
 * | `root`              | synthetic library root                                        |
 * | `cat:playlists`     | list of user playlists                                        |
 * | `cat:albums`        | list of albums                                                |
 * | `cat:artists`       | list of artists                                               |
 * | `cat:songs`         | flat list of every song                                       |
 * | `pl:<id>`           | songs inside a playlist                                       |
 * | `al:<encoded-key>`  | songs on an album (albumKey = "album|artist")                 |
 * | `ar:<encoded-name>` | songs by an artist                                            |
 * | `sg:<id>@<parent>`  | one playable song, tagged with the container it was browsed  |
 * |                     | from so [onSetMediaItems] can queue up the surrounding songs. |
 *
 * `@<parent>` is baked into every song's id because Auto's play call only echoes the
 * song's own mediaId back to us. Without the tag we'd play a single-item queue with no
 * skip-next behaviour.
 */
class MediaBrowseTree(
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
    private val scope: CoroutineScope,
    private val prepareExternalQueue: suspend (songIds: List<Long>, startIndex: Int) -> List<QueueEntry>,
) : MediaLibrarySession.Callback {
    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val root = folderItem(id = ID_ROOT, title = "migs music")
        return Futures.immediateFuture(LibraryResult.ofItem(root, params))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        async {
            val all = childrenOf(parentId) ?: return@async badListValue()
            val sliced = paginate(all, page, pageSize)
            LibraryResult.ofItemList(ImmutableList.copyOf(sliced), params)
        }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        async {
            val item = resolveItem(mediaId) ?: return@async LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            LibraryResult.ofItem(item, null)
        }

    /**
     * Called when a MediaBrowser controller (Auto) picks an item to play. The incoming
     * mediaItems carry just the mediaId + metadata — no URI. We expand the id into the
     * full containing queue (all songs on the album / in the playlist / by the artist),
     * mutate our own [QueueEngine] state via [prepareExternalQueue] so our in-app UI
     * stays in sync, then hand Media3 back the resolved items with URIs. Media3 will
     * call `player.setMediaItems` with what we return, which triggers the usual
     * `Player.Listener` path in [PlaybackManager] — but by then queueEngine already
     * matches the entryIds we baked into the returned items, so nothing looks foreign.
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch {
            try {
                val requested = mediaItems.firstOrNull()
                if (requested == null) {
                    future.set(MediaSession.MediaItemsWithStartPosition(ImmutableList.of(), 0, 0L))
                    return@launch
                }
                val expansion = expandForPlay(requested.mediaId)
                if (expansion == null) {
                    // Unknown / unparseable id — fall back to whatever Media3 gave us so
                    // the request doesn't silently fail; playback of a single song works,
                    // it just won't have skip-next context.
                    future.set(
                        MediaSession.MediaItemsWithStartPosition(
                            ImmutableList.copyOf(mediaItems),
                            startIndex,
                            startPositionMs,
                        ),
                    )
                    return@launch
                }
                val (songIds, expandedStartIndex, songsById) = expansion
                val entries = prepareExternalQueue(songIds, expandedStartIndex)
                val resolved =
                    entries.mapNotNull { entry ->
                        val song = songsById[entry.songId] ?: return@mapNotNull null
                        song.toPlayerMediaItem(entryId = entry.entryId)
                    }
                future.set(
                    MediaSession.MediaItemsWithStartPosition(
                        ImmutableList.copyOf(resolved),
                        expandedStartIndex,
                        startPositionMs,
                    ),
                )
            } catch (throwable: Throwable) {
                Log.e(TAG, "onSetMediaItems failed for ${mediaItems.firstOrNull()?.mediaId}", throwable)
                future.setException(throwable)
            }
        }
        return future
    }

    /**
     * `MediaController.addMediaItems` path — same resolution, but we return only the
     * items that map to real songs (URIs). We don't touch queueEngine here because the
     * caller is asking us to *add* to whatever queue exists, not replace it — the
     * existing engine state is authoritative and Media3's own append handles the rest.
     */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        val future = SettableFuture.create<MutableList<MediaItem>>()
        scope.launch {
            try {
                val resolved = mutableListOf<MediaItem>()
                for (item in mediaItems) {
                    val parsed = parseSongId(item.mediaId)
                    val song =
                        if (parsed != null) {
                            libraryRepository.getSongsByIds(listOf(parsed.songId)).firstOrNull()
                        } else {
                            null
                        }
                    if (song != null) {
                        // Preserve the incoming mediaId so callers that reference it after
                        // add (e.g. remove-by-id) still work.
                        resolved += song.toPlayerMediaItem(entryId = item.mediaId)
                    } else if (item.localConfiguration?.uri != null) {
                        // Already has a URI — trust it (rare, but safe fallback).
                        resolved += item
                    }
                    // else: silently drop items we can't resolve.
                }
                future.set(resolved)
            } catch (throwable: Throwable) {
                Log.e(TAG, "onAddMediaItems failed", throwable)
                future.setException(throwable)
            }
        }
        return future
    }

    // ─── children resolution ────────────────────────────────────────────────

    private suspend fun childrenOf(parentId: String): List<MediaItem>? =
        when {
            parentId == ID_ROOT -> topLevelChildren()
            parentId == ID_CAT_PLAYLISTS -> playlistList()
            parentId == ID_CAT_ALBUMS -> albumList()
            parentId == ID_CAT_ARTISTS -> artistList()
            parentId == ID_CAT_SONGS -> flatSongsAsChildren()
            parentId.startsWith("pl:") -> {
                val id = parentId.removePrefix("pl:").toLongOrNull() ?: return null
                playlistSongs(id, parentId)
            }
            parentId.startsWith("al:") -> albumSongs(decodeKey(parentId.removePrefix("al:")), parentId)
            parentId.startsWith("ar:") -> artistSongs(decodeKey(parentId.removePrefix("ar:")), parentId)
            else -> null
        }

    private fun topLevelChildren(): List<MediaItem> =
        listOf(
            folderItem(id = ID_CAT_PLAYLISTS, title = "Playlists"),
            folderItem(id = ID_CAT_ALBUMS, title = "Albums"),
            folderItem(id = ID_CAT_ARTISTS, title = "Artists"),
            folderItem(id = ID_CAT_SONGS, title = "Songs"),
        )

    private suspend fun playlistList(): List<MediaItem> =
        playlistRepository.observePlaylists().first().map { p ->
            folderItem(
                id = "pl:${p.id}",
                title = p.name,
                subtitle = "${p.songCount} songs",
            )
        }

    private suspend fun albumList(): List<MediaItem> =
        libraryRepository.observeAlbums().first().map { a ->
            folderItem(
                id = "al:${encodeKey(a.key)}",
                title = a.title,
                subtitle = a.artist,
                artworkUri = a.albumArtUri,
            )
        }

    private suspend fun artistList(): List<MediaItem> =
        libraryRepository.observeArtists().first().map { a ->
            folderItem(
                id = "ar:${encodeKey(a.name)}",
                title = a.name,
                subtitle = "${a.songCount} songs • ${a.albumCount} albums",
            )
        }

    private suspend fun flatSongsAsChildren(): List<MediaItem> =
        libraryRepository.observeAllSongs().first().map { it.toBrowseSong(parentId = ID_CAT_SONGS) }

    private suspend fun playlistSongs(
        playlistId: Long,
        parentId: String,
    ): List<MediaItem> =
        playlistRepository.observePlaylistSongs(playlistId).first().map { pl ->
            val metadata =
                MediaMetadata.Builder()
                    .setTitle(pl.title)
                    .setArtist(pl.artist)
                    .setAlbumTitle(pl.album)
                    .setDisplayTitle(pl.title)
                    .setSubtitle(pl.artist)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .apply { if (pl.albumArtUri != null) setArtworkUri(Uri.parse(pl.albumArtUri)) }
                    .build()
            MediaItem.Builder()
                .setMediaId("sg:${pl.songId}@$parentId")
                .setMediaMetadata(metadata)
                .build()
        }

    private suspend fun albumSongs(
        albumKey: String,
        parentId: String,
    ): List<MediaItem> = libraryRepository.observeSongsByAlbum(albumKey).first().map { it.toBrowseSong(parentId) }

    private suspend fun artistSongs(
        artistName: String,
        parentId: String,
    ): List<MediaItem> = libraryRepository.observeSongsByArtist(artistName).first().map { it.toBrowseSong(parentId) }

    // ─── single-item resolution (onGetItem) ────────────────────────────────

    private suspend fun resolveItem(mediaId: String): MediaItem? {
        when (mediaId) {
            ID_ROOT -> return folderItem(id = ID_ROOT, title = "migs music")
            ID_CAT_PLAYLISTS -> return folderItem(id = mediaId, title = "Playlists")
            ID_CAT_ALBUMS -> return folderItem(id = mediaId, title = "Albums")
            ID_CAT_ARTISTS -> return folderItem(id = mediaId, title = "Artists")
            ID_CAT_SONGS -> return folderItem(id = mediaId, title = "Songs")
        }
        if (mediaId.startsWith("pl:")) {
            val id = mediaId.removePrefix("pl:").toLongOrNull() ?: return null
            val summary = playlistRepository.observePlaylists().first().firstOrNull { it.id == id } ?: return null
            return folderItem(id = mediaId, title = summary.name, subtitle = "${summary.songCount} songs")
        }
        if (mediaId.startsWith("al:")) {
            val key = decodeKey(mediaId.removePrefix("al:"))
            val summary = libraryRepository.observeAlbums().first().firstOrNull { it.key == key } ?: return null
            return folderItem(
                id = mediaId,
                title = summary.title,
                subtitle = summary.artist,
                artworkUri = summary.albumArtUri,
            )
        }
        if (mediaId.startsWith("ar:")) {
            val name = decodeKey(mediaId.removePrefix("ar:"))
            val summary = libraryRepository.observeArtists().first().firstOrNull { it.name == name } ?: return null
            return folderItem(id = mediaId, title = summary.name, subtitle = "${summary.songCount} songs")
        }
        if (mediaId.startsWith("sg:")) {
            val parsed = parseSongId(mediaId) ?: return null
            val song = libraryRepository.getSongsByIds(listOf(parsed.songId)).firstOrNull() ?: return null
            return song.toBrowseSong(parsed.parentId, mediaIdOverride = mediaId)
        }
        return null
    }

    // ─── play-time context expansion ────────────────────────────────────────

    private data class Expansion(
        val songIds: List<Long>,
        val startIndex: Int,
        val songsById: Map<Long, SongEntity>,
    )

    /**
     * Given the mediaId of a song the user just tapped in Auto, return the full ordered
     * list of songs from its container plus the index within that list. When the id has
     * no parent context (or the context lookup fails), falls back to a single-song queue.
     */
    private suspend fun expandForPlay(mediaId: String): Expansion? {
        val parsed = parseSongId(mediaId) ?: return null
        val contextSongs: List<SongEntity> =
            when {
                parsed.parentId == ID_CAT_SONGS -> libraryRepository.observeAllSongs().first()
                parsed.parentId.startsWith("pl:") -> {
                    val playlistId = parsed.parentId.removePrefix("pl:").toLongOrNull()
                    if (playlistId == null) {
                        emptyList()
                    } else {
                        val ordered = playlistRepository.observePlaylistSongs(playlistId).first().map { it.songId }
                        val byId = libraryRepository.getSongsByIds(ordered).associateBy { it.id }
                        ordered.mapNotNull { byId[it] }
                    }
                }
                parsed.parentId.startsWith("al:") ->
                    libraryRepository.observeSongsByAlbum(decodeKey(parsed.parentId.removePrefix("al:"))).first()
                parsed.parentId.startsWith("ar:") ->
                    libraryRepository.observeSongsByArtist(decodeKey(parsed.parentId.removePrefix("ar:"))).first()
                else -> emptyList()
            }
        val startIndex = contextSongs.indexOfFirst { it.id == parsed.songId }
        if (contextSongs.isEmpty() || startIndex < 0) {
            // Context lookup failed — fall back to a single-song queue with just the tapped song.
            val single = libraryRepository.getSongsByIds(listOf(parsed.songId)).firstOrNull() ?: return null
            return Expansion(
                songIds = listOf(single.id),
                startIndex = 0,
                songsById = mapOf(single.id to single),
            )
        }
        return Expansion(
            songIds = contextSongs.map { it.id },
            startIndex = startIndex,
            songsById = contextSongs.associateBy { it.id },
        )
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    /**
     * Builds a folder-type browse item — shown as a row in the Auto browse UI. Songs use
     * [toBrowseSong] instead (which sets IS_PLAYABLE + MEDIA_TYPE_MUSIC).
     */
    private fun folderItem(
        id: String,
        title: String,
        subtitle: String? = null,
        artworkUri: String? = null,
    ): MediaItem {
        val metadataBuilder =
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        if (subtitle != null) {
            metadataBuilder.setSubtitle(subtitle).setArtist(subtitle)
        }
        if (artworkUri != null) {
            metadataBuilder.setArtworkUri(Uri.parse(artworkUri))
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    /**
     * Browse-tree wrapper for a song. Deliberately no URI — Auto only needs metadata
     * for display; [onSetMediaItems] / [onAddMediaItems] resolve to a real URI-bearing
     * [MediaItem] via [toPlayerMediaItem] at tap time.
     */
    private fun SongEntity.toBrowseSong(
        parentId: String,
        mediaIdOverride: String? = null,
    ): MediaItem {
        val mediaId = mediaIdOverride ?: "sg:$id@$parentId"
        val metadata =
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setDisplayTitle(title)
                .setSubtitle(artist)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .apply { if (albumArtUri != null) setArtworkUri(Uri.parse(albumArtUri)) }
                .build()
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * The URI-bearing item we hand to Media3 for actual playback. mediaId is the queue
     * entryId (matching [PlaybackManager]'s internal scheme) so
     * `onMediaItemTransition` can look it up in [QueueEngine] without confusion.
     */
    private fun SongEntity.toPlayerMediaItem(entryId: String): MediaItem {
        val metadata =
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setDisplayTitle(title)
                .setSubtitle(artist)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .apply { if (albumArtUri != null) setArtworkUri(Uri.parse(albumArtUri)) }
                .build()
        return MediaItem.Builder()
            .setMediaId(entryId)
            .setUri(Uri.parse(contentUri))
            .setMediaMetadata(metadata)
            .build()
    }

    private fun paginate(
        items: List<MediaItem>,
        page: Int,
        pageSize: Int,
    ): List<MediaItem> {
        if (pageSize <= 0) return items
        val fromIndex = (page * pageSize).coerceAtMost(items.size)
        val toIndex = ((page + 1) * pageSize).coerceAtMost(items.size)
        if (fromIndex >= toIndex) return emptyList()
        return items.subList(fromIndex, toIndex)
    }

    private fun badListValue(): LibraryResult<ImmutableList<MediaItem>> =
        LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)

    /**
     * Coroutine → ListenableFuture bridge. Runs the suspend block on our scope, sets the
     * future when done. On exception, resolves to a "not supported" error rather than
     * propagating raw — Auto handles error results better than raw exceptions.
     */
    private inline fun <T : Any> async(
        crossinline block: suspend () -> LibraryResult<T>,
    ): ListenableFuture<LibraryResult<T>> {
        val future = SettableFuture.create<LibraryResult<T>>()
        scope.launch {
            try {
                future.set(block())
            } catch (throwable: Throwable) {
                Log.e(TAG, "browse callback failed", throwable)
                future.set(LibraryResult.ofError<T>(LibraryResult.RESULT_ERROR_UNKNOWN))
            }
        }
        return future
    }

    private companion object {
        const val TAG = "MediaBrowseTree"
        const val ID_ROOT = "root"
        const val ID_CAT_PLAYLISTS = "cat:playlists"
        const val ID_CAT_ALBUMS = "cat:albums"
        const val ID_CAT_ARTISTS = "cat:artists"
        const val ID_CAT_SONGS = "cat:songs"
    }
}

internal data class ParsedSongId(val songId: Long, val parentId: String)

/**
 * Parses `sg:<songId>@<parentId>`. Returns null if the input doesn't match the format.
 */
internal fun parseSongId(mediaId: String): ParsedSongId? {
    if (!mediaId.startsWith("sg:")) return null
    val rest = mediaId.removePrefix("sg:")
    val at = rest.indexOf('@')
    if (at <= 0 || at == rest.length - 1) return null
    val songId = rest.substring(0, at).toLongOrNull() ?: return null
    val parent = rest.substring(at + 1)
    return ParsedSongId(songId = songId, parentId = parent)
}

private fun encodeKey(key: String): String = Uri.encode(key)

private fun decodeKey(key: String): String = Uri.decode(key)
