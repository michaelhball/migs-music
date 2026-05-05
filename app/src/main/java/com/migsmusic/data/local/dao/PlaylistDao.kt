package com.migsmusic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.migsmusic.data.local.entity.PlaylistEntity
import com.migsmusic.data.local.entity.PlaylistSongEntity
import com.migsmusic.data.local.model.PlaylistSong
import com.migsmusic.data.local.model.PlaylistSummary
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query(
        """
        SELECT playlists.id AS id, playlists.name AS name, COUNT(playlist_songs.playlistItemId) AS songCount
        FROM playlists
        LEFT JOIN playlist_songs ON playlists.id = playlist_songs.playlistId
        GROUP BY playlists.id, playlists.name
        ORDER BY LOWER(playlists.name)
        """
    )
    fun observePlaylists(): Flow<List<PlaylistSummary>>

    @Query(
        """
        SELECT
            playlist_songs.playlistItemId AS playlistItemId,
            playlist_songs.playlistId AS playlistId,
            playlist_songs.songId AS songId,
            songs.title AS title,
            songs.artist AS artist,
            songs.album AS album,
            songs.durationMs AS durationMs,
            playlist_songs.position AS position,
            songs.albumArtUri AS albumArtUri
        FROM playlist_songs
        INNER JOIN songs ON songs.id = playlist_songs.songId
        WHERE playlist_songs.playlistId = :playlistId
        ORDER BY playlist_songs.position
        """
    )
    fun observePlaylistSongs(playlistId: Long): Flow<List<PlaylistSong>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getPlaylist(playlistId: Long): PlaylistEntity?

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun getLastSongPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSong(playlistSong: PlaylistSongEntity): Long

    @Query("DELETE FROM playlist_songs WHERE playlistItemId = :playlistItemId")
    suspend fun removePlaylistSong(playlistItemId: Long)

    @Query("SELECT * FROM playlist_songs WHERE playlistItemId = :playlistItemId LIMIT 1")
    suspend fun getPlaylistSongEntity(playlistItemId: Long): PlaylistSongEntity?

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clearPlaylistSongs(playlistId: Long)

    @Update
    suspend fun updatePlaylistSongs(items: List<PlaylistSongEntity>)

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position")
    suspend fun getPlaylistSongEntities(playlistId: Long): List<PlaylistSongEntity>

    /** Shift every row in `[minPos, maxPos]` down by 1 (for "move down" reorders). */
    @Query(
        """
        UPDATE playlist_songs SET position = position - 1
        WHERE playlistId = :playlistId AND position BETWEEN :minPos AND :maxPos
        """
    )
    suspend fun shiftPositionsDown(playlistId: Long, minPos: Int, maxPos: Int)

    /** Shift every row in `[minPos, maxPos]` up by 1 (for "move up" reorders). */
    @Query(
        """
        UPDATE playlist_songs SET position = position + 1
        WHERE playlistId = :playlistId AND position BETWEEN :minPos AND :maxPos
        """
    )
    suspend fun shiftPositionsUp(playlistId: Long, minPos: Int, maxPos: Int)

    @Query("UPDATE playlist_songs SET position = :position WHERE playlistItemId = :playlistItemId")
    suspend fun setPlaylistSongPosition(playlistItemId: Long, position: Int)

    /**
     * Atomic single-row reorder: shifts the rows in the affected range and re-positions the
     * moved row. Three SQL statements instead of `O(N)` row updates.
     */
    @Transaction
    suspend fun moveSongPosition(
        playlistId: Long,
        playlistItemId: Long,
        fromPos: Int,
        toPos: Int,
    ) {
        if (fromPos == toPos) return
        if (fromPos < toPos) {
            shiftPositionsDown(playlistId, fromPos + 1, toPos)
        } else {
            shiftPositionsUp(playlistId, toPos, fromPos - 1)
        }
        setPlaylistSongPosition(playlistItemId, toPos)
    }

    @Transaction
    suspend fun replacePlaylistOrder(playlistId: Long, items: List<PlaylistSongEntity>) {
        clearPlaylistSongs(playlistId)
        items.forEach { insertPlaylistSong(it.copy(playlistItemId = 0, playlistId = playlistId)) }
    }
}
