package com.migsmusic.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.migsmusic.data.local.entity.SongEntity
import com.migsmusic.data.local.model.AlbumSummary
import com.migsmusic.data.local.model.ArtistSummary
import com.migsmusic.data.local.model.FolderSummary
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY LOWER(title), LOWER(artist)")
    fun observeAllSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs ORDER BY LOWER(title), LOWER(artist)")
    suspend fun getAllSongs(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getSongsByIds(ids: List<Long>): List<SongEntity>

    @Query(
        """
        SELECT folderPath AS path, folderName AS name, COUNT(*) AS songCount
        FROM songs
        WHERE folderPath != ''
        GROUP BY folderPath, folderName
        ORDER BY LOWER(folderPath)
        """,
    )
    fun observeFolders(): Flow<List<FolderSummary>>

    /**
     * Aggregate (folderPath, songCount) rows for songs at-or-below [parentPath]. Pass an
     * empty string for the root. Caller (LibraryRepository.observeSubfolders) extracts the
     * "next path segment" client-side and merges counts per segment.
     *
     * Why we don't extract the segment in SQL: SQLite has no string-split, only INSTR + SUBSTR
     * which would require a recursive CTE or conditional path arithmetic for arbitrary depth.
     * The Kotlin pass over the resulting distinct-folderPath rows (typically <100 rows even at
     * 10k songs) is trivial compared to the prior approach of materializing every SongEntity
     * row through a Flow on every emission.
     */
    @Query(
        """
        SELECT folderPath AS path, '' AS name, COUNT(*) AS songCount
        FROM songs
        WHERE folderPath != ''
          AND (:parentPath = '' OR folderPath = :parentPath OR folderPath LIKE :parentPath || '/%')
        GROUP BY folderPath
        """,
    )
    fun observeFolderCountsUnder(parentPath: String): Flow<List<FolderSummary>>

    @Query(
        """
        SELECT * FROM songs
        WHERE folderPath = :folderPath
        ORDER BY discNumber, trackNumber, LOWER(title)
        """,
    )
    fun observeSongsInFolder(folderPath: String): Flow<List<SongEntity>>

    /**
     * Songs whose folderPath is exactly [folderPath] OR is a descendant. When [folderPath] is
     * empty, returns every song. Used by Folders view's recursive Play / Shuffle Folder.
     */
    @Query(
        """
        SELECT * FROM songs
        WHERE :folderPath = ''
           OR folderPath = :folderPath
           OR folderPath LIKE :folderPath || '/%'
        ORDER BY LOWER(folderPath), discNumber, trackNumber, LOWER(title)
        """,
    )
    fun observeSongsRecursivelyIn(folderPath: String): Flow<List<SongEntity>>

    /**
     * One row per (album, artist) tuple. Picks an arbitrary non-null albumArtUri across the
     * group via MAX(), which ignores NULLs in SQLite — gives stable cover art per album.
     */
    @Query(
        """
        SELECT
            album || '|||' || artist AS `key`,
            album AS title,
            artist AS artist,
            COUNT(*) AS songCount,
            MAX(albumArtUri) AS albumArtUri
        FROM songs
        GROUP BY album, artist
        ORDER BY LOWER(album), LOWER(artist)
        """,
    )
    fun observeAlbums(): Flow<List<AlbumSummary>>

    @Query(
        """
        SELECT
            artist AS name,
            COUNT(*) AS songCount,
            COUNT(DISTINCT album) AS albumCount
        FROM songs
        GROUP BY artist
        ORDER BY LOWER(artist)
        """,
    )
    fun observeArtists(): Flow<List<ArtistSummary>>

    @Query(
        """
        SELECT * FROM songs
        WHERE artist = :artist
        ORDER BY LOWER(album), discNumber, trackNumber, LOWER(title)
        """,
    )
    fun observeSongsByArtist(artist: String): Flow<List<SongEntity>>

    @Query(
        """
        SELECT * FROM songs
        WHERE album || '|||' || artist = :albumKey
        ORDER BY discNumber, trackNumber, LOWER(title)
        """,
    )
    fun observeSongsByAlbum(albumKey: String): Flow<List<SongEntity>>

    @Query(
        """
        SELECT * FROM songs
        WHERE title LIKE '%' || :query || '%'
           OR artist LIKE '%' || :query || '%'
           OR album LIKE '%' || :query || '%'
           OR folderName LIKE '%' || :query || '%'
        ORDER BY LOWER(title), LOWER(artist)
        """,
    )
    fun searchSongs(query: String): Flow<List<SongEntity>>

    // @Upsert (INSERT-or-UPDATE) instead of @Insert(REPLACE) (DELETE-then-INSERT).
    // Originally adopted because REPLACE triggered CASCADE on `playlist_songs.songId`,
    // wiping playlists on every scan. Post-v7 the cross-ref FKs target `absolutePath`
    // (stable across MediaStore _ID churn), so REPLACE wouldn't be destructive anymore —
    // but Upsert is still semantically correct (we're updating metadata for an existing
    // file, not replacing it) and avoids unnecessary writes.
    @Upsert
    suspend fun upsertAll(songs: List<SongEntity>)

    /**
     * Chunked upsert wrapped in a single Room transaction. The library scan splits the full
     * MediaStore song list into chunks (memory pressure on large libraries), but without a
     * surrounding transaction each chunk's upsert was its own implicit transaction → one
     * fsync per chunk → ~5-10× more disk traffic on a 5k-song scan than necessary. Wrap the
     * loop so we get one commit at the end.
     */
    @Transaction
    suspend fun upsertAllChunked(chunks: List<List<SongEntity>>) {
        for (chunk in chunks) upsertAll(chunk)
    }

    @Query("DELETE FROM songs")
    suspend fun clearAll()

    @Query("SELECT id FROM songs")
    suspend fun getAllSongIds(): List<Long>

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /**
     * (id, absolutePath) for every song. Pre-v7 this powered the playlist-songId remap
     * logic; post-v7 the cross-ref FKs target absolutePath directly so remap is moot.
     * Kept for callers that want a fast diff of "what's already in the library by path".
     */
    @Query("SELECT id, absolutePath FROM songs WHERE absolutePath != ''")
    suspend fun getIdAndPaths(): List<SongIdPath>

    /**
     * Resolve a list of [songIds] (MediaStore `_ID` values) to their current absolute
     * paths. Used by repos translating songId-land callers (UI/playback) into the v7
     * absolutePath cross-ref scheme. Order of the result is unspecified — callers map
     * by id.
     */
    @Query("SELECT id AS songId, absolutePath FROM songs WHERE id IN (:songIds)")
    suspend fun resolveAbsolutePaths(songIds: List<Long>): List<SongIdAndPath>
}

/** Projection for [SongDao.resolveAbsolutePaths]. */
data class SongIdAndPath(val songId: Long, val absolutePath: String)

/** Projection for [SongDao.getIdAndPaths]. */
data class SongIdPath(
    val id: Long,
    val absolutePath: String,
)
