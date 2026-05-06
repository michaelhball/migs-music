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

    /**
     * Drops the persisted snapshot. Used when a library scan remaps song IDs (MediaStore
     * reassignment) and any of the snapshot's referenced ids would now be stale — easier to
     * lose the saved position than to parse and re-serialize the comma-separated history /
     * upcoming / later / remaining lists. The active in-memory queue is unaffected; only
     * the persisted version is cleared.
     */
    @Query("DELETE FROM playback_snapshot")
    suspend fun clearSnapshot()
}
