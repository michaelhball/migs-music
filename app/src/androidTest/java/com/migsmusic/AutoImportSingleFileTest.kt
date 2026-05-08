package com.migsmusic

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.migsmusic.data.OrphanAudioTracker
import com.migsmusic.data.local.AppDatabase
import com.migsmusic.data.local.entity.SongEntity
import com.migsmusic.data.repository.LibraryRepository
import com.migsmusic.data.repository.PlaylistRepository
import com.migsmusic.playback.PlaybackController
import com.migsmusic.playlistimport.AutoImportService
import com.migsmusic.playlistimport.DiscoveredM3u
import com.migsmusic.playlistimport.M3uMatcherIndex
import com.migsmusic.playlistimport.SingleFileOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Tests [AutoImportService.autoImportSingleFile] directly. Uses an in-memory Room DB and
 * real on-disk temp files (in the test app's cache dir), bypassing SAF — the matcher and
 * upsert logic don't care about the URI shape, just the bytes returned via either the
 * absolutePath fast path or contentResolver.openInputStream fallback.
 *
 * Source-delete is best-effort in production (SAF fail = log + return Imported anyway), so
 * we don't try to assert deletion here — the playlist write is what matters for sync
 * correctness, and the file lifecycle is covered by the live device smoke tests.
 */
@RunWith(AndroidJUnit4::class)
class AutoImportSingleFileTest {
    private lateinit var db: AppDatabase
    private lateinit var service: AutoImportService
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var orphanAudioTracker: OrphanAudioTracker
    private lateinit var tempDir: File
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        playlistRepository = PlaylistRepository(db.playlistDao(), db.songDao())
        libraryRepository =
            LibraryRepository(
                context = context,
                songDao = db.songDao(),
            )
        // Stub PlaybackController — these tests don't exercise the orphan-current-song path,
        // and a real PlaybackManager would spin up coroutines that outlive the test and try
        // to query the closed Room DB.
        val fakeController =
            object : PlaybackController {
                override val currentSongId: StateFlow<Long?> = MutableStateFlow(null)

                override fun stopAndClearQueue() = error("not expected to fire in these tests")
            }
        // Reset the tracker's prefs so tests start with a known empty state.
        context.getSharedPreferences("migs-music-orphan-audio", Context.MODE_PRIVATE)
            .edit().clear().commit()
        orphanAudioTracker = OrphanAudioTracker(context)
        service =
            AutoImportService(
                context = context,
                playlistRepository = playlistRepository,
                libraryRepository = libraryRepository,
                playbackController = fakeController,
                orphanAudioTracker = orphanAudioTracker,
            )

        tempDir = File(context.cacheDir, "auto-import-test-${UUID.randomUUID()}")
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun importedWritesSyncedPlaylistAndReportsImported() =
        runBlocking {
            val library =
                listOf(
                    fakeSong(1L, "Hey Jude", "The Beatles"),
                    fakeSong(2L, "Yesterday", "The Beatles"),
                )
            db.songDao().upsertAll(library)

            val m3u =
                """
                #EXTM3U
                #EXTINF:431,The Beatles - Hey Jude
                /storage/whatever/heyjude.mp3
                #EXTINF:125,The Beatles - Yesterday
                /storage/whatever/yesterday.mp3
                """.trimIndent()
            val file = writeM3u("Beatles.m3u", m3u)

            val outcome =
                service.autoImportSingleFile(file, M3uMatcherIndex(library))

            assertEquals(SingleFileOutcome.Imported, outcome)
            val syncedPlaylists = playlistRepository.getSyncedPlaylists()
            assertEquals(1, syncedPlaylists.size)
            assertEquals("Beatles", syncedPlaylists.first().name)
            val songIds = playlistRepository.getPlaylistSongIds(syncedPlaylists.first().id)
            assertEquals(setOf(1L, 2L), songIds)
        }

    @Test
    fun noMatchesLeavesNoPlaylistAndReportsNoMatches() =
        runBlocking {
            val library = listOf(fakeSong(1L, "Hey Jude", "The Beatles"))
            db.songDao().upsertAll(library)

            // M3U references a song the library doesn't have.
            val m3u =
                """
                #EXTM3U
                #EXTINF:200,Some Other Artist - Some Other Song
                /storage/whatever/other.mp3
                """.trimIndent()
            val file = writeM3u("Mismatch.m3u", m3u)

            val outcome =
                service.autoImportSingleFile(file, M3uMatcherIndex(library))

            assertEquals(SingleFileOutcome.NoMatches, outcome)
            assertTrue(playlistRepository.getSyncedPlaylists().isEmpty())
        }

    @Test
    fun emptyContentReportsFailed() =
        runBlocking {
            val file = writeM3u("Empty.m3u", "")

            val outcome =
                service.autoImportSingleFile(file, M3uMatcherIndex(emptyList()))

            assertTrue("expected Failed but was $outcome", outcome is SingleFileOutcome.Failed)
            assertTrue(playlistRepository.getSyncedPlaylists().isEmpty())
        }

    @Test
    fun missingFileReportsFailed() =
        runBlocking {
            val nonexistent =
                DiscoveredM3u(
                    uri = Uri.parse("file:///does/not/exist.m3u"),
                    displayName = "Missing.m3u",
                    absolutePath = "/does/not/exist.m3u",
                )

            val outcome =
                service.autoImportSingleFile(nonexistent, M3uMatcherIndex(emptyList()))

            assertTrue("expected Failed but was $outcome", outcome is SingleFileOutcome.Failed)
        }

    @Test
    fun importedReplacesExistingSyncedPlaylistOfSameName() =
        runBlocking {
            val library =
                listOf(
                    fakeSong(1L, "Hey Jude", "The Beatles"),
                    fakeSong(2L, "Yesterday", "The Beatles"),
                )
            db.songDao().upsertAll(library)

            // First import: just Hey Jude.
            val first =
                writeM3u(
                    "Beatles.m3u",
                    """
                    #EXTM3U
                    #EXTINF:431,The Beatles - Hey Jude
                    /a.mp3
                    """.trimIndent(),
                )
            assertEquals(
                SingleFileOutcome.Imported,
                service.autoImportSingleFile(first, M3uMatcherIndex(library)),
            )

            // Second import (same name): both songs. Should REPLACE the contents, not append.
            val second =
                writeM3u(
                    "Beatles.m3u",
                    """
                    #EXTM3U
                    #EXTINF:431,The Beatles - Hey Jude
                    /a.mp3
                    #EXTINF:125,The Beatles - Yesterday
                    /b.mp3
                    """.trimIndent(),
                )
            assertEquals(
                SingleFileOutcome.Imported,
                service.autoImportSingleFile(second, M3uMatcherIndex(library)),
            )

            val synced = playlistRepository.getSyncedPlaylists()
            assertEquals(1, synced.size)
            val songIds = playlistRepository.observePlaylistSongs(synced.first().id).first().map { it.songId }
            assertEquals(listOf(1L, 2L), songIds)
        }

    @Test
    fun importedDoesNotTouchManualPlaylistWithSameName() =
        runBlocking {
            val library =
                listOf(
                    fakeSong(1L, "Hey Jude", "The Beatles"),
                    fakeSong(2L, "Yesterday", "The Beatles"),
                )
            db.songDao().upsertAll(library)

            // User-created manual playlist with name "Beatles" containing only Yesterday.
            val manualId = playlistRepository.createPlaylist("Beatles")
            playlistRepository.addSong(manualId, 2L)

            // Sync arrives with a Beatles.m3u containing Hey Jude — should land in a SEPARATE
            // synced playlist row, manual one untouched.
            val m3u =
                writeM3u(
                    "Beatles.m3u",
                    """
                    #EXTM3U
                    #EXTINF:431,The Beatles - Hey Jude
                    /a.mp3
                    """.trimIndent(),
                )
            assertEquals(
                SingleFileOutcome.Imported,
                service.autoImportSingleFile(m3u, M3uMatcherIndex(library)),
            )

            val manualSongs = playlistRepository.observePlaylistSongs(manualId).first().map { it.songId }
            assertEquals(listOf(2L), manualSongs)

            val synced = playlistRepository.getSyncedPlaylists()
            assertEquals(1, synced.size)
            assertFalse(synced.first().id == manualId)
            val syncedSongs = playlistRepository.observePlaylistSongs(synced.first().id).first().map { it.songId }
            assertEquals(listOf(1L), syncedSongs)
        }

    @Test
    fun perSongRemovalCapturesOrphanUriAndDropsRow() =
        runBlocking {
            // Three songs in the library, all wired into a single synced playlist.
            val library =
                listOf(
                    fakeSong(1L, "Hey Jude", "The Beatles"),
                    fakeSong(2L, "Yesterday", "The Beatles"),
                    fakeSong(3L, "Let It Be", "The Beatles"),
                )
            db.songDao().upsertAll(library)
            val first =
                writeM3u(
                    "Beatles.m3u",
                    """
                    #EXTM3U
                    #EXTINF:431,The Beatles - Hey Jude
                    /a.mp3
                    #EXTINF:125,The Beatles - Yesterday
                    /b.mp3
                    #EXTINF:243,The Beatles - Let It Be
                    /c.mp3
                    """.trimIndent(),
                )
            assertEquals(
                SingleFileOutcome.Imported,
                service.autoImportSingleFile(first, M3uMatcherIndex(library), deleteOrphans = true),
            )
            assertEquals(0, orphanAudioTracker.count.value)

            // Second sync: same playlist, but Yesterday is gone. Library still has all three
            // songs (MediaStore hasn't changed yet — file's still on disk). With
            // deleteOrphans=true we expect Yesterday's row to be dropped AND its content URI
            // captured for later cleanup-via-Settings.
            val second =
                writeM3u(
                    "Beatles.m3u",
                    """
                    #EXTM3U
                    #EXTINF:431,The Beatles - Hey Jude
                    /a.mp3
                    #EXTINF:243,The Beatles - Let It Be
                    /c.mp3
                    """.trimIndent(),
                )
            assertEquals(
                SingleFileOutcome.Imported,
                service.autoImportSingleFile(second, M3uMatcherIndex(library), deleteOrphans = true),
            )

            // Yesterday's row should be gone, others intact.
            assertEquals(setOf(1L, 3L), db.songDao().getAllSongIds().toSet())
            // Tracker should now hold Yesterday's content URI.
            assertEquals(1, orphanAudioTracker.count.value)
            assertEquals(
                "content://media/external/audio/media/2",
                orphanAudioTracker.all().single().toString(),
            )
            // Playlist should also reflect the new contents.
            val synced = playlistRepository.getSyncedPlaylists().single()
            val ids = playlistRepository.observePlaylistSongs(synced.id).first().map { it.songId }
            assertEquals(listOf(1L, 3L), ids)
        }

    @Test
    fun perSongRemovalSkipsTrackerWhenDeleteOrphansFalse() =
        runBlocking {
            // Same setup as the orphan test but with deleteOrphans=false. Song should still
            // come out of the playlist on re-import, but its row should remain in `songs`
            // and the orphan tracker should stay empty — that's what the Mac toggle controls.
            val library =
                listOf(
                    fakeSong(1L, "Hey Jude", "The Beatles"),
                    fakeSong(2L, "Yesterday", "The Beatles"),
                )
            db.songDao().upsertAll(library)
            assertEquals(
                SingleFileOutcome.Imported,
                service.autoImportSingleFile(
                    writeM3u(
                        "Beatles.m3u",
                        """
                        #EXTM3U
                        #EXTINF:431,The Beatles - Hey Jude
                        /a.mp3
                        #EXTINF:125,The Beatles - Yesterday
                        /b.mp3
                        """.trimIndent(),
                    ),
                    M3uMatcherIndex(library),
                    deleteOrphans = false,
                ),
            )

            assertEquals(
                SingleFileOutcome.Imported,
                service.autoImportSingleFile(
                    writeM3u(
                        "Beatles.m3u",
                        """
                        #EXTM3U
                        #EXTINF:431,The Beatles - Hey Jude
                        /a.mp3
                        """.trimIndent(),
                    ),
                    M3uMatcherIndex(library),
                    deleteOrphans = false,
                ),
            )

            // Both song rows still present.
            assertEquals(setOf(1L, 2L), db.songDao().getAllSongIds().toSet())
            // Tracker untouched.
            assertEquals(0, orphanAudioTracker.count.value)
        }

    private fun writeM3u(
        displayName: String,
        content: String,
    ): DiscoveredM3u {
        val file = File(tempDir, displayName)
        file.writeText(content)
        return DiscoveredM3u(
            // Uri value isn't read on the absolutePath fast path. SAF delete will fail
            // silently and return false; that's logged but doesn't change the outcome.
            uri = Uri.fromFile(file),
            displayName = displayName,
            absolutePath = file.absolutePath,
        )
    }

    private fun fakeSong(
        id: Long,
        title: String,
        artist: String,
    ) = SongEntity(
        id = id,
        contentUri = "content://media/external/audio/media/$id",
        albumId = null,
        title = title,
        artist = artist,
        album = "Album $id",
        durationMs = 180_000L,
        trackNumber = 1,
        discNumber = 1,
        folderPath = "Music",
        folderName = "Music",
        albumArtUri = null,
        dateAddedSeconds = 0L,
        dateModifiedSeconds = 0L,
        absolutePath = "/storage/emulated/0/Music/$title.mp3",
    )
}
