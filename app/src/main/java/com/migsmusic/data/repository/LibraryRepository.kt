package com.migsmusic.data.repository

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.migsmusic.data.local.dao.SongDao
import com.migsmusic.data.local.entity.SongEntity
import com.migsmusic.data.local.model.AlbumSummary
import com.migsmusic.data.local.model.ArtistSummary
import com.migsmusic.data.local.model.FolderSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

class LibraryRepository(
    private val context: Context,
    private val songDao: SongDao,
) {
    fun observeAllSongs(): Flow<List<SongEntity>> = songDao.observeAllSongs()

    /**
     * Snapshot the songs table once. Use this instead of `observeAllSongs().first()` for
     * one-shot reads (M3U import, auto-import receiver) — Flow's first emission has to set
     * up the invalidation tracker and round-trip through Dispatchers.IO before returning,
     * which adds noticeable latency on a cold DB.
     */
    suspend fun getAllSongsOnce(): List<SongEntity> = songDao.getAllSongs()

    fun observeFolders(): Flow<List<FolderSummary>> = songDao.observeFolders()

    fun observeSongsInFolder(folderPath: String): Flow<List<SongEntity>> = songDao.observeSongsInFolder(folderPath)

    fun searchSongs(query: String): Flow<List<SongEntity>> = songDao.searchSongs(query)

    suspend fun getSongsByIds(ids: List<Long>): List<SongEntity> {
        if (ids.isEmpty()) return emptyList()
        val indexed = songDao.getSongsByIds(ids).associateBy { it.id }
        return ids.mapNotNull(indexed::get)
    }

    suspend fun isEmpty(): Boolean = songDao.getAllSongIds().isEmpty()

    /**
     * Removes [ids] from the songs table. Used by the orphan-cleanup path of the sync flow
     * to drop ghost entries (songs no longer referenced by any synced playlist whose files
     * we plan to ask the user to delete via `MediaStore.createDeleteRequest` from the
     * Settings screen).
     */
    suspend fun deleteSongs(ids: List<Long>) {
        if (ids.isEmpty()) return
        ids.chunked(500).forEach { songDao.deleteByIds(it) }
    }

    /**
     * Returns the immediate child folders directly under [parentPath], plus their cumulative
     * song counts. Pass an empty string to get the top-level folders.
     *
     * Backed by [SongDao.observeFolderCountsUnder], which returns one row per distinct
     * folderPath at-or-below [parentPath] (≤ a few hundred rows even on a 10k-song library).
     * We then group those rows by their next-path-segment-relative-to-[parentPath]. Previously
     * this mapped the full songs flow and rebuilt the segment map per row — fine at 1k songs,
     * a real cliff at 10k where every MediaStore tick re-emitted ~10k SongEntity objects
     * three times in parallel (see HierarchicalFolderView).
     */
    fun observeSubfolders(parentPath: String): Flow<List<FolderSummary>> =
        songDao.observeFolderCountsUnder(parentPath.trimEnd('/')).map { rows ->
            buildSubfoldersFromAggregates(rows, parentPath)
        }.flowOn(Dispatchers.Default)

    /** Songs whose `folderPath` equals exactly [parentPath] (no deeper). SQL-side filter + sort. */
    fun observeDirectSongsIn(parentPath: String): Flow<List<SongEntity>> = songDao.observeSongsInFolder(parentPath)

    /** All songs under [parentPath] including subfolders. Used for "Play Folder" at any level. */
    fun observeSongsRecursivelyIn(parentPath: String): Flow<List<SongEntity>> = songDao.observeSongsRecursivelyIn(parentPath.trimEnd('/'))

    fun observeAlbums(): Flow<List<AlbumSummary>> = songDao.observeAlbums()

    fun observeArtists(): Flow<List<ArtistSummary>> = songDao.observeArtists()

    fun observeSongsByArtist(artist: String): Flow<List<SongEntity>> = songDao.observeSongsByArtist(artist)

    fun observeSongsByAlbum(albumKey: String): Flow<List<SongEntity>> = songDao.observeSongsByAlbum(albumKey)

    private fun buildSubfoldersFromAggregates(
        rows: List<FolderSummary>,
        parentPath: String,
    ): List<FolderSummary> {
        val normalizedParent = parentPath.trimEnd('/')
        val prefix = if (normalizedParent.isEmpty()) "" else "$normalizedParent/"

        val accumulator = mutableMapOf<String, Int>()
        for (row in rows) {
            val path = row.path
            // Defensive: SQL filter should already exclude these, but in case the parent string
            // representation diverges (e.g. trailing slashes) we re-check here.
            if (normalizedParent.isNotEmpty() && !path.startsWith(prefix) && path != normalizedParent) continue
            val rel = if (normalizedParent.isEmpty()) path else path.removePrefix(prefix)
            val nextSegment = rel.substringBefore('/')
            if (nextSegment.isEmpty()) continue
            accumulator.merge(nextSegment, row.songCount, Int::plus)
        }
        return accumulator.map { (name, count) ->
            val childPath = if (normalizedParent.isEmpty()) name else "$normalizedParent/$name"
            FolderSummary(path = childPath, name = name, songCount = count)
        }.sortedBy { it.name.lowercase() }
    }

    suspend fun scanDevice(): Int =
        withContext(Dispatchers.IO) {
            val contentResolver = context.contentResolver
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

            val projection =
                buildList {
                    add(MediaStore.Audio.Media._ID)
                    add(MediaStore.Audio.Media.ALBUM_ID)
                    add(MediaStore.Audio.Media.TITLE)
                    add(MediaStore.Audio.Media.ARTIST)
                    add(MediaStore.Audio.Media.ALBUM)
                    add(MediaStore.Audio.Media.DURATION)
                    add(MediaStore.Audio.Media.TRACK)
                    add(MediaStore.Audio.Media.DATE_ADDED)
                    add(MediaStore.Audio.Media.DATE_MODIFIED)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        add(MediaStore.Audio.Media.RELATIVE_PATH)
                    }
                    // Always pull DATA (the absolute file path). It's deprecated on Q+ but
                    // still readable, and we need it for MediaScannerConnection requests
                    // when MediaStore returned tagless rows (see below).
                    add(MediaStore.Audio.Media.DATA)
                }.toTypedArray()

            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            val songs = mutableListOf<SongEntity>()
            // Files where MediaStore returned tagless metadata. These get a follow-up
            // MediaScannerConnection request after the main scan so the next ContentObserver
            // tick reads proper artist/title. Happens when files are added by ANY mechanism
            // that doesn't trigger the platform's media scanner — `adb push`, some MTP
            // implementations, third-party file managers, etc.
            val tagless = mutableListOf<String>()

            val cursor =
                contentResolver.query(collection, projection, selection, null, sortOrder)
                    ?: throw IllegalStateException("MediaStore query returned null; aborting scan to avoid library wipe")
            cursor.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val albumIdIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val trackIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val dateAddedIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val dateModifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val absolutePathIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val displayPathColumn =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                    } else {
                        absolutePathIndex
                    }

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val albumId = cursor.getLong(albumIdIndex).takeIf { it > 0L }
                    val rawTrack = cursor.getInt(trackIndex)
                    val relativeOrAbsolutePath = cursor.getString(displayPathColumn).orEmpty()
                    val folderInfo = deriveFolderInfo(relativeOrAbsolutePath)
                    val contentUri = ContentUris.withAppendedId(collection, id).toString()
                    val albumArtUri =
                        albumId?.let {
                            ContentUris.withAppendedId(
                                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                                it,
                            ).toString()
                        }

                    val rawArtist = cursor.getString(artistIndex).orEmpty()
                    val rawTitle = cursor.getString(titleIndex).orEmpty()
                    val absolutePath = cursor.getString(absolutePathIndex).orEmpty()
                    if (absolutePath.isNotEmpty() && (rawArtist.isBlank() || rawTitle.isBlank())) {
                        tagless += absolutePath
                    }

                    songs +=
                        SongEntity(
                            id = id,
                            contentUri = contentUri,
                            albumId = albumId,
                            title = rawTitle.ifBlank { "Unknown title" },
                            artist = rawArtist.ifBlank { "Unknown artist" },
                            album = cursor.getString(albumIndex).orEmpty().ifBlank { "Unknown album" },
                            durationMs = cursor.getLong(durationIndex),
                            trackNumber = rawTrack % 1000,
                            discNumber = rawTrack / 1000,
                            folderPath = folderInfo.first,
                            folderName = folderInfo.second,
                            albumArtUri = albumArtUri,
                            dateAddedSeconds = cursor.getLong(dateAddedIndex),
                            dateModifiedSeconds = cursor.getLong(dateModifiedIndex),
                            absolutePath = absolutePath,
                        )
                }
            }

            // Kick off a media-scanner pass for any tagless files. The platform reads ID3
            // tags asynchronously and updates MediaStore; LibrarySyncObserver picks up
            // that change on the next debounce and re-runs scanDevice, which then stores
            // proper artist/title. This is what makes the app robust to files added via
            // mechanisms that don't auto-trigger the scanner (adb push, some file managers).
            if (tagless.isNotEmpty()) {
                Log.i(TAG, "Requesting MediaScanner refresh for ${tagless.size} tagless files")
                MediaScannerConnection.scanFile(
                    context,
                    tagless.toTypedArray(),
                    null,
                    null,
                )
            }

            if (songs.isEmpty()) {
                songDao.clearAll()
                return@withContext 0
            }

            // Chunk the upsert so a 50k-song library doesn't pin a single all-or-nothing
            // transaction (and a single huge SQLite statement). [SongDao.upsertAllChunked]
            // wraps the per-chunk loop in ONE Room transaction so we still get a single
            // commit + fsync at the end — without it each chunk was its own implicit
            // transaction, multiplying disk traffic ~5-10× on cold-start scans.
            //
            // Pre-v7 we needed to detect+remap MediaStore _ID reassignments here (because
            // playlist_songs FK'd on songs.id, and a re-tagged file could get a new _ID).
            // Post-v7 the cross-ref FKs target absolutePath, which doesn't change on
            // re-tagging — so the remap dance is gone.
            songDao.upsertAllChunked(songs.chunked(UPSERT_CHUNK_SIZE))

            // Prune songs whose file is no longer in the MediaStore scan AND not on disk.
            // The on-disk check guards against the transient state where MediaScanner has
            // briefly cleared IS_MUSIC for a file it's re-reading tags on — we don't want
            // to delete the SongEntity (cascading hearts and playlist refs) just because
            // the tag scanner is mid-flight. Uses absolutePath as the diff key, since _ID
            // may have changed for files that survived re-tagging.
            pruneMissingSongs(songs.asSequence().map { it.absolutePath }.filter { it.isNotEmpty() }.toSet())
            songs.size
        }

    private companion object {
        private const val UPSERT_CHUNK_SIZE = 1_000
        private const val TAG = "LibraryRepository"
    }

    /**
     * Delete SongEntity rows whose `absolutePath` is no longer present in the just-completed
     * MediaStore scan ([scannedPaths]) AND whose file isn't on disk. The disk-check guards
     * against MediaScanner's tag-rescan transient: while MediaScanner re-reads tags it
     * briefly clears `IS_MUSIC`, so our `IS_MUSIC != 0` query doesn't see the row — but the
     * file is still very much there. Without the guard we'd delete the SongEntity and
     * (post-v7) CASCADE the playlist + heart references for a file that's about to come
     * right back. With the guard, the transient state is invisible to us.
     *
     * Genuinely-deleted files: not in scan, not on disk → pruned. CASCADE fires correctly.
     */
    private suspend fun pruneMissingSongs(scannedPaths: Set<String>) {
        val all = songDao.getIdAndPaths()
        if (all.isEmpty()) return
        val toPruneIds =
            all.asSequence()
                .filter { it.absolutePath !in scannedPaths }
                .filter {
                    // File.exists() is a stat() syscall — fast (microseconds). Worst case
                    // here is a library where many songs have changed; that's still well
                    // under a second of stat work for tens of thousands of files.
                    !File(it.absolutePath).exists()
                }
                .map { it.id }
                .toList()
        if (toPruneIds.isEmpty()) return
        Log.i(TAG, "scanDevice: pruning ${toPruneIds.size} song row(s) with missing files")
        toPruneIds.chunked(500).forEach { chunk ->
            songDao.deleteByIds(chunk)
        }
    }

    private fun deriveFolderInfo(path: String): Pair<String, String> {
        if (path.isBlank()) return "" to "Unknown folder"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val normalized = path.trimEnd('/')
            val folderName = normalized.substringAfterLast('/').ifBlank { normalized }
            return normalized to folderName
        }

        val file = File(path)
        val parent = file.parent.orEmpty()
        return parent to parent.substringAfterLast(File.separator).ifBlank { "Unknown folder" }
    }
}
