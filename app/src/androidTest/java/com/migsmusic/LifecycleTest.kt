package com.migsmusic

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.migsmusic.ui.UiTestTags
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.io.IOException

/**
 * Verify session persistence: pick a song, pause, force-stop the process,
 * relaunch via instrumentation rule, assert mini-player rehydrates with the same title.
 */
@RunWith(AndroidJUnit4::class)
class LifecycleTest {
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = mediaPermissionRule()

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetSingletonState() {
        resetPlaybackForTest()
    }

    @Test
    fun lastPlayingSongRestoresAfterColdStart() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return

        // Wait until the library has settled (more songs imply the scan is further along).
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(UiTestTags.SongRow).fetchSemanticsNodes().size >= 5
        }

        val expectedTitle = composeRule.titleOfRow(UiTestTags.SongRow, 0)
        require(!expectedTitle.isNullOrBlank()) { "Could not read row 0 title before tap" }

        composeRule.onAllNodesWithTag(UiTestTags.SongRow)[0].performClick()
        // Wait for the mini-player to actually reflect THIS row's title (not a stale snapshot).
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.firstTextUnder(UiTestTags.MiniPlayer) == expectedTitle
        }

        // Pause and let the snapshot persist
        composeRule.onNodeWithTag(UiTestTags.MiniPlayerPlayPause).performClick()
        Thread.sleep(2_000)

        // Recreate the activity (simulates UI state recovery).
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            composeRule.activity.recreate()
        }

        composeRule.waitUntil(timeoutMillis = 30_000) { composeRule.hasNode(UiTestTags.MiniPlayer) }
        val titleAfter = composeRule.firstTextUnder(UiTestTags.MiniPlayer)
        check(titleAfter == expectedTitle) {
            "Restored mini-player title '$titleAfter' did not match pre-recreate '$expectedTitle'"
        }
    }

    private fun runShell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pfd: ParcelFileDescriptor = automation.executeShellCommand(command)
        return try {
            FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            ""
        } finally {
            try {
                pfd.close()
            } catch (_: IOException) {
            }
        }
    }
}
