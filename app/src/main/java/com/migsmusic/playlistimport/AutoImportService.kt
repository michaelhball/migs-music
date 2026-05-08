package com.migsmusic.playlistimport

import android.content.Context
import android.util.Log
import com.migsmusic.data.OrphanAudioTracker
import com.migsmusic.data.repository.LibraryRepository
import com.migsmusic.data.repository.PlaylistRepository
import com.migsmusic.playback.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Aggregate result of an auto-import batch. [unprocessed] is everything that didn't get
 * absorbed into a synced playlist — UI surfaces it as "available to import" cards.
 * [failures] is the strict subset that errored (parse / IO error) rather than just having
 * no matches; UI can show a snackbar for those because they indicate a real problem.
 */
data class ImportSummary(
    val imported: Int,
    val unprocessed: List<DiscoveredM3u>,
    val failures: List<Pair<DiscoveredM3u, String>>,
)

/** Visible to tests so they can assert outcomes per file directly. */
internal sealed interface SingleFileOutcome {
    data object Imported : SingleFileOutcome

    data object NoMatches : SingleFileOutcome

    data class Failed(val reason: String) : SingleFileOutcome
}

/**
 * Walks the app sync directory ([SYNC_DIR_PATH]), auto-imports every `.m3u` / `.m3u8` file
 * it finds as a synced playlist (replacing same-name synced playlists; never touching
 * manual ones), and deletes each consumed file from disk.
 *
 * Lives at the Application scope (on [com.migsmusic.AppContainer]) so it can be invoked
 * from anywhere — the Playlists ViewModel during normal UI flows, and a BroadcastReceiver
 * triggered remotely by the Mac sync app the moment it finishes pushing files. Both paths
 * call the same code, which keeps "what does auto-import mean" in one place.
 */
class AutoImportService(
    private val context: Context,
    private val playlistRepository: PlaylistRepository,
    private val libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
    private val orphanAudioTracker: OrphanAudioTracker,
) {
    /**
     * Serializes calls to [importAll]. Two callers can race: the AUTO_IMPORT
     * BroadcastReceiver fired by the Mac sync, and PlaylistsViewModel's
     * `refreshAvailableM3uFiles` that runs whenever the user opens the Playlists tab.
     * Without this mutex they can both see the same unprocessed `.m3u` file and run
     * the per-song-orphan diff with stale state — observed as false-positive orphan
     * entries on a fresh-install sync.
     */
    private val importMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun importAll(): ImportSummary =
        importMutex.withLock {
            importAllLocked()
        }

    private suspend fun importAllLocked(): ImportSummary {
        val files = scanForM3uFiles()
        Log.i(TAG, "importAll: ${files.size} m3u file(s) found in $SYNC_DIR_PATH")
        // Force a fresh MediaStore → Room scan before reading the library snapshot. The Mac
        // sync flow pushes audio files via adb and broadcasts AUTO_IMPORT immediately after,
        // so without this step `observeAllSongs().first()` may return a stale view that's
        // missing the just-pushed tracks (LibrarySyncObserver's debounced auto-rescan hasn't
        // fired yet, and the deprecated MEDIA_SCANNER_SCAN_FILE broadcast no-ops on Android 11+).
        runCatching { libraryRepository.scanDevice() }
            .onFailure { Log.w(TAG, "pre-import scanDevice failed", it) }

        // Read the sync manifest BEFORE per-file imports so we can honor the deleteOrphans
        // flag during each per-playlist replace — when an existing synced playlist's
        // contents change, songs that drop out of it (and aren't referenced by any other
        // playlist) get cleaned out of the library too.
        val manifest = readSyncManifest()
        val deleteOrphans = manifest?.deleteOrphans == true

        var imported = 0
        val unprocessed = mutableListOf<DiscoveredM3u>()
        val failures = mutableListOf<Pair<DiscoveredM3u, String>>()

        if (files.isNotEmpty()) {
            // Snapshot the library + build the matcher index ONCE, then reuse for every file.
            // Each file's matcher pass would otherwise rebuild three maps over the full library
            // — for a Mac sync landing 10 m3u's against a 5k-song library that's 30 redundant
            // associateBy passes.
            val library = libraryRepository.getAllSongsOnce()
            val index = M3uMatcherIndex(library)
            for (file in files) {
                when (val outcome = autoImportSingleFile(file, index, deleteOrphans)) {
                    SingleFileOutcome.Imported -> imported++
                    SingleFileOutcome.NoMatches -> unprocessed += file
                    is SingleFileOutcome.Failed -> {
                        unprocessed += file
                        failures += file to outcome.reason
                    }
                }
            }
        }

        // Apply manifest-driven prune (whole-playlist removal) using the manifest we already
        // parsed. Skips silently if no manifest was present this round.
        if (manifest != null) {
            runCatching { applySyncManifest(manifest) }
                .onFailure { Log.w(TAG, "manifest prune failed", it) }
        }

        return ImportSummary(imported = imported, unprocessed = unprocessed, failures = failures)
    }

    private data class ParsedManifest(
        val keepNames: Set<String>,
        val deleteOrphans: Boolean,
    )

    /**
     * Reads `<sync dir>/.migs-sync-manifest` if present. Returns null if absent.
     * Doesn't delete — that happens after the prune step in [applySyncManifest].
     */
    private suspend fun readSyncManifest(): ParsedManifest? {
        val file = File(SYNC_DIR_PATH, ".migs-sync-manifest")
        val text =
            withContext(Dispatchers.IO) {
                runCatching { if (file.exists()) file.readText() else null }.getOrNull()
            } ?: return null

        var deleteOrphans = false
        val keepNames = mutableSetOf<String>()
        for (line in text.lineSequence()) {
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
        return ParsedManifest(keepNames = keepNames, deleteOrphans = deleteOrphans)
    }

    /**
     * Whole-playlist prune step: deletes any synced playlist whose name isn't in the
     * manifest's keep list, optionally deleting orphan audio files. Then deletes the
     * manifest itself.
     *
     * If the currently-playing song belongs to a pruned playlist, playback is stopped to
     * avoid leaving an orphan track in the mini-player (same behavior as the user-facing
     * delete flow in PlaylistsViewModel).
     */
    private suspend fun applySyncManifest(manifest: ParsedManifest) {
        val syncedPlaylists = playlistRepository.getSyncedPlaylists()
        val toRemove = syncedPlaylists.filter { it.name !in manifest.keepNames }
        if (toRemove.isNotEmpty()) {
            Log.i(TAG, "pruning ${toRemove.size} synced playlist(s) not in manifest: ${toRemove.map { it.name }}")

            // Compute orphan song ids BEFORE deleting playlists, otherwise the rows we'd be
            // diffing against are already gone.
            val orphanSongIds =
                if (manifest.deleteOrphans) {
                    playlistRepository.getOrphanSongIds(toRemove.map { it.id })
                } else {
                    emptyList()
                }

            val currentSongId = playbackController.currentSongId.value
            for (playlist in toRemove) {
                if (currentSongId != null) {
                    val ids = playlistRepository.getPlaylistSongIds(playlist.id)
                    if (currentSongId in ids) {
                        playbackController.stopAndClearQueue()
                    }
                }
                playlistRepository.deletePlaylist(playlist.id)
            }

            if (orphanSongIds.isNotEmpty()) {
                Log.i(TAG, "audio-cleanup: marking ${orphanSongIds.size} orphan song(s) for deletion via MediaStore")
                requestOrphanAudioDeletion(orphanSongIds)
            }
        }

        // Delete the manifest file directly — it lives in our app's media dir so File.delete
        // works without permissions.
        withContext(Dispatchers.IO) {
            runCatching { File(SYNC_DIR_PATH, ".migs-sync-manifest").delete() }
        }
    }

    /**
     * Drops the SongEntity rows for the given orphan song ids so the library doesn't show
     * ghost entries pointing at songs that should no longer be there. Doesn't actually
     * delete the audio files from disk — Android 11+ refuses to grant SAF write to
     * `/sdcard/Music`, and we can't pop a `MediaStore.createDeleteRequest` confirmation
     * dialog from a background BroadcastReceiver. Audio file deletion belongs in a future
     * Settings screen that runs in the foreground and can show a system confirm.
     */
    private suspend fun requestOrphanAudioDeletion(songIds: List<Long>) {
        // Capture content URIs BEFORE dropping the SongEntity rows — once they're gone, we
        // can't reconstruct the URI, and the Settings screen needs them for
        // MediaStore.createDeleteRequest. The actual audio file under /sdcard/Music remains
        // until the user triggers the system delete dialog from Settings, which is the
        // only legal path (background BroadcastReceiver can't show a system confirm).
        val songs = libraryRepository.getSongsByIds(songIds)
        orphanAudioTracker.add(songs.map { it.contentUri })
        Log.i(TAG, "audio-cleanup: marking ${songIds.size} song row(s) as removed; files remain on disk pending Settings cleanup")
        libraryRepository.deleteSongs(songIds)
    }

    /**
     * Imports a single discovered file. Returns:
     * - [SingleFileOutcome.Imported] — parsed, matched, written, source deleted.
     * - [SingleFileOutcome.NoMatches] — parsed fine but no library songs matched. The file
     *   is left on disk and surfaced via the "Available to import" UI fallback so the user
     *   can decide what to do (typically: nothing, since songs aren't actually present).
     * - [SingleFileOutcome.Failed] — IO error, parse error, or upsert exception. Returned
     *   reason gets surfaced via snackbar so the user knows something's wrong.
     */
    internal suspend fun autoImportSingleFile(
        file: DiscoveredM3u,
        index: M3uMatcherIndex,
        deleteOrphans: Boolean = false,
    ): SingleFileOutcome =
        runCatching {
            val content =
                withContext(Dispatchers.IO) {
                    runCatching { File(file.absolutePath).readText() }.getOrNull()
                }
            if (content == null) {
                Log.w(TAG, "  content read returned null for ${file.displayName}")
                return@runCatching SingleFileOutcome.Failed("could not read file")
            }
            if (content.isBlank()) {
                return@runCatching SingleFileOutcome.Failed("file is empty")
            }

            val matchResult =
                withContext(Dispatchers.Default) {
                    matchM3uEntries(parseM3u(content), index)
                }
            val newSongIds = matchResult.matched.map { it.song.id }
            Log.i(
                TAG,
                "${file.displayName}: matched=${matchResult.matched.size} unmatched=${matchResult.unmatched.size}",
            )
            if (newSongIds.isEmpty()) return@runCatching SingleFileOutcome.NoMatches

            val playlistName =
                file.displayName.removeSuffix(".m3u").removeSuffix(".m3u8")

            // Capture the playlist's previous song set BEFORE upsert — if this is the
            // replace branch (existing synced playlist), we'll diff against the new set
            // to identify per-song removals for orphan cleanup.
            val existing = playlistRepository.getSyncedPlaylists().firstOrNull { it.name == playlistName.trim() }
            val priorSongIds: Set<Long> =
                if (existing != null) playlistRepository.getPlaylistSongIds(existing.id) else emptySet()

            val playlistId = playlistRepository.upsertSyncedPlaylist(playlistName, newSongIds)
            Log.i(TAG, "  upserted playlist id=$playlistId with ${newSongIds.size} song(s)")

            // Per-song orphan cleanup: songs that were in the playlist before this sync but
            // aren't anymore, AND aren't referenced by any other playlist (synced or manual).
            // Gated on the deleteOrphans flag from the manifest. Catches the case where the
            // user removes a song from a still-synced playlist on the Mac.
            if (deleteOrphans && priorSongIds.isNotEmpty()) {
                val removed = priorSongIds - newSongIds.toSet()
                if (removed.isNotEmpty()) {
                    val nowOrphan =
                        removed.filter { songId -> playlistRepository.songIsUnreferenced(songId) }
                    if (nowOrphan.isNotEmpty()) {
                        Log.i(TAG, "  per-song cleanup: ${nowOrphan.size} removed song(s) now orphan")
                        requestOrphanAudioDeletion(nowOrphan)
                    }
                }
            }

            // Delete the M3U file directly — it lives under our app media dir so File.delete
            // works without permissions.
            val deleted =
                withContext(Dispatchers.IO) {
                    runCatching { File(file.absolutePath).delete() }.getOrDefault(false)
                }
            if (!deleted) Log.w(TAG, "  source delete failed for ${file.displayName}")
            SingleFileOutcome.Imported
        }.getOrElse {
            Log.w(TAG, "Auto-import failed for ${file.displayName}", it)
            SingleFileOutcome.Failed(it.message ?: it.javaClass.simpleName)
        }

    private companion object {
        const val TAG = "AutoImportService"
    }
}
