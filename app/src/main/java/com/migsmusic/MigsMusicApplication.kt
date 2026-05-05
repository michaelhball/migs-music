package com.migsmusic

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.migsmusic.data.LibrarySyncObserver
import com.migsmusic.data.local.AppDatabase
import com.migsmusic.data.repository.LibraryRepository
import com.migsmusic.data.repository.PlaybackSessionRepository
import com.migsmusic.data.repository.PlaylistRepository
import com.migsmusic.playback.PlaybackManager

class MigsMusicApplication : Application(), ImageLoaderFactory {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        val database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "migs-music.db"
        )
            .fallbackToDestructiveMigration(dropAllTables = false)
            // WAL lets scanDevice writes overlap with UI reads (e.g. observeAllSongs Flow
            // firing while ContentObserver-driven upserts run). Default TRUNCATE journal
            // serializes them, which is visible as scroll hitches during heavy MTP transfers.
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

        val libraryRepository = LibraryRepository(
            context = applicationContext,
            songDao = database.songDao(),
        )
        val playlistRepository = PlaylistRepository(
            playlistDao = database.playlistDao(),
        )
        val playbackSessionRepository = PlaybackSessionRepository(
            playbackSnapshotDao = database.playbackSnapshotDao(),
        )

        val preferences = AppPreferences(applicationContext)

        appContainer = AppContainer(
            libraryRepository = libraryRepository,
            playlistRepository = playlistRepository,
            playbackManager = PlaybackManager(
                context = applicationContext,
                libraryRepository = libraryRepository,
                sessionRepository = playbackSessionRepository,
                preferences = preferences,
            ),
            preferences = preferences,
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
    }

    /**
     * Detects whether the process is hosting androidx instrumentation tests. The androidx.test
     * runtime is only on the classpath when the test APK is loaded; in production builds the
     * lookup throws ClassNotFoundException. Cheap, no setup ceremony, no build-config plumbing.
     */
    private fun isInstrumentationRunning(): Boolean = try {
        Class.forName("androidx.test.platform.app.InstrumentationRegistry")
        true
    } catch (e: ClassNotFoundException) {
        false
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
)
