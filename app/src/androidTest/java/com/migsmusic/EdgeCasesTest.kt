package com.migsmusic

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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

/**
 * Edge cases that are easy to break — empty queue, nothing playing, search miss, lots of
 * Play-Later spam, system back from various screens, skip past end of queue.
 */
@RunWith(AndroidJUnit4::class)
class EdgeCasesTest {
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = mediaPermissionRule()

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val app: MigsMusicApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MigsMusicApplication

    @Before
    fun resetSingletonState() {
        resetPlaybackForTest()
    }

    @Test
    fun queueTabWithNoPlaybackShowsCleanly() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return

        // Force-stop playback first via API (so this test is independent of test order).
        runBlocking { app.appContainer.playbackManager.stopForTaskRemoval() }

        composeRule.onNodeWithTag(UiTestTags.QueueTab).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.hasNode(UiTestTags.QueueScreen) }
        composeRule.onNodeWithTag(UiTestTags.QueueScreen).assertIsDisplayed()
        // No upcoming, no current, no history. The route should still render the header.
        check(composeRule.onAllNodesWithText("Now Playing Queue").fetchSemanticsNodes().isNotEmpty()) {
            "Empty queue tab missing its header"
        }
    }

    @Test
    fun searchWithNoResultsDoesNotCrash() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return
        composeRule.waitForLibraryScanSettled(minSongs = 1)

        // Type a search query that won't match anything realistic.
        val noMatch = "zzqxq_no_match_${System.currentTimeMillis()}"
        composeRule.onNodeWithTag(UiTestTags.SearchField).performTextInput(noMatch)
        // Wait for the debounce and assert zero rows are visible.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(UiTestTags.SongRow).fetchSemanticsNodes().isEmpty()
        }
        // Clear with system back / focus loss not necessary; just navigate away.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        // Going back from search field should NOT crash the app (defensive — pressBack on a TextField
        // typically dismisses keyboard rather than killing the activity).
        composeRule.waitUntil(timeoutMillis = 3_000) { composeRule.hasNode(UiTestTags.SongsScreen) }
    }

    @Test
    fun rapidPlayLaterSpamResultsInExpectedQueueLength() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return
        composeRule.waitForLibraryScanSettled(minSongs = 3)

        composeRule.onAllNodesWithTag(UiTestTags.SongRow)[0].performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) { composeRule.hasNode(UiTestTags.MiniPlayer) }

        // Spam directly through PlaybackManager — the UI's overflow-menu pattern doesn't
        // support real rapid spam (each tap requires open + select + close).
        val pm = app.appContainer.playbackManager
        val songIds = kotlinx.coroutines.runBlocking {
            app.appContainer.libraryRepository.observeAllSongs().first()
        }.map { it.id }
        require(songIds.size >= 5) { "Need at least 5 songs to spam Play Later from" }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            repeat(4) { pm.playLater(songIds[1]) }
        }

        composeRule.onNodeWithTag(UiTestTags.QueueTab).performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.hasNode(UiTestTags.queueUpcomingRow(3))
        }
    }

    @Test
    fun skippingPastEndOfQueueDoesNotCrash() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return
        composeRule.waitForLibraryScanSettled(minSongs = 1)

        // Seed an explicit single-song queue via the playback manager so we control what "end" means.
        val songId = runBlocking {
            app.appContainer.libraryRepository.observeAllSongs().first().first().id
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            app.appContainer.playbackManager.playContext(listOf(songId), 0, false)
        }
        composeRule.waitUntil(timeoutMillis = 15_000) { composeRule.hasNode(UiTestTags.MiniPlayer) }

        // Press Next 10 times even though there's only one item in the queue.
        repeat(10) {
            composeRule.onNodeWithTag(UiTestTags.MiniPlayerNext).performClick()
        }

        Thread.sleep(1_000)
        // App should still be alive: the MiniPlayer should still be visible (with the single song
        // still current — skipping past end is a no-op in our PlaybackManager).
        check(composeRule.hasNode(UiTestTags.MiniPlayer)) { "App died after skipping past end of queue" }
    }

    @Test
    fun systemBackFromSongsScreenDoesNotCrash() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return

        // Pressing back on the start destination finishes the activity. We just verify no crash
        // in the app process. The activity will go away; we re-launch via a fresh tap.
        // Since the test rule recreates the activity per @Test method, just running this test and
        // letting it finish without a logcat FATAL is sufficient.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        Thread.sleep(500)
        // No assertion here: success = no crash. If the activity died gracefully, the next test rebuilds.
    }
}
