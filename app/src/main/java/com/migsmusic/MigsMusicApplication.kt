package com.migsmusic

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.migsmusic.data.LibrarySyncObserver
import com.migsmusic.data.OrphanAudioTracker
import com.migsmusic.data.local.AppDatabase
import com.migsmusic.data.local.MIGRATION_2_3
import com.migsmusic.data.local.MIGRATION_3_4
import com.migsmusic.data.local.MIGRATION_4_5
import com.migsmusic.data.local.MIGRATION_5_6
import com.migsmusic.data.local.MIGRATION_6_7
import com.migsmusic.data.repository.LibraryRepository
import com.migsmusic.data.repository.LovesRepository
import com.migsmusic.data.repository.PlaybackSessionRepository
import com.migsmusic.data.repository.PlaylistRepository
import com.migsmusic.playback.PlaybackManager
import com.migsmusic.playlistimport.AutoImportService

class MigsMusicApplication : Application(), ImageLoaderFactory {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        val database =
            Room.databaseBuilder(
                applicationContext,
                AppDatabase::class.java,
                "migs-music.db",
            )
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .fallbackToDestructiveMigration(dropAllTables = false)
                // WAL lets scanDevice writes overlap with UI reads (e.g. observeAllSongs Flow
                // firing while ContentObserver-driven upserts run). Default TRUNCATE journal
                // serializes them, which is visible as scroll hitches during heavy MTP transfers.
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()

        val libraryRepository =
            LibraryRepository(
                context = applicationContext,
                songDao = database.songDao(),
            )
        val playlistRepository =
            PlaylistRepository(
                playlistDao = database.playlistDao(),
                songDao = database.songDao(),
            )
        val playbackSessionRepository =
            PlaybackSessionRepository(
                playbackSnapshotDao = database.playbackSnapshotDao(),
            )
        val lovesRepository =
            LovesRepository(
                dao = database.lovedSongDao(),
                songDao = database.songDao(),
            )

        val preferences = AppPreferences(applicationContext)
        val orphanAudioTracker = OrphanAudioTracker(applicationContext)

        val playbackManager =
            PlaybackManager(
                context = applicationContext,
                libraryRepository = libraryRepository,
                sessionRepository = playbackSessionRepository,
                preferences = preferences,
            )

        val autoImportService =
            AutoImportService(
                context = applicationContext,
                playlistRepository = playlistRepository,
                libraryRepository = libraryRepository,
                playbackController = playbackManager,
                orphanAudioTracker = orphanAudioTracker,
            )

        appContainer =
            AppContainer(
                libraryRepository = libraryRepository,
                playlistRepository = playlistRepository,
                playbackManager = playbackManager,
                preferences = preferences,
                autoImportService = autoImportService,
                orphanAudioTracker = orphanAudioTracker,
                lovesRepository = lovesRepository,
            )

        // Watch MediaStore for new audio files; auto-rescan when changes settle so the user
        // never has to tap a button after dropping music onto the phone.
        // Disabled under instrumentation: a stray MediaStore notification mid-test (caused by
        // any other audio-touching app on the device) kicks off a multi-second rescan that
        // re-emits 1845 songs through every observer and breaks `waitUntil` timing. Tests
        // explicitly call `libraryRepository.scanDevice()` when they need fresh data.
        if (!isInstrumentationRunning()) {
            LibrarySyncObserver(applicationContext, libraryRepository).start()
        }

        // Force-stop / debug reinstall doesn't checkpoint WAL on this device, so writes that
        // landed only in WAL get lost. Checkpoint when the app moves to the background so the
        // main DB file always has the latest committed state. Cheap enough to run on every
        // background transition.
        registerActivityLifecycleCallbacks(WalCheckpointer(database))
    }

    /**
     * Album art is small, immutable per song, and reused across many list rows. Default Coil
     * sizing (~25% RAM, ~2% disk) is fine for one-off images but underuses disk for our case.
     * Bumping disk to 64MB lets the entire library's album art live in cache after one pass;
     * `respectCacheHeaders = false` because content URIs don't carry HTTP headers.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_art"))
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
}

data class AppContainer(
    val libraryRepository: LibraryRepository,
    val playlistRepository: PlaylistRepository,
    val playbackManager: PlaybackManager,
    val preferences: AppPreferences,
    val autoImportService: AutoImportService,
    val orphanAudioTracker: OrphanAudioTracker,
    val lovesRepository: LovesRepository,
)

/**
 * Counts visible activities; when the count drops to zero (app going to background) the
 * Room WAL is checkpointed via `PRAGMA wal_checkpoint(TRUNCATE)`. Without this, writes
 * that have committed to WAL but not yet been merged into the main DB file can be lost
 * when the OS aggressively kills the process — observed in practice during debug
 * `adb install -r` cycles where playlist rows persisted but `playlist_songs` rows did not.
 */
private class WalCheckpointer(
    private val database: AppDatabase,
) : Application.ActivityLifecycleCallbacks {
    private var startedActivities = 0

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities--
        if (startedActivities <= 0) {
            startedActivities = 0
            runCatching {
                database.openHelper.writableDatabase
                    .query("PRAGMA wal_checkpoint(TRUNCATE)")
                    .use { it.moveToFirst() }
            }.onFailure { Log.w("WalCheckpointer", "checkpoint failed", it) }
        }
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
