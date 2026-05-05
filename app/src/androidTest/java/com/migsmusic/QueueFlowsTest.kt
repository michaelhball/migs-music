package com.migsmusic

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
class QueueFlowsTest {
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = mediaPermissionRule()

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetSingletonState() {
        resetPlaybackForTest()
    }

    /**
     * Songs now expose actions via a "..." overflow menu (or long-press). Tap the row's
     * menu icon, wait for the named action, tap it. Menu auto-dismisses.
     */
    private fun openSongMenuAndTap(
        rowIndex: Int,
        actionTag: String,
    ) {
        composeRule.onAllNodesWithTag(UiTestTags.SongRowMenu)[rowIndex].performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.hasNode(actionTag) }
        composeRule.onNodeWithTag(actionTag).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { !composeRule.hasNode(actionTag) }
    }

    @Test
    fun playNextAndPlayLaterInsertInExpectedOrder() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(UiTestTags.SongRow).fetchSemanticsNodes().size >= 4
        }

        // Start playback at row 0
        composeRule.onAllNodesWithTag(UiTestTags.SongRow)[0].performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { composeRule.hasNode(UiTestTags.MiniPlayer) }

        // Read title + open menu + tap action, in tight pairs (async lazy-column work can shift
        // indices between operations). Each row's "..." menu opens a single SongActionNext
        // (or Later) menu item — only one at a time, so no [i] indexing needed once open.
        val rowBTitle = composeRule.titleOfRow(UiTestTags.SongRow, 1)
        openSongMenuAndTap(rowIndex = 1, actionTag = UiTestTags.SongActionNext)

        val rowCTitle = composeRule.titleOfRow(UiTestTags.SongRow, 2)
        openSongMenuAndTap(rowIndex = 2, actionTag = UiTestTags.SongActionLater)

        val rowDTitle = composeRule.titleOfRow(UiTestTags.SongRow, 3)
        openSongMenuAndTap(rowIndex = 3, actionTag = UiTestTags.SongActionNext)

        require(!rowBTitle.isNullOrBlank() && !rowCTitle.isNullOrBlank() && !rowDTitle.isNullOrBlank()) {
            "Could not read song row titles for queue test"
        }

        // Switch to Queue tab and assert the upcoming order: [B, D, C, ...natural...]
        // Per QueueEngine: addNext appends to nextItems, so after add(B), add(D) → nextItems=[B, D].
        // addLater appends to laterItems → laterItems=[C]. upcoming = nextItems + laterItems + natural.
        composeRule.onNodeWithTag(UiTestTags.QueueTab).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.hasNode(UiTestTags.queueUpcomingRow(0)) &&
                composeRule.hasNode(UiTestTags.queueUpcomingRow(1)) &&
                composeRule.hasNode(UiTestTags.queueUpcomingRow(2))
        }

        val upcoming0 = composeRule.titleOfRow(UiTestTags.queueUpcomingRow(0), 0)
        val upcoming1 = composeRule.titleOfRow(UiTestTags.queueUpcomingRow(1), 0)
        val upcoming2 = composeRule.titleOfRow(UiTestTags.queueUpcomingRow(2), 0)

        check(upcoming0 == rowBTitle) {
            "Expected upcoming[0] to be '$rowBTitle' (Play Next first), got '$upcoming0'"
        }
        check(upcoming1 == rowDTitle) {
            "Expected upcoming[1] to be '$rowDTitle' (Play Next second), got '$upcoming1'"
        }
        check(upcoming2 == rowCTitle) {
            "Expected upcoming[2] to be '$rowCTitle' (Play Later first), got '$upcoming2'"
        }
    }

    @Test
    fun queueClearRemovesUpcomingButKeepsCurrent() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(UiTestTags.SongRow).fetchSemanticsNodes().size >= 2
        }

        composeRule.onAllNodesWithTag(UiTestTags.SongRow)[0].performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { composeRule.hasNode(UiTestTags.MiniPlayer) }
        openSongMenuAndTap(rowIndex = 1, actionTag = UiTestTags.SongActionNext)

        composeRule.onNodeWithTag(UiTestTags.QueueTab).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { composeRule.hasNode(UiTestTags.queueUpcomingRow(0)) }
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.hasNode(UiTestTags.QueueClear) }

        composeRule.onNodeWithTag(UiTestTags.QueueClear).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            !composeRule.hasNode(UiTestTags.queueUpcomingRow(0))
        }
        // Current song still showing
        check(composeRule.hasNode(UiTestTags.QueueCurrentRow)) { "Current row disappeared after clear" }
    }
}
