package com.migsmusic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.migsmusic.data.local.entity.LovedSongEntity
import com.migsmusic.data.local.entity.SongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LovedSongDao {
    /**
     * Reactive: emits whenever the heart state for the song at [songAbsolutePath] changes.
     * Callers in songId-land resolve via [PlaylistDao.resolveAbsolutePaths] first; the
     * Repository wrapper does the resolution so consumers don't see absolutePaths at all.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM loved_songs WHERE songAbsolutePath = :songAbsolutePath)")
    fun observeIsLoved(songAbsolutePath: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(loved: LovedSongEntity)

    @Query("DELETE FROM loved_songs WHERE songAbsolutePath = :songAbsolutePath")
    suspend fun delete(songAbsolutePath: String)

    /** All loved songs joined with their full SongEntity, most-recent first. */
    @Query(
        """
        SELECT s.* FROM loved_songs l
        INNER JOIN songs s ON s.absolutePath = l.songAbsolutePath
        ORDER BY l.addedAtSeconds DESC
        """,
    )
    fun observeAll(): Flow<List<SongEntity>>

    /** Just the count — used for the "Loves" virtual playlist's track-count badge. */
    @Query("SELECT COUNT(*) FROM loved_songs")
    fun observeCount(): Flow<Int>
}
