package com.migsmusic

import android.os.ParcelFileDescriptor
import android.view.KeyEvent
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
 * Validates that when the user toggles play/pause inside the app, the platform-level
 * MediaSession state visible to lockscreen/notification/Bluetooth changes accordingly.
 *
 * This is the robust replacement for testing actual media key dispatch (which depends
 * on platform routing that varies across OEMs and Android versions): we prove the
 * session publishes the right state, which is what those external surfaces consume.
 */
@RunWith(AndroidJUnit4::class)
class MediaKeyTest {
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = mediaPermissionRule()

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetSingletonState() {
        resetPlaybackForTest()
    }

    @Test
    fun mediaSessionStateMatchesAppPauseAndResume() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return

        composeRule.waitForLibraryScanSettled(minSongs = 2)

        composeRule.onAllNodesWithTag(UiTestTags.SongRow)[0].performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) { composeRule.hasNode(UiTestTags.MiniPlayer) }
        composeRule.onNodeWithTag(UiTestTags.MiniPlayer).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.hasNode(UiTestTags.PlayerScreen) }

        // Wait for actual playback (Pause label appears).
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.firstTextUnder(UiTestTags.PlayerPlayPause) == "Pause"
        }
        // And for the platform's view of the session to reflect PLAYING.
        waitForSessionState("state=PLAYING(3)")

        // Tap pause inside app.
        composeRule.onNodeWithTag(UiTestTags.PlayerPlayPause).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.firstTextUnder(UiTestTags.PlayerPlayPause) == "Play"
        }
        waitForSessionState("state=PAUSED(2)")

        // Tap resume inside app.
        composeRule.onNodeWithTag(UiTestTags.PlayerPlayPause).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.firstTextUnder(UiTestTags.PlayerPlayPause) == "Pause"
        }
        waitForSessionState("state=PLAYING(3)")

        // Pause to leave clean.
        composeRule.onNodeWithTag(UiTestTags.PlayerPlayPause).performClick()
    }

    private fun waitForSessionState(needle: String, timeoutMillis: Long = 15_000L) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var lastDump = ""
        while (System.currentTimeMillis() < deadline) {
            lastDump = runShell("dumpsys media_session")
            val ourBlock = lastDump.lines()
                .filter { it.contains("com.migsmusic") || it.contains("PlaybackState") }
            if (ourBlock.any { it.contains(needle) }) return
            Thread.sleep(300)
        }
        error("MediaSession state did not become '$needle'. Last excerpt:\n${lastDump.take(4000)}")
    }

    private fun runShell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pfd: ParcelFileDescriptor = automation.executeShellCommand(command)
        return try {
            FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            ""
        } finally {
            try { pfd.close() } catch (_: IOException) {}
        }
    }
}
