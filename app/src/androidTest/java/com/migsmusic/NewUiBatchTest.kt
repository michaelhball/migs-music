package com.migsmusic

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.migsmusic.ui.UiTestTags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * End-to-end coverage for the recent UI batch:
 *  - Snackbar appears after Play Next.
 *  - "Restore import order" reverts a manually-reordered playlist to its initial order.
 *  - Folder breadcrumb segments are tappable and navigate.
 *  - Album sort selection persists across an Activity recreate (covers the SharedPreferences
 *    write + subsequent re-read on cold-ish start; full process-death coverage would need
 *    a separate harness).
 *
 * Mini-player horizontal swipe-to-skip is intentionally not tested here — gesture recognition
 * tied to physical pixel deltas is flaky in instrumentation, and the underlying skip logic
 * is already covered by RobustnessTest's rapid-skip path.
 */
@RunWith(AndroidJUnit4::class)
class NewUiBatchTest {
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = mediaPermissionRule()

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val app: MigsMusicApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MigsMusicApplication

    @Before
    fun resetSingletonState() {
        resetPlaybackForTest()
        // Wipe any lingering test playlists so the alphabetical LazyColumn shows what we create.
        runBlocking {
            app.appContainer.playlistRepository.observePlaylists().first().forEach {
                app.appContainer.playlistRepository.deletePlaylist(it.id)
            }
        }
    }

    @Test
    fun snackbarAppearsAfterPlayNext() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return
        composeRule.waitForLibraryScanSettled(minSongs = 2)

        // Start playback so there's a queue context for "Play next".
        composeRule.onAllNodesWithTag(UiTestTags.SongRow)[0].performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) { composeRule.hasNode(UiTestTags.MiniPlayer) }

        // Open another song's overflow menu and tap Play Next.
        composeRule.onAllNodesWithTag(UiTestTags.SongRowMenu)[1].performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.hasNode(UiTestTags.SongActionNext) }
        composeRule.onNodeWithTag(UiTestTags.SongActionNext).performClick()

        // The snackbar text should appear within the message-host's animation window.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Added to queue").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun restoreOriginalOrderRevertsManualReorder() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return
        composeRule.waitForLibraryScanSettled(minSongs = 3)

        val playlistName = "Migs-Restore-${UUID.randomUUID().toString().take(6)}"

        // Seed a playlist with 3 songs in a known order via the repository.
        val songIds =
            runBlocking {
                app.appContainer.libraryRepository.observeAllSongs().first().take(3).map { it.id }
            }
        val playlistId =
            runBlocking {
                app.appContainer.playlistRepository.createPlaylistWithSongs(playlistName, songIds)
            }

        // Manually reorder: move song[0] → end. (Direct repo call, faster than driving drag.)
        runBlocking {
            app.appContainer.playlistRepository.moveSong(playlistId, fromIndex = 0, toIndex = 2)
        }

        // Verify the song-id order is now [1, 2, 0].
        val afterMove =
            runBlocking {
                app.appContainer.playlistRepository.observePlaylistSongs(playlistId).first()
                    .map { it.songId }
            }
        check(afterMove == listOf(songIds[1], songIds[2], songIds[0])) {
            "Setup failed: expected [${songIds[1]}, ${songIds[2]}, ${songIds[0]}] after move, got $afterMove"
        }

        // Open the playlist via UI and tap "Restore order".
        composeRule.onNodeWithTag(UiTestTags.PlaylistsTab).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(playlistName).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText(playlistName).onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(UiTestTags.PlaylistSongRow).fetchSemanticsNodes().size == 3
        }
        composeRule.onNodeWithTag(UiTestTags.PlaylistDetailRestoreOrder).performClick()

        // Wait for the restore to propagate via Room → Flow → recompose.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            val current =
                runBlocking {
                    app.appContainer.playlistRepository.observePlaylistSongs(playlistId).first()
                        .map { it.songId }
                }
            current == songIds
        }

        // Cleanup
        runBlocking { app.appContainer.playlistRepository.deletePlaylist(playlistId) }
    }

    @Test
    fun folderBreadcrumbSegmentNavigates() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return
        composeRule.waitForLibraryScanSettled(minSongs = 1)

        composeRule.onNodeWithTag(UiTestTags.FoldersTab).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { composeRule.hasNode(UiTestTags.FoldersScreen) }

        // Need at least a top-level folder with a sub-folder for the breadcrumb to appear.
        // Drill in twice if possible; if not, the assertion at the end gracefully exits.
        val topLevelFolders = composeRule.onAllNodesWithTag(UiTestTags.FolderRow).fetchSemanticsNodes()
        if (topLevelFolders.isEmpty()) return // Nothing to drill into.
        composeRule.onAllNodesWithTag(UiTestTags.FolderRow)[0].performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.hasNode(UiTestTags.FolderDetailScreen) }

        val subfolders = composeRule.onAllNodesWithTag(UiTestTags.FolderRow).fetchSemanticsNodes()
        if (subfolders.isEmpty()) return // Just one level deep — breadcrumb has nothing to test.
        composeRule.onAllNodesWithTag(UiTestTags.FolderRow)[0].performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.hasNode(UiTestTags.FolderDetailScreen) }

        // We're now two levels deep. The breadcrumb should have at least 2 segments — the
        // first is tappable (it's an ancestor); tapping it should navigate back to that folder.
        val segments = composeRule.onAllNodesWithTag(UiTestTags.FolderBreadcrumbSegment).fetchSemanticsNodes()
        check(segments.size >= 2) { "Expected ≥2 breadcrumb segments two levels deep, got ${segments.size}" }
        composeRule.onAllNodesWithTag(UiTestTags.FolderBreadcrumbSegment)[0].performClick()
        // After tapping the root segment, we should be back on a FolderDetailScreen with a
        // shallower path (and thus fewer breadcrumb segments).
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(UiTestTags.FolderBreadcrumbSegment).fetchSemanticsNodes().size <
                segments.size
        }
    }

    @Test
    fun albumSortSelectionPersistsAcrossActivityRecreate() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return
        composeRule.waitForLibraryScanSettled(minSongs = 1)

        // Navigate to Albums via the Songs tab's "Albums" button.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Albums").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Albums").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.hasNode(UiTestTags.SortButton) }

        // Open the sort menu and pick "Most songs" (a non-default).
        composeRule.onNodeWithTag(UiTestTags.SortButton).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.hasNode(UiTestTags.sortOption("SONG_COUNT_DESC"))
        }
        composeRule.onNodeWithTag(UiTestTags.sortOption("SONG_COUNT_DESC")).performClick()

        // Verify the SortButton label updated to reflect the new selection.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Most songs").fetchSemanticsNodes().isNotEmpty()
        }

        // Recreate the Activity (covers config-change-style state restoration; SharedPreferences
        // survives the recreate, so the new ViewModel's initial sortOrder should match).
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            composeRule.activity.recreate()
        }

        // After recreate the user lands back on Songs (default start dest); navigate again.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Albums").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Albums").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.hasNode(UiTestTags.SortButton) }

        // The SortButton should still display "Most songs", proving the choice persisted.
        check(composeRule.onAllNodesWithText("Most songs").fetchSemanticsNodes().isNotEmpty()) {
            "Album sort selection did not persist across Activity recreate"
        }

        // Reset to default so we don't pollute future test runs.
        composeRule.onNodeWithTag(UiTestTags.SortButton).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.hasNode(UiTestTags.sortOption("TITLE_ASC"))
        }
        composeRule.onNodeWithTag(UiTestTags.sortOption("TITLE_ASC")).performClick()
    }
}
