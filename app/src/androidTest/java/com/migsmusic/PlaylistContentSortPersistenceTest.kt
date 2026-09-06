package com.migsmusic

import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.migsmusic.ui.PlaylistContentSortOrder
import com.migsmusic.ui.UiTestTags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlaylistContentSortPersistenceTest {
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = mediaPermissionRule()

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val app: MigsMusicApplication
        get() = context.applicationContext as MigsMusicApplication

    private val testPlaylistName = "Migs-Sort-${UUID.randomUUID().toString().take(6)}"
    private var playlistId: Long? = null

    @Before
    fun resetSingletonState() {
        resetPlaybackForTest()
    }

    @After
    fun cleanUp() {
        playlistId?.let { id ->
            runBlocking { app.appContainer.playlistRepository.deletePlaylist(id) }
            forgetSortPreference(id)
        }
    }

    @Test
    fun perPlaylistSortSurvivesANewPreferencesInstance() {
        val id = 987_654_321L
        try {
            assertEquals(PlaylistContentSortOrder.DEFAULT, AppPreferences(context).playlistContentSortOrder(id))
            AppPreferences(context).setPlaylistContentSortOrder(id, PlaylistContentSortOrder.DEFAULT_DESC)
            assertEquals(PlaylistContentSortOrder.DEFAULT_DESC, AppPreferences(context).playlistContentSortOrder(id))
            // Scoped per playlist: a neighbour keeps its own default.
            assertEquals(PlaylistContentSortOrder.DEFAULT, AppPreferences(context).playlistContentSortOrder(id + 1))
        } finally {
            forgetSortPreference(id)
        }
    }

    @Test
    fun newestFirstStaysAppliedAfterLeavingAndReopeningThePlaylist() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return
        composeRule.waitForLibraryScanSettled(minSongs = 3)

        val songIds =
            runBlocking {
                app.appContainer.libraryRepository.observeAllSongs().first().take(3).map { it.id }
            }
        check(songIds.size == 3) { "Expected 3 song IDs from library, got ${songIds.size}" }
        val id =
            runBlocking {
                val created = app.appContainer.playlistRepository.createPlaylist(testPlaylistName)
                songIds.forEach { app.appContainer.playlistRepository.addSong(created, it) }
                created
            }
        playlistId = id

        composeRule.onNodeWithTag(UiTestTags.PlaylistsTab).performClick()
        openTestPlaylist()
        val defaultOrder = rowTitles()
        assumeTrue("need three distinct titles to observe ordering", defaultOrder.toSet().size == 3)

        chooseSort(PlaylistContentSortOrder.DEFAULT_DESC)
        composeRule.waitUntil(timeoutMillis = 5_000) { rowTitles() == defaultOrder.reversed() }

        // Leave via system back and come straight back in: the choice must stick.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitUntil(timeoutMillis = 10_000) { composeRule.hasNode(UiTestTags.PlaylistsScreen) }
        openTestPlaylist()
        composeRule.waitUntil(timeoutMillis = 5_000) { rowTitles() == defaultOrder.reversed() }
        assertEquals(defaultOrder.reversed(), rowTitles())
        assertEquals(PlaylistContentSortOrder.DEFAULT_DESC, AppPreferences(context).playlistContentSortOrder(id))

        // Switching back to the default restores the canonical order and is remembered too.
        chooseSort(PlaylistContentSortOrder.DEFAULT)
        composeRule.waitUntil(timeoutMillis = 5_000) { rowTitles() == defaultOrder }
        assertEquals(PlaylistContentSortOrder.DEFAULT, AppPreferences(context).playlistContentSortOrder(id))
    }

    private fun openTestPlaylist() {
        composeRule.waitUntil(timeoutMillis = 10_000) { composeRule.hasNode(UiTestTags.PlaylistRow) }
        // The user's real playlists may push ours below the fold of the LazyColumn.
        composeRule
            .onNode(hasScrollAction() and hasAnyDescendant(hasTestTag(UiTestTags.PlaylistRow)), useUnmergedTree = true)
            .performScrollToNode(hasText(testPlaylistName))
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(testPlaylistName).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText(testPlaylistName).onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(UiTestTags.PlaylistSongRow).fetchSemanticsNodes().size == 3
        }
    }

    private fun chooseSort(order: PlaylistContentSortOrder) {
        composeRule.onAllNodesWithTag(UiTestTags.SortButton).onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.hasNode(UiTestTags.sortOption(order.name)) }
        composeRule.onNodeWithTag(UiTestTags.sortOption(order.name)).performClick()
    }

    private fun rowTitles(): List<String?> = (0 until 3).map { composeRule.titleOfRow(UiTestTags.PlaylistSongRow, it) }

    private fun forgetSortPreference(id: Long) {
        context
            .getSharedPreferences("migs-music-prefs", Context.MODE_PRIVATE)
            .edit()
            .remove("playlist_content_sort_$id")
            .apply()
    }
}
