package com.migsmusic.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.migsmusic.data.local.dao.PlaybackSnapshotDao
import com.migsmusic.data.local.dao.PlaylistDao
import com.migsmusic.data.local.dao.SongDao
import com.migsmusic.data.local.entity.PlaybackSnapshotEntity
import com.migsmusic.data.local.entity.PlaylistEntity
import com.migsmusic.data.local.entity.PlaylistSongEntity
import com.migsmusic.data.local.entity.SongEntity

@Database(
    entities = [
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        PlaybackSnapshotEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playbackSnapshotDao(): PlaybackSnapshotDao
}
