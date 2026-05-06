package com.migsmusic.playlistimport

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.migsmusic.data.repository.LibraryRepository
import com.migsmusic.data.repository.PlaylistRepository
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
) {
    suspend fun importAllInTree(treeUri: Uri): List<DiscoveredM3u> {
        val files = scanForM3uFiles(context, treeUri)
        if (files.isEmpty()) return emptyList()
        // Snapshot the library once — the matcher pre-indexes from it, so re-running per
        // file would just rebuild the same maps.
        val library = libraryRepository.observeAllSongs().first()
        val unprocessed = mutableListOf<DiscoveredM3u>()
        for (file in files) {
            if (!autoImportSingleFile(file, library)) {
                unprocessed += file
            }
        }
        return unprocessed
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
                    context.contentResolver.openInputStream(file.uri)?.use { it.bufferedReader().readText() }
                } ?: return@runCatching false
            if (content.isBlank()) return@runCatching false

            val songIds =
                withContext(Dispatchers.Default) {
                    val entries = parseM3u(content)
                    matchM3uEntries(entries, library).matched.map { it.song.id }
                }
            if (songIds.isEmpty()) return@runCatching false

            val playlistName =
                file.displayName.removeSuffix(".m3u").removeSuffix(".m3u8")
            playlistRepository.upsertSyncedPlaylist(playlistName, songIds)

            // Best-effort delete. If it fails (permission revoked, transient IO), the next
            // scan will try again — the upsert is idempotent.
            withContext(Dispatchers.IO) {
                runCatching { DocumentFile.fromSingleUri(context, file.uri)?.delete() }
            }
            true
        }.getOrElse {
            Log.w(TAG, "Auto-import failed for ${file.displayName}", it)
            false
        }

    private companion object {
        const val TAG = "AutoImportService"
    }
}
