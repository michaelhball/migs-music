package com.migsmusic

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.migsmusic.playlistimport.SYNC_DIR_PATH
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * A sync that lands before the library exists must still be picked up: finishing a scan
 * has to import whatever is waiting in the sync inbox, with no broadcast and no visit to
 * the Playlists tab.
 */
@RunWith(AndroidJUnit4::class)
class InboxImportAfterScanTest {
    @get:Rule
    val permissionRule: GrantPermissionRule = mediaPermissionRule()

    private val app: MigsMusicApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MigsMusicApplication

    private val playlistName = "Migs-Inbox-${UUID.randomUUID().toString().take(6)}"
    private val inboxFile = File(SYNC_DIR_PATH, "$playlistName.m3u")

    @After
    fun cleanUp() {
        inboxFile.delete()
        runBlocking {
            app.appContainer.playlistRepository.observePlaylists().first()
                .filter { it.name == playlistName }
                .forEach { app.appContainer.playlistRepository.deletePlaylist(it.id) }
        }
    }

    @Test
    fun finishingAScanImportsWhatIsWaitingInTheInbox() {
        val song =
            runBlocking { app.appContainer.libraryRepository.observeAllSongs().first() }
                .firstOrNull { it.absolutePath.isNotEmpty() }
        assumeTrue("needs at least one indexed song with a path", song != null)

        File(SYNC_DIR_PATH).mkdirs()
        inboxFile.writeText("#EXTM3U\n${song!!.absolutePath}\n")

        runBlocking { app.appContainer.libraryRepository.scanDevice() }

        val deadline = System.currentTimeMillis() + 30_000
        var imported = runBlocking { findPlaylist() }
        while (imported == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(500)
            imported = runBlocking { findPlaylist() }
        }
        checkNotNull(imported) { "inbox file was not imported within 30s of the scan finishing" }

        val songs = runBlocking { app.appContainer.playlistRepository.observePlaylistSongs(imported.id).first() }
        assertEquals(listOf(song.id), songs.map { it.songId })
        assertFalse("imported .m3u should be consumed from the inbox", inboxFile.exists())
    }

    private suspend fun findPlaylist() = app.appContainer.playlistRepository.observePlaylists().first().firstOrNull { it.name == playlistName }
}
