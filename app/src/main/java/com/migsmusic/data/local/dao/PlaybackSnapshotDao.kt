package com.migsmusic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.migsmusic.data.local.entity.PlaybackSnapshotEntity

@Dao
interface PlaybackSnapshotDao {
    @Query("SELECT * FROM playback_snapshot WHERE snapshotId = 0")
    suspend fun getSnapshot(): PlaybackSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshot(snapshot: PlaybackSnapshotEntity)
}
