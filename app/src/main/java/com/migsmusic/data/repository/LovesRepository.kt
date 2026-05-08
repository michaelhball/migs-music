package com.migsmusic.data.repository

import com.migsmusic.data.local.dao.LovedSongDao
import com.migsmusic.data.local.dao.SongDao
import com.migsmusic.data.local.entity.LovedSongEntity
import com.migsmusic.data.local.entity.SongEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * "Loves" / hearted songs. Local-only — never written to or read from by the Mac
 * sync flow. Keyed on songs.absolutePath since v7 (was MediaStore _ID), so a heart
 * survives MediaScanner's tag-rescan _ID churn.
 *
 * Public API still operates in songId-land for compatibility with UI / playback,
 * which think in MediaStore `_ID`s for content-URI building. The repo translates
 * via [PlaylistDao.resolveAbsolutePaths]. CASCADE on absolutePath means deleting a
 * song from the library (file genuinely gone) auto-removes its heart.
 */
class LovesRepository(
    private val dao: LovedSongDao,
    private val songDao: SongDao,
) {
    fun observeIsLoved(songId: Long): Flow<Boolean> =
        flow {
            // Resolve the song's absolutePath once at subscription time. Songs only get a
            // new path when their file moves on disk, which can't happen while we hold a
            // single observation — so re-resolving on each tick would be wasted work.
            val path = songDao.resolveAbsolutePaths(listOf(songId)).firstOrNull()?.absolutePath
            if (path == null) emitAll(flowOf(false)) else emitAll(dao.observeIsLoved(path))
        }

    fun observeAll(): Flow<List<SongEntity>> = dao.observeAll()

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun love(songId: Long) {
        val path = songDao.resolveAbsolutePaths(listOf(songId)).firstOrNull()?.absolutePath ?: return
        dao.insert(
            LovedSongEntity(
                songAbsolutePath = path,
                addedAtSeconds = System.currentTimeMillis() / 1000,
            ),
        )
    }

    suspend fun unlove(songId: Long) {
        val path = songDao.resolveAbsolutePaths(listOf(songId)).firstOrNull()?.absolutePath ?: return
        dao.delete(path)
    }
}
