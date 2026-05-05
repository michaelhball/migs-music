package com.migsmusic.data.repository

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
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
     * Returns the immediate child folders directly under [parentPath], plus their cumulative
     * song counts. Pass an empty string to get the top-level folders.
     */
    fun observeSubfolders(parentPath: String): Flow<List<FolderSummary>> =
        songDao.observeAllSongs().map { all ->
            buildSubfolders(all, parentPath)
        }.flowOn(Dispatchers.Default)

    /** Songs whose `folderPath` equals exactly [parentPath] (no deeper). SQL-side filter + sort. */
    fun observeDirectSongsIn(parentPath: String): Flow<List<SongEntity>> = songDao.observeSongsInFolder(parentPath)

    /** All songs under [parentPath] including subfolders. Used for "Play Folder" at any level. */
    fun observeSongsRecursivelyIn(parentPath: String): Flow<List<SongEntity>> = songDao.observeSongsRecursivelyIn(parentPath.trimEnd('/'))

    fun observeAlbums(): Flow<List<AlbumSummary>> = songDao.observeAlbums()

    fun observeArtists(): Flow<List<ArtistSummary>> = songDao.observeArtists()

    fun observeSongsByArtist(artist: String): Flow<List<SongEntity>> = songDao.observeSongsByArtist(artist)

    fun observeSongsByAlbum(albumKey: String): Flow<List<SongEntity>> = songDao.observeSongsByAlbum(albumKey)

    private fun buildSubfolders(
        all: List<SongEntity>,
        parentPath: String,
    ): List<FolderSummary> {
        val normalizedParent = parentPath.trimEnd('/')
        val prefix = if (normalizedParent.isEmpty()) "" else "$normalizedParent/"

        // For each song under the parent, take the next path segment as the immediate subfolder name.
        val accumulator = mutableMapOf<String, Int>()
        for (song in all) {
            val path = song.folderPath
            if (normalizedParent.isNotEmpty() && !path.startsWith(prefix) && path != normalizedParent) continue
            if (normalizedParent.isEmpty() && path.isEmpty()) continue
            val rel = if (normalizedParent.isEmpty()) path else path.removePrefix(prefix)
            val nextSegment = rel.substringBefore('/')
            if (nextSegment.isEmpty()) continue
            accumulator.merge(nextSegment, 1, Int::plus)
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
                    } else {
                        add(MediaStore.Audio.Media.DATA)
                    }
                }.toTypedArray()

            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            val songs = mutableListOf<SongEntity>()

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
                val pathColumn =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                    } else {
                        cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    }

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val albumId = cursor.getLong(albumIdIndex).takeIf { it > 0L }
                    val rawTrack = cursor.getInt(trackIndex)
                    val relativeOrAbsolutePath = cursor.getString(pathColumn).orEmpty()
                    val folderInfo = deriveFolderInfo(relativeOrAbsolutePath)
                    val contentUri = ContentUris.withAppendedId(collection, id).toString()
                    val albumArtUri =
                        albumId?.let {
                            ContentUris.withAppendedId(
                                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                                it,
                            ).toString()
                        }

                    songs +=
                        SongEntity(
                            id = id,
                            contentUri = contentUri,
                            albumId = albumId,
                            title = cursor.getString(titleIndex).orEmpty().ifBlank { "Unknown title" },
                            artist = cursor.getString(artistIndex).orEmpty().ifBlank { "Unknown artist" },
                            album = cursor.getString(albumIndex).orEmpty().ifBlank { "Unknown album" },
                            durationMs = cursor.getLong(durationIndex),
                            trackNumber = rawTrack % 1000,
                            discNumber = rawTrack / 1000,
                            folderPath = folderInfo.first,
                            folderName = folderInfo.second,
                            albumArtUri = albumArtUri,
                            dateAddedSeconds = cursor.getLong(dateAddedIndex),
                            dateModifiedSeconds = cursor.getLong(dateModifiedIndex),
                        )
                }
            }

            if (songs.isEmpty()) {
                songDao.clearAll()
                return@withContext 0
            }

            // Chunk the upsert so a 50k-song library doesn't pin a single all-or-nothing transaction
            // (and a single huge SQLite statement) — the small per-chunk overhead is well worth it.
            songs.chunked(UPSERT_CHUNK_SIZE).forEach { chunk -> songDao.upsertAll(chunk) }
            pruneMissingSongs(currentSongIds = songs.map { it.id }.toSet())
            songs.size
        }

    private companion object {
        private const val UPSERT_CHUNK_SIZE = 1_000
    }

    private suspend fun pruneMissingSongs(currentSongIds: Set<Long>) {
        val missingIds = songDao.getAllSongIds().filterNot(currentSongIds::contains)
        if (missingIds.isEmpty()) return

        missingIds.chunked(500).forEach { chunk ->
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
