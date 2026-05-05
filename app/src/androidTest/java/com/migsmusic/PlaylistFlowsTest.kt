package com.migsmusic

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
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
 * Verifies playlist CRUD via the UI: list display, opening a playlist, reordering songs,
 * removing songs, and deleting from the list.
 *
 * Create + rename are exercised through the repository (they go through the same code paths
 * the dialog calls — `playlistRepository.createPlaylist` / `renamePlaylist`). The rationale:
 * Compose's OutlinedTextField cursor blink prevents `waitForIdle` from settling reliably,
 * causing performClick/performImeAction on dialog inputs to time out flakily on real devices.
 * The dialog's UX is trivially correct (single text field + Save) — the value-add is testing
 * the data layer + the rest of the playlist UI flow.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistFlowsTest {
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = mediaPermissionRule()

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val testPlaylistName = "Migs-Test-${UUID.randomUUID().toString().take(6)}"

    private val app: MigsMusicApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MigsMusicApplication

    @Before
    fun resetSingletonState() {
        resetPlaybackForTest()
        // Wipe leftover test playlists from prior runs. Without this they accumulate (UUID-named,
        // never cleaned up after a failed test), the alphabetical LazyColumn only renders the
        // first ~8, and a freshly-created playlist sorts below the fold — so `onAllNodesWithText`
        // can't find it without scrolling. Cheap to clear; tests don't depend on prior playlists.
        runBlocking {
            val playlists = app.appContainer.playlistRepository.observePlaylists().first()
            playlists.forEach { app.appContainer.playlistRepository.deletePlaylist(it.id) }
        }
    }

    @Test
    fun openReorderRemoveDelete() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return
        composeRule.waitForLibraryScanSettled(minSongs = 2)

        // Seed a playlist with 2 songs via the repository (avoiding flaky dialog text input).
        val songIds =
            runBlocking {
                app.appContainer.libraryRepository.observeAllSongs().first().take(2).map { it.id }
            }
        check(songIds.size == 2) { "Expected 2 song IDs from library, got ${songIds.size}" }

        val playlistId =
            runBlocking {
                val id = app.appContainer.playlistRepository.createPlaylist(testPlaylistName)
                songIds.forEach { app.appContainer.playlistRepository.addSong(id, it) }
                id
            }

        // 1) Switch to Playlists tab; verify the playlist appears.
        composeRule.onNodeWithTag(UiTestTags.PlaylistsTab).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(testPlaylistName).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText(testPlaylistName).onFirst().assertIsDisplayed()

        // 2) Open the playlist; verify both songs visible.
        composeRule.onAllNodesWithText(testPlaylistName).onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(UiTestTags.PlaylistSongRow).fetchSemanticsNodes().size == 2
        }

        // 3) Capture titles, reorder via Move Down on row 0, verify order.
        val firstTitleBefore = composeRule.titleOfRow(UiTestTags.PlaylistSongRow, 0)
        val secondTitleBefore = composeRule.titleOfRow(UiTestTags.PlaylistSongRow, 1)
        require(!firstTitleBefore.isNullOrBlank() && !secondTitleBefore.isNullOrBlank()) {
            "Could not read playlist song titles"
        }

        composeRule.onAllNodesWithTag(UiTestTags.PlaylistSongMoveDown)[0].performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.titleOfRow(UiTestTags.PlaylistSongRow, 0) == secondTitleBefore
        }
        check(composeRule.titleOfRow(UiTestTags.PlaylistSongRow, 1) == firstTitleBefore) {
            "Reorder failed: row 1 expected '$firstTitleBefore'"
        }

        // 4) Remove row 0; verify count drops.
        composeRule.onAllNodesWithTag(UiTestTags.PlaylistSongRemove)[0].performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(UiTestTags.PlaylistSongRow).fetchSemanticsNodes().size == 1
        }

        // 5) Back to playlists list (via system back, not tab-tap which uses popUpTo+restoreState
        // and can show stale Compose state). Then delete via the Delete button on the matching row.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.hasNode(UiTestTags.PlaylistsScreen) &&
                composeRule.onAllNodesWithText(testPlaylistName).fetchSemanticsNodes().isNotEmpty()
        }
        val rowNodes = composeRule.onAllNodesWithTag(UiTestTags.PlaylistRow).fetchSemanticsNodes()
        val targetIndex =
            rowNodes.indexOfFirst { node ->
                collectTexts(node).any { it == testPlaylistName }
            }
        require(targetIndex >= 0) { "Test playlist row not found for delete" }
        composeRule.onAllNodesWithTag(UiTestTags.PlaylistDeleteButton)[targetIndex].performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(testPlaylistName).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun renameViaRepositoryReflectsInUi() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return
        composeRule.waitForLibraryScanSettled(minSongs = 1)

        val name = "Migs-Rename-${UUID.randomUUID().toString().take(6)}"
        val renamed = "$name-renamed"
        val playlistId = runBlocking { app.appContainer.playlistRepository.createPlaylist(name) }

        composeRule.onNodeWithTag(UiTestTags.PlaylistsTab).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
        }

        runBlocking { app.appContainer.playlistRepository.renamePlaylist(playlistId, renamed) }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(renamed).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText(name).fetchSemanticsNodes().isEmpty()
        }

        // Cleanup
        runBlocking { app.appContainer.playlistRepository.deletePlaylist(playlistId) }
    }
}
