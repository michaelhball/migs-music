package com.migsmusic

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.common.util.concurrent.ListenableFuture
import com.migsmusic.playback.MediaPlaybackService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.TimeUnit

/**
 * Drives the Android Auto surface the way Auto itself does: a [MediaBrowser] bound to
 * [MediaPlaybackService], walking the browse tree and then "tapping" a song. Runs against
 * whatever library + playlists are on the device, so it skips (not fails) when there's
 * nothing to browse.
 */
@RunWith(AndroidJUnit4::class)
class MediaBrowseTreeTest {
    @get:Rule
    val permissionRule: GrantPermissionRule = mediaPermissionRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as MigsMusicApplication
    private var browser: MediaBrowser? = null

    @Before
    fun setUp() {
        resetPlaybackForTest()
    }

    @After
    fun tearDown() {
        browser?.let { b ->
            onMain {
                b.pause()
                b.release()
            }
        }
        browser = null
        resetPlaybackForTest()
    }

    @Test
    fun manifestAdvertisesAndroidAutoAndLibraryService() {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        val descResId = appInfo.metaData?.getInt("com.google.android.gms.car.application") ?: 0
        assertTrue("com.google.android.gms.car.application meta-data missing", descResId != 0)

        // Auto only lists apps whose descriptor declares a media use.
        val parser = context.resources.getXml(descResId)
        var declaresMedia = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "uses") {
                for (i in 0 until parser.attributeCount) {
                    if (parser.getAttributeValue(i) == "media") declaresMedia = true
                }
            }
            parser.next()
        }
        assertTrue("automotive descriptor has no <uses name=\"media\"/>", declaresMedia)

        val ourService = MediaPlaybackService::class.java.name
        listOf(
            "androidx.media3.session.MediaLibraryService",
            "androidx.media3.session.MediaSessionService",
            "android.media.browse.MediaBrowserService",
        ).forEach { action ->
            val resolved = pm.queryIntentServices(Intent(action).setPackage(context.packageName), 0)
            assertTrue("$action does not resolve to $ourService", resolved.any { it.serviceInfo.name == ourService })
        }
    }

    @Test
    fun browseTreeExposesCategoriesPlaylistsAndSongs() {
        val b = connect()

        val root = await(onMain { b.getLibraryRoot(null) })
        assertEquals(LibraryResult.RESULT_SUCCESS, root.resultCode)
        assertEquals("root", root.value!!.mediaId)

        val categories = children(b, "root")
        assertEquals(
            listOf("Playlists", "Albums", "Artists", "Songs"),
            categories.map { it.mediaMetadata.title.toString() },
        )
        assertTrue(categories.all { it.mediaMetadata.isBrowsable == true })

        val expectedPlaylists = runBlocking { app.appContainer.playlistRepository.observePlaylists().first() }
        val playlists = children(b, "cat:playlists")
        assertEquals(expectedPlaylists.map { "pl:${it.id}" }, playlists.map { it.mediaId })
        assertEquals(expectedPlaylists.map { it.name }, playlists.map { it.mediaMetadata.title.toString() })

        val library = runBlocking { app.appContainer.libraryRepository.observeAllSongs().first() }
        if (library.isNotEmpty()) {
            val albums = children(b, "cat:albums")
            assertTrue(albums.isNotEmpty() && albums.all { it.mediaId.startsWith("al:") && it.mediaMetadata.isBrowsable == true })
            val artists = children(b, "cat:artists")
            assertTrue(artists.isNotEmpty() && artists.all { it.mediaId.startsWith("ar:") && it.mediaMetadata.isBrowsable == true })
            val firstPage = children(b, "cat:songs", pageSize = 25)
            assertEquals(minOf(25, library.size), firstPage.size)
            assertTrue(firstPage.all { it.mediaId.startsWith("sg:") && it.mediaId.endsWith("@cat:songs") })

            // Descending into an album yields playable songs carrying both the technical
            // and the display metadata fields.
            val albumSongs = children(b, albums.first().mediaId)
            assertTrue(albumSongs.isNotEmpty())
            albumSongs.forEach { assertPlayableSong(it, parentId = albums.first().mediaId) }
        }

        val target = expectedPlaylists.filter { it.songCount > 0 }.minByOrNull { it.songCount }
        if (target != null) {
            val expectedSongs = runBlocking { app.appContainer.playlistRepository.observePlaylistSongs(target.id).first() }
            val songs = children(b, "pl:${target.id}")
            assertEquals(expectedSongs.map { "sg:${it.songId}@pl:${target.id}" }, songs.map { it.mediaId })
            songs.forEach { assertPlayableSong(it, parentId = "pl:${target.id}") }
            // Album art is passed through whenever the library has it.
            assertEquals(
                expectedSongs.count { it.albumArtUri != null },
                songs.count { it.mediaMetadata.artworkUri != null },
            )
        }

        val item = await(onMain { b.getItem("cat:albums") })
        assertEquals(LibraryResult.RESULT_SUCCESS, item.resultCode)
        assertEquals("Albums", item.value!!.mediaMetadata.title.toString())

        // Unknown ids come back as an error result, never a crash.
        val bad = await(onMain { b.getChildren("nope:1", 0, 50, null) })
        assertEquals(LibraryResult.RESULT_ERROR_BAD_VALUE, bad.resultCode)
        val badItem = await(onMain { b.getItem("sg:999999999@pl:0") })
        assertEquals(LibraryResult.RESULT_ERROR_BAD_VALUE, badItem.resultCode)
    }

    @Test
    fun tappingAPlaylistSongQueuesWholePlaylistAndPublishesDisplayMetadata() {
        val b = connect()
        val playlists = runBlocking { app.appContainer.playlistRepository.observePlaylists().first() }
        val target = playlists.filter { it.songCount >= 2 }.minByOrNull { it.songCount }
        assumeTrue("needs a playlist with at least 2 songs on the device", target != null)

        val songs = children(b, "pl:${target!!.id}")
        val tapped = songs[1]
        onMain {
            b.volume = 0f
            b.setMediaItem(tapped)
            b.prepare()
            b.play()
        }

        waitUntil("playlist queued behind the tapped song", 30_000) {
            onMain {
                b.mediaItemCount == songs.size &&
                    b.currentMediaItemIndex == 1 &&
                    b.playbackState == Player.STATE_READY
            }
        }

        val metadata = onMain { b.mediaMetadata }
        assertEquals(tapped.mediaMetadata.title.toString(), metadata.title.toString())
        assertEquals(metadata.title.toString(), metadata.displayTitle.toString())
        assertEquals(metadata.artist.toString(), metadata.subtitle.toString())

        // The in-app UI follows what Auto started.
        waitUntil("in-app now-playing matches Auto", 10_000) {
            app.appContainer.playbackManager.uiState.value.currentSong?.title == tapped.mediaMetadata.title.toString()
        }

        // Skip-next works because the whole playlist was queued, not just the tapped song.
        onMain { b.seekToNextMediaItem() }
        waitUntil("advanced to the next playlist song", 15_000) {
            onMain { b.currentMediaItemIndex == 2 || (songs.size == 2 && b.currentMediaItemIndex == 1) }
        }
    }

    private fun assertPlayableSong(
        item: MediaItem,
        parentId: String,
    ) {
        assertTrue("bad song id ${item.mediaId}", item.mediaId.startsWith("sg:") && item.mediaId.endsWith("@$parentId"))
        val md = item.mediaMetadata
        assertEquals(true, md.isPlayable)
        assertEquals(false, md.isBrowsable)
        assertTrue("blank title for ${item.mediaId}", !md.title.isNullOrBlank())
        assertEquals(md.title.toString(), md.displayTitle.toString())
        assertEquals(md.artist.toString(), md.subtitle.toString())
    }

    private fun connect(): MediaBrowser {
        val token = SessionToken(context, ComponentName(context, MediaPlaybackService::class.java))
        val future = onMain { MediaBrowser.Builder(context, token).buildAsync() }
        return await(future).also { browser = it }
    }

    private fun children(
        b: MediaBrowser,
        parentId: String,
        pageSize: Int = 1000,
    ): List<MediaItem> {
        val result = await(onMain { b.getChildren(parentId, 0, pageSize, null) })
        assertEquals("getChildren($parentId) failed", LibraryResult.RESULT_SUCCESS, result.resultCode)
        return result.value!!
    }

    // MediaController/MediaBrowser methods must run on the looper they were built with (main).
    private fun <T> onMain(block: () -> T): T {
        var out: T? = null
        instrumentation.runOnMainSync { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun <T> await(future: ListenableFuture<T>): T = future.get(20, TimeUnit.SECONDS)

    private fun waitUntil(
        what: String,
        timeoutMillis: Long,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(250)
        }
        throw AssertionError("timed out waiting for: $what")
    }
}
