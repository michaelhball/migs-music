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
    /** Reactive: emits whenever the heart state for [songId] changes. */
    @Query("SELECT EXISTS(SELECT 1 FROM loved_songs WHERE songId = :songId)")
    fun observeIsLoved(songId: Long): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(loved: LovedSongEntity)

    @Query("DELETE FROM loved_songs WHERE songId = :songId")
    suspend fun delete(songId: Long)

    /** All loved songs joined with their full SongEntity, most-recent first. */
    @Query(
        """
        SELECT s.* FROM loved_songs l
        INNER JOIN songs s ON s.id = l.songId
        ORDER BY l.addedAtSeconds DESC
        """,
    )
    fun observeAll(): Flow<List<SongEntity>>

    /** Just the count — used for the "Loves" virtual playlist's track-count badge. */
    @Query("SELECT COUNT(*) FROM loved_songs")
    fun observeCount(): Flow<Int>
}
