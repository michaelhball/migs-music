package com.migsmusic

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
 * Verifies that swiping the app away from Recents stops playback and tears down the
 * foreground service — the user explicitly does NOT want music to keep playing after
 * they swipe-close the app.
 *
 * Drives the same code path as a real swipe via `Activity.finishAndRemoveTask()`,
 * which causes the system to invoke `Service.onTaskRemoved` on the running
 * MediaPlaybackService.
 */
@RunWith(AndroidJUnit4::class)
class SwipeAwayTest {
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = mediaPermissionRule()

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetSingletonState() {
        resetPlaybackForTest()
    }

    @Test
    fun swipeAwayStopsPlaybackAndForegroundService() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return
        composeRule.waitForLibraryScanSettled(minSongs = 1)

        // Start playback
        composeRule.onAllNodesWithTag(UiTestTags.SongRow)[0].performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) { composeRule.hasNode(UiTestTags.MiniPlayer) }

        // Confirm via the platform that the session is actually PLAYING
        waitForDumpsysContains("dumpsys media_session", "state=PLAYING(3)", 15_000)

        // Simulate swipe-away
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            composeRule.activity.finishAndRemoveTask()
        }

        // After task removal: either the session no longer reports PLAYING for our package,
        // OR our service has stopped. Both are acceptable signals that playback halted.
        val deadline = System.currentTimeMillis() + 15_000
        var lastSession = ""
        var lastService = ""
        while (System.currentTimeMillis() < deadline) {
            lastSession = runShell("dumpsys media_session")
            lastService = runShell("dumpsys activity services com.migsmusic")
            val ourSessionStillPlaying =
                lastSession.lines().any { line ->
                    line.contains("com.migsmusic") && line.contains("state=PLAYING(3)")
                }
            val ourServiceForeground =
                lastService.contains("MediaPlaybackService") &&
                    lastService.contains("isForeground=true")
            if (!ourSessionStillPlaying && !ourServiceForeground) return
            Thread.sleep(300)
        }
        error(
            "Playback did not stop after task removal.\n" +
                "media_session excerpt:\n${lastSession.take(2000)}\n" +
                "activity services excerpt:\n${lastService.take(2000)}",
        )
    }

    private fun waitForDumpsysContains(
        command: String,
        needle: String,
        timeoutMillis: Long,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (runShell(command).contains(needle)) return
            Thread.sleep(300)
        }
        error("Dumpsys never contained '$needle' within ${timeoutMillis}ms")
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
