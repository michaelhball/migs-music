package com.migsmusic.playlistimport

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.migsmusic.data.repository.LibraryRepository
import com.migsmusic.data.repository.PlaylistRepository
import com.migsmusic.playback.PlaybackManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Walks a granted SAF tree, auto-imports every `.m3u` / `.m3u8` file it finds as a synced
 * playlist (replacing same-name synced playlists; never touching manual ones), and deletes
 * each consumed file from disk.
 *
 * Lives at the Application scope (on [com.migsmusic.AppContainer]) so it can be invoked
 * from anywhere — the Playlists ViewModel during normal UI flows, and a BroadcastReceiver
 * triggered remotely by the Mac sync app the moment it finishes pushing files. Both paths
 * call the same code, which keeps "what does auto-import mean" in one place.
 *
 * Returns the list of files that were *not* imported (zero matches, parse failure, IO
 * error). The caller can surface those for manual handling.
 */
class AutoImportService(
    private val context: Context,
    private val playlistRepository: PlaylistRepository,
    private val libraryRepository: LibraryRepository,
    private val playbackManager: PlaybackManager,
) {
    suspend fun importAllInTree(treeUri: Uri): List<DiscoveredM3u> {
        val files = scanForM3uFiles(context, treeUri)
        // Even if no m3u files were pushed this round, we still want to honor the manifest —
        // a sync that only deselects playlists pushes nothing but a manifest, and the receiver
        // needs to prune those.
        Log.i(TAG, "importAllInTree: ${files.size} m3u file(s) found")
        // Force a fresh MediaStore → Room scan before reading the library snapshot. The Mac
        // sync flow pushes audio files via adb and broadcasts AUTO_IMPORT immediately after,
        // so without this step `observeAllSongs().first()` may return a stale view that's
        // missing the just-pushed tracks (LibrarySyncObserver's debounced auto-rescan hasn't
        // fired yet, and the deprecated MEDIA_SCANNER_SCAN_FILE broadcast no-ops on Android 11+).
        runCatching { libraryRepository.scanDevice() }
            .onFailure { Log.w(TAG, "pre-import scanDevice failed", it) }
        // Snapshot the library once — the matcher pre-indexes from it, so re-running per
        // file would just rebuild the same maps.
        val library = libraryRepository.observeAllSongs().first()
        val unprocessed = mutableListOf<DiscoveredM3u>()
        for (file in files) {
            if (!autoImportSingleFile(file, library)) {
                unprocessed += file
            }
        }

        // After importing, honor any sync manifest the Mac side dropped: prune synced
        // playlists whose names aren't present (mirror semantics — uncheck on the Mac =
        // remove from the phone). Manual playlists are never touched.
        runCatching { pruneSyncedPlaylistsToManifest(treeUri) }
            .onFailure { Log.w(TAG, "manifest prune failed", it) }

        return unprocessed
    }

    /**
     * Reads the optional sync manifest at `<treeUri>/.migs-sync-manifest`, deletes any
     * synced playlists not listed in it, then deletes the manifest. No-op if the file
     * isn't there.
     *
     * Manifest format: UTF-8, one playlist name per line. An optional first line of the
     * form `#opts:key=value,key=value` carries options:
     *   - `deleteOrphans=true` — also delete the audio files of removed playlists when no
     *     other playlist (manual or synced) still references them.
     *
     * If the currently-playing song belongs to a pruned playlist, playback is stopped to
     * avoid leaving an orphan track in the mini-player (same behavior as the user-facing
     * delete flow in PlaylistsViewModel).
     */
    private suspend fun pruneSyncedPlaylistsToManifest(treeUri: Uri) {
        // Read via SAF rather than direct java.io.File: scoped storage on Android 11+
        // returns EACCES for dot-files (hidden) under /sdcard/Music even when normal
        // .m3u reads succeed. SAF with a constructed doc URI works for the same path
        // (verified by the delete flow), so we use it here too.
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "primary:Music/.migs-sync-manifest")
        val manifestText =
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(docUri)
                        ?.use { it.bufferedReader().readText() }
                }.getOrNull()
            } ?: return // No manifest pushed this round; nothing to prune.

        var deleteOrphans = false
        val keepNames = mutableSetOf<String>()
        for (line in manifestText.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("#opts:")) {
                trimmed.removePrefix("#opts:")
                    .split(",")
                    .forEach { opt ->
                        val (k, v) = opt.split("=", limit = 2).let { it.getOrNull(0) to it.getOrNull(1) }
                        if (k?.trim() == "deleteOrphans") deleteOrphans = v?.trim() == "true"
                    }
                continue
            }
            keepNames += trimmed
        }

        val syncedPlaylists = playlistRepository.getSyncedPlaylists()
        val toRemove = syncedPlaylists.filter { it.name !in keepNames }
        if (toRemove.isNotEmpty()) {
            Log.i(TAG, "pruning ${toRemove.size} synced playlist(s) not in manifest: ${toRemove.map { it.name }}")

            // Compute orphan song ids BEFORE deleting playlists, otherwise the rows we'd be
            // diffing against are already gone.
            val orphanSongIds =
                if (deleteOrphans) {
                    playlistRepository.getOrphanSongIds(toRemove.map { it.id })
                } else {
                    emptyList()
                }

            val currentSongId = playbackManager.currentSongId.value
            for (playlist in toRemove) {
                if (currentSongId != null) {
                    val ids = playlistRepository.getPlaylistSongIds(playlist.id)
                    if (currentSongId in ids) {
                        playbackManager.stopAndClearQueue()
                    }
                }
                playlistRepository.deletePlaylist(playlist.id)
            }

            if (orphanSongIds.isNotEmpty()) {
                deleteOrphanAudioFiles(treeUri, orphanSongIds)
            }
        }

        // Delete the manifest file via SAF — direct File.delete is blocked under scoped
        // storage on /sdcard/Music for files we didn't create. The constructed doc URI is
        // the same one we used to read the manifest above.
        val deleted =
            withContext(Dispatchers.IO) {
                runCatching { DocumentFile.fromSingleUri(context, docUri)?.delete() ?: false }
                    .getOrDefault(false)
            }
        if (!deleted) Log.w(TAG, "manifest delete failed at $docUri")
    }

    /**
     * For each orphaned song id, look up the absolute file path via MediaStore, build the
     * SAF doc URI under [treeUri] (which the user granted at /sdcard/Music), and delete the
     * underlying file. Then drop the SongEntity rows from Room so the library doesn't show
     * ghost entries that won't play.
     *
     * Best-effort per file: a delete failure for one orphan doesn't stop the others.
     * Songs whose files live outside the granted tree (e.g. RELATIVE_PATH "Movies/..." for
     * some weird MTP transfer) are skipped — we have no permission there.
     */
    private suspend fun deleteOrphanAudioFiles(
        treeUri: Uri,
        songIds: List<Long>,
    ) {
        val paths = libraryRepository.getSongAbsolutePaths(songIds)
        if (paths.isEmpty()) return
        Log.i(TAG, "audio-cleanup: ${paths.size} orphan file(s) to delete")
        val deletedSongIds = mutableListOf<Long>()
        for ((songId, absolutePath) in paths) {
            val docUri = absolutePathToTreeDocUri(treeUri, absolutePath) ?: continue
            val deleted =
                withContext(Dispatchers.IO) {
                    runCatching { DocumentFile.fromSingleUri(context, docUri)?.delete() ?: false }
                        .getOrDefault(false)
                }
            if (deleted) {
                deletedSongIds += songId
            } else {
                Log.w(TAG, "audio-cleanup: failed to delete $absolutePath")
            }
        }
        if (deletedSongIds.isNotEmpty()) {
            libraryRepository.deleteSongs(deletedSongIds)
        }
    }

    /**
     * Maps an absolute filesystem path under `/storage/emulated/0/<sub>` to a SAF document
     * URI for [treeUri] (which is the granted Music tree, doc id `primary:Music`). Returns
     * null if the path is outside the tree — we can't delete it via this grant.
     */
    private fun absolutePathToTreeDocUri(
        treeUri: Uri,
        absolutePath: String,
    ): Uri? {
        val musicRoot = "/storage/emulated/0/Music/"
        val rel = absolutePath.removePrefix(musicRoot)
        if (rel == absolutePath) return null // path didn't start with /storage/emulated/0/Music/
        val docId = "primary:Music/$rel"
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    }

    /**
     * Imports a single discovered file. Returns true if the file was successfully consumed
     * (parsed, matched against the library, written into a synced playlist, source deleted).
     * Returns false on any failure mode — the file is left on disk so the caller can decide
     * what to surface to the user.
     */
    private suspend fun autoImportSingleFile(
        file: DiscoveredM3u,
        library: List<com.migsmusic.data.local.entity.SongEntity>,
    ): Boolean =
        runCatching {
            val content =
                withContext(Dispatchers.IO) {
                    // Prefer direct fs read when available — the OnePlus ExternalStorageProvider
                    // returns 0 children for /sdcard/Music via SAF, but per-file content read
                    // through the same provider works fine. Falling back to SAF on devices
                    // where we couldn't get an absolutePath (cloud-provider trees, removable
                    // SD cards we haven't taught the path mapping for yet).
                    file.absolutePath?.let { java.io.File(it).readText() }
                        ?: context.contentResolver.openInputStream(file.uri)?.use { it.bufferedReader().readText() }
                } ?: run {
                    Log.w(TAG, "  content read returned null for ${file.displayName}")
                    return@runCatching false
                }
            if (content.isBlank()) return@runCatching false

            val matchResult =
                withContext(Dispatchers.Default) {
                    matchM3uEntries(parseM3u(content), library)
                }
            val songIds = matchResult.matched.map { it.song.id }
            Log.i(
                TAG,
                "${file.displayName}: matched=${matchResult.matched.size} unmatched=${matchResult.unmatched.size}",
            )
            if (songIds.isEmpty()) return@runCatching false

            val playlistName =
                file.displayName.removeSuffix(".m3u").removeSuffix(".m3u8")
            val playlistId = playlistRepository.upsertSyncedPlaylist(playlistName, songIds)
            Log.i(TAG, "  upserted playlist id=$playlistId with ${songIds.size} song(s)")

            // Best-effort delete. SAF delete via the constructed document URI works on
            // OnePlus even though SAF *listing* is broken — confirmed via a write-probe test.
            val deleted =
                withContext(Dispatchers.IO) {
                    runCatching { DocumentFile.fromSingleUri(context, file.uri)?.delete() ?: false }
                        .getOrDefault(false)
                }
            if (!deleted) Log.w(TAG, "  source delete failed for ${file.displayName}")
            true
        }.getOrElse {
            Log.w(TAG, "Auto-import failed for ${file.displayName}", it)
            false
        }

    private companion object {
        const val TAG = "AutoImportService"
    }
}
