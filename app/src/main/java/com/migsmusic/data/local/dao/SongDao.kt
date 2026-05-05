package com.migsmusic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
        """
    )
    fun observeFolders(): Flow<List<FolderSummary>>

    @Query(
        """
        SELECT * FROM songs
        WHERE folderPath = :folderPath
        ORDER BY discNumber, trackNumber, LOWER(title)
        """
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
        """
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
        """
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
        """
    )
    fun observeArtists(): Flow<List<ArtistSummary>>

    @Query(
        """
        SELECT * FROM songs
        WHERE artist = :artist
        ORDER BY LOWER(album), discNumber, trackNumber, LOWER(title)
        """
    )
    fun observeSongsByArtist(artist: String): Flow<List<SongEntity>>

    @Query(
        """
        SELECT * FROM songs
        WHERE album || '|||' || artist = :albumKey
        ORDER BY discNumber, trackNumber, LOWER(title)
        """
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
        """
    )
    fun searchSongs(query: String): Flow<List<SongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(songs: List<SongEntity>)

    @Query("DELETE FROM songs")
    suspend fun clearAll()

    @Query("SELECT id FROM songs")
    suspend fun getAllSongIds(): List<Long>

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
