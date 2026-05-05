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
                // Capture the canonical "as added" order so the user can revert any manual
                // reordering later via "Restore import order".
                originalPosition = nextPosition,
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
     * import flow. Returns the new playlist id. Each song gets `originalPosition` set to
     * its index in [songIds], which is the M3U import order.
     */
    suspend fun createPlaylistWithSongs(
        name: String,
        songIds: List<Long>,
    ): Long {
        val playlistId = createPlaylist(name)
        songIds.forEach { addSong(playlistId, it) }
        return playlistId
    }

    /**
     * Resets the playlist's song order back to the order songs were imported / added in.
     * Use after the user has manually reordered and wants to revert. No-op for legacy rows
     * (pre-v3) without an `originalPosition` — though the migration backfilled those.
     */
    suspend fun restoreOriginalOrder(playlistId: Long) {
        playlistDao.restoreOriginalOrder(playlistId)
    }

    suspend fun hasOriginalOrder(playlistId: Long): Boolean = playlistDao.hasOriginalOrder(playlistId)

    private suspend fun normalizePlaylistPositions(playlistId: Long) {
        val normalized =
            playlistDao.getPlaylistSongEntities(playlistId)
                .mapIndexed { index, item -> item.copy(position = index) }
        if (normalized.isNotEmpty()) {
            playlistDao.updatePlaylistSongs(normalized)
        }
    }
}
