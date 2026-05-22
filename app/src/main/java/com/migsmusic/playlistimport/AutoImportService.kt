package com.migsmusic.playlistimport

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import com.migsmusic.data.OrphanAudioTracker
import com.migsmusic.data.repository.LibraryRepository
import com.migsmusic.data.repository.PlaylistRepository
import com.migsmusic.playback.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

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
        // Consume the sync-stats sidecar the Mac script leaves behind. Its audioPushed
        // count is logged for diagnostics only — it used to gate the library refresh, but
        // that was unsafe: a track can be on disk yet absent from MediaStore regardless of
        // what THIS sync pushed (an earlier interrupted sync, lazy background indexing).
        // ensureLibraryCovers decides the refresh from what the .m3u files actually need.
        val stats = readSyncStats()
        Log.i(TAG, "importAll: sync reported ${stats?.audioPushed ?: "?"} audio file(s) pushed")

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
            // associateBy passes. ensureLibraryCovers rebuilds it if the .m3u files reference
            // tracks the snapshot doesn't yet know about.
            val index =
                ensureLibraryCovers(files, M3uMatcherIndex(libraryRepository.getAllSongsOnce()))
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

    private data class SyncStats(val audioPushed: Int)

    /**
     * Reads `<sync dir>/.migs-sync-stats` (key=value lines) if present. Pushed by the Mac
     * sync script after the tar-stream completes; tells us whether any audio files were
     * actually new this round so we can skip the MediaStore rescan on no-op resyncs.
     * Returns null if absent or unreadable — callers treat that as "unknown, scan to be safe".
     * Deletes the file on read (it's per-sync, not durable state).
     */
    private suspend fun readSyncStats(): SyncStats? {
        val file = File(SYNC_DIR_PATH, ".migs-sync-stats")
        return withContext(Dispatchers.IO) {
            runCatching {
                if (!file.exists()) return@runCatching null
                val text = file.readText()
                file.delete()
                var audioPushed: Int? = null
                for (line in text.lineSequence()) {
                    val (k, v) = line.split("=", limit = 2).let { it.getOrNull(0)?.trim() to it.getOrNull(1)?.trim() }
                    if (k == "audioPushed") audioPushed = v?.toIntOrNull()
                }
                audioPushed?.let { SyncStats(audioPushed = it) }
            }.getOrNull()
        }
    }

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

    /**
     * Ensures the library [index] covers every track path referenced by [files]. The Mac
     * sync lands audio with adb/tar, which does NOT register files with MediaStore —
     * Android's media scanner only picks them up lazily, so a just-pushed track can be on
     * disk yet absent from the library, and the import would silently drop it.
     *
     * For any referenced path the [index] doesn't already know, run a MediaScanner pass
     * (which registers it with MediaStore), refresh Room from MediaStore, and return a
     * rebuilt index. When every referenced track is already covered — the common no-op
     * resync — the passed-in index is returned untouched and nothing is scanned.
     */
    private suspend fun ensureLibraryCovers(
        files: List<DiscoveredM3u>,
        index: M3uMatcherIndex,
    ): M3uMatcherIndex {
        val referenced =
            withContext(Dispatchers.IO) {
                files
                    .mapNotNull { f -> runCatching { File(f.absolutePath).readText() }.getOrNull() }
                    .flatMap { content -> parseM3u(content) }
                    .map { it.rawPath }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }
        val unindexed = referenced.filterNot { it in index.byAbsolutePath }
        if (unindexed.isEmpty()) return index

        Log.i(TAG, "ensureLibraryCovers: ${unindexed.size} referenced track(s) not in library — scanning")
        scanPaths(unindexed)
        runCatching { libraryRepository.scanDevice() }
            .onFailure { Log.w(TAG, "post-scan scanDevice failed", it) }
        return M3uMatcherIndex(libraryRepository.getAllSongsOnce())
    }

    /**
     * Runs a MediaScanner pass over [paths] and suspends until every file has been scanned
     * (or [SCAN_TIMEOUT_MS] elapses — a stuck scan must not hang the import forever).
     * MediaScanner resolves /sdcard symlinks itself, but we normalise to the canonical
     * /storage/emulated/0 form it stores paths under regardless.
     */
    private suspend fun scanPaths(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val canonical =
            paths
                .map {
                    if (it.startsWith(SDCARD_PREFIX)) {
                        STORAGE_EMULATED_PREFIX + it.removePrefix(SDCARD_PREFIX)
                    } else {
                        it
                    }
                }
                .toTypedArray()
        withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                val remaining = AtomicInteger(canonical.size)
                MediaScannerConnection.scanFile(context, canonical, null) { _, _ ->
                    if (remaining.decrementAndGet() == 0 && cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    private companion object {
        const val TAG = "AutoImportService"
        const val SCAN_TIMEOUT_MS = 120_000L
        const val SDCARD_PREFIX = "/sdcard/"
        const val STORAGE_EMULATED_PREFIX = "/storage/emulated/0/"
    }
}
