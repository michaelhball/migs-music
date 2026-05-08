package com.migsmusic

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.migsmusic.ui.UiTestTags
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchAndPlayerTest {
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = mediaPermissionRule()

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetSingletonState() {
        resetPlaybackForTest()
    }

    @Test
    fun searchFiltersSongs() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(UiTestTags.SongRow).fetchSemanticsNodes().size >= 1
        }

        val firstTitle = composeRule.titleOfRow(UiTestTags.SongRow, 0)
        require(!firstTitle.isNullOrBlank()) { "Could not read first song title" }

        // Pick a substring that's likely unique enough to filter heavily.
        // Use the first 4 chars of the title (or full title if shorter).
        val needle = firstTitle.take(4)
        val before = composeRule.onAllNodesWithTag(UiTestTags.SongRow).fetchSemanticsNodes().size

        composeRule.onNodeWithTag(UiTestTags.SearchField).performTextInput(needle)
        // debounce 150ms in viewmodel + flow turnover
        composeRule.waitUntil(timeoutMillis = 5_000) {
            val current = composeRule.onAllNodesWithTag(UiTestTags.SongRow).fetchSemanticsNodes().size
            current in 1..before
        }

        // Verify the matching first row contains the needle (case-insensitive)
        val titleAfter = composeRule.titleOfRow(UiTestTags.SongRow, 0)
        require(!titleAfter.isNullOrBlank()) { "Search returned empty title at index 0" }
        check(
            titleAfter.contains(needle, ignoreCase = true) ||
                titleAfter.contains(needle.lowercase()) ||
                titleAfter.contains(needle.uppercase()),
        ) {
            "Top search result '$titleAfter' does not contain query '$needle'"
        }
    }

    @Test
    fun fullPlayerExposesAllTransportControls() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(UiTestTags.SongRow).fetchSemanticsNodes().size >= 2
        }

        composeRule.onAllNodesWithTag(UiTestTags.SongRow)[0].performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { composeRule.hasNode(UiTestTags.MiniPlayer) }

        // Expand to full player
        composeRule.onNodeWithTag(UiTestTags.MiniPlayer).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.hasNode(UiTestTags.PlayerScreen) }

        composeRule.onNodeWithTag(UiTestTags.PlayerScreen).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTestTags.PlayerPlayPause).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTestTags.PlayerNext).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTestTags.PlayerPrevious).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTestTags.PlayerRepeat).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTestTags.PlayerOpenQueue).assertIsDisplayed()
    }

    @Test
    fun playPauseTogglesAndRepeatCycles() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(UiTestTags.SongRow).fetchSemanticsNodes().size >= 1
        }

        composeRule.onAllNodesWithTag(UiTestTags.SongRow)[0].performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) { composeRule.hasNode(UiTestTags.MiniPlayer) }
        composeRule.onNodeWithTag(UiTestTags.MiniPlayer).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.hasNode(UiTestTags.PlayerScreen) }

        // Wait for actual playback to converge ("Pause" content-description means
        // playing). Player buttons are icons now, not text — the contentDescription
        // is the only stable way to read the visible state.
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.firstContentDescriptionUnder(UiTestTags.PlayerPlayPause) == "Pause"
        }

        // From the known playing state, tap toggles to paused.
        composeRule.onNodeWithTag(UiTestTags.PlayerPlayPause).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.firstContentDescriptionUnder(UiTestTags.PlayerPlayPause) == "Play"
        }
        // Toggle back so we don't leave audio paused mid-suite.
        composeRule.onNodeWithTag(UiTestTags.PlayerPlayPause).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.firstContentDescriptionUnder(UiTestTags.PlayerPlayPause) == "Pause"
        }

        // Cycle repeat: Off → All → One → Off. The icon's contentDescription is
        // always "Repeat" (we communicate the mode via tint + icon variant), so we
        // can't compare descriptions across taps. Instead, verify the playback
        // manager's repeatMode actually advances by reading uiState.
        val app = ApplicationProvider.getApplicationContext<com.migsmusic.MigsMusicApplication>()
        val pm = app.appContainer.playbackManager
        val r0 = pm.uiState.value.repeatMode
        composeRule.onNodeWithTag(UiTestTags.PlayerRepeat).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { pm.uiState.value.repeatMode != r0 }
        val r1 = pm.uiState.value.repeatMode
        composeRule.onNodeWithTag(UiTestTags.PlayerRepeat).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { pm.uiState.value.repeatMode != r1 }
    }
}
