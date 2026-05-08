package com.migsmusic.data.repository

import com.migsmusic.data.local.dao.LovedSongDao
import com.migsmusic.data.local.entity.LovedSongEntity
import com.migsmusic.data.local.entity.SongEntity
import kotlinx.coroutines.flow.Flow

/**
 * "Loves" / hearted songs. Local-only — never written to or read from by the Mac
 * sync flow. The user's hearts persist across every sync, every per-playlist
 * replace, every orphan-cleanup. The CASCADE FK to songs means deleting a song
 * from the library auto-removes its heart row, so we never end up with hearts
 * pointing at songs that no longer exist.
 */
class LovesRepository(
    private val dao: LovedSongDao,
) {
    fun observeIsLoved(songId: Long): Flow<Boolean> = dao.observeIsLoved(songId)

    fun observeAll(): Flow<List<SongEntity>> = dao.observeAll()

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun love(songId: Long) {
        dao.insert(
            LovedSongEntity(
                songId = songId,
                addedAtSeconds = System.currentTimeMillis() / 1000,
            ),
        )
    }

    suspend fun unlove(songId: Long) {
        dao.delete(songId)
    }
}
