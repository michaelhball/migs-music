package com.migsmusic.data.repository

import com.migsmusic.data.local.dao.PlaylistDao
import com.migsmusic.data.local.entity.PlaylistEntity
import com.migsmusic.data.local.entity.PlaylistSongEntity
import com.migsmusic.data.local.model.PlaylistSong
import com.migsmusic.data.local.model.PlaylistSummary
import kotlinx.coroutines.flow.Flow

class PlaylistRepository(
    private val playlistDao: PlaylistDao,
) {
    fun observePlaylists(): Flow<List<PlaylistSummary>> = playlistDao.observePlaylists()

    fun observePlaylistSongs(playlistId: Long): Flow<List<PlaylistSong>> = playlistDao.observePlaylistSongs(playlistId)

    suspend fun createPlaylist(name: String): Long {
        val now = System.currentTimeMillis()
        return playlistDao.insertPlaylist(
            PlaylistEntity(
                name = name.trim(),
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )
    }

    suspend fun renamePlaylist(
        playlistId: Long,
        name: String,
    ) {
        val playlist = playlistDao.getPlaylist(playlistId) ?: return
        playlistDao.updatePlaylist(
            playlist.copy(
                name = name.trim(),
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deletePlaylist(playlistId: Long) {
        playlistDao.deletePlaylist(playlistId)
    }

    suspend fun addSong(
        playlistId: Long,
        songId: Long,
    ) {
        val nextPosition = playlistDao.getLastSongPosition(playlistId) + 1
        playlistDao.insertPlaylistSong(
            PlaylistSongEntity(
                playlistId = playlistId,
                songId = songId,
                position = nextPosition,
                addedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun removeSong(playlistItemId: Long) {
        val item = playlistDao.getPlaylistSongEntity(playlistItemId) ?: return
        playlistDao.removePlaylistSong(playlistItemId)
        normalizePlaylistPositions(item.playlistId)
    }

    suspend fun moveSong(
        playlistId: Long,
        fromIndex: Int,
        toIndex: Int,
    ) {
        val items = playlistDao.getPlaylistSongEntities(playlistId)
        if (fromIndex !in items.indices) return
        val destination = toIndex.coerceIn(0, items.lastIndex)
        if (fromIndex == destination) return

        val moved = items[fromIndex]
        // Translate list indices to actual positions in case prior writes left them sparse.
        val fromPos = moved.position
        val toPos = items[destination].position
        playlistDao.moveSongPosition(
            playlistId = playlistId,
            playlistItemId = moved.playlistItemId,
            fromPos = fromPos,
            toPos = toPos,
        )
    }

    /**
     * Creates a fresh playlist with [name] and adds [songIds] in order. Used by the M3U
     * import flow. Returns the new playlist id.
     */
    suspend fun createPlaylistWithSongs(
        name: String,
        songIds: List<Long>,
    ): Long {
        val playlistId = createPlaylist(name)
        songIds.forEach { addSong(playlistId, it) }
        return playlistId
    }

    private suspend fun normalizePlaylistPositions(playlistId: Long) {
        val normalized =
            playlistDao.getPlaylistSongEntities(playlistId)
                .mapIndexed { index, item -> item.copy(position = index) }
        if (normalized.isNotEmpty()) {
            playlistDao.updatePlaylistSongs(normalized)
        }
    }
}
