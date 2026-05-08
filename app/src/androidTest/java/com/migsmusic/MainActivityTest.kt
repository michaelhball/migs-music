package com.migsmusic

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.migsmusic.ui.UiTestTags
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            },
        )

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetSingletonState() {
        resetPlaybackForTest()
    }

    @Test
    fun launchesAndShowsCoreUi() {
        // No app-level TopAppBar / "MIGS Music" title anymore — it lived for one
        // commit and was removed because the system task switcher already shows
        // the app label. Verify the app actually launches by waiting for either
        // the library content or the permission gate.
        waitForLibraryOrPermissionGate()
    }

    @Test
    fun navigatesPrimaryTabs() {
        waitForLibraryOrPermissionGate()
        if (hasNode(UiTestTags.PermissionButton)) return

        composeRule.onNodeWithTag(UiTestTags.SongsTab).assertIsSelected()

        composeRule.onNodeWithTag(UiTestTags.FoldersTab).performClick()
        waitForNode(UiTestTags.FoldersScreen)
        composeRule.onNodeWithTag(UiTestTags.FoldersScreen).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTestTags.FoldersTab).assertIsSelected()

        composeRule.onNodeWithTag(UiTestTags.PlaylistsTab).performClick()
        waitForNode(UiTestTags.PlaylistsScreen)
        composeRule.onNodeWithTag(UiTestTags.PlaylistsScreen).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTestTags.PlaylistsTab).assertIsSelected()

        composeRule.onNodeWithTag(UiTestTags.QueueTab).performClick()
        waitForNode(UiTestTags.QueueScreen)
        composeRule.onNodeWithTag(UiTestTags.QueueScreen).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTestTags.QueueTab).assertIsSelected()

        composeRule.onNodeWithTag(UiTestTags.SongsTab).performClick()
        waitForNode(UiTestTags.SongsScreen)
        composeRule.onNodeWithTag(UiTestTags.SongsScreen).assertIsDisplayed()
    }

    @Test
    fun startsPlaybackFromVisibleSongAndOpensPlayer() {
        waitForLibraryOrPermissionGate()
        if (hasNode(UiTestTags.PermissionButton)) return

        composeRule.waitUntil(timeoutMillis = 30_000) {
            hasNode(UiTestTags.SongRow)
        }

        composeRule.onAllNodesWithTag(UiTestTags.SongRow).onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            hasNode(UiTestTags.MiniPlayer)
        }

        composeRule.onNodeWithTag(UiTestTags.MiniPlayerPlayPause).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTestTags.MiniPlayerNext).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTestTags.MiniPlayerPrevious).assertIsDisplayed()

        composeRule.onNodeWithTag(UiTestTags.MiniPlayer).performClick()
        composeRule.onNodeWithTag(UiTestTags.PlayerScreen).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTestTags.PlayerPlayPause).assertIsDisplayed()
    }

    private fun waitForLibraryOrPermissionGate() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            hasNode(UiTestTags.PermissionButton) ||
                hasNode(UiTestTags.SearchField) ||
                composeRule.onAllNodesWithText("Allow music access").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Search songs").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun hasNode(tag: String): Boolean {
        return composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForNode(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            hasNode(tag)
        }
    }
}
