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

@RunWith(AndroidJUnit4::class)
class MediaSessionTest {
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = mediaPermissionRule()

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetSingletonState() {
        resetPlaybackForTest()
    }

    @Test
    fun mediaSessionRegistersWhenPlaybackStarts() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(UiTestTags.SongRow).fetchSemanticsNodes().size >= 1
        }

        composeRule.onAllNodesWithTag(UiTestTags.SongRow)[0].performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) { composeRule.hasNode(UiTestTags.MiniPlayer) }

        // Give MediaSession + foreground service time to register with the platform.
        var dump: String = ""
        var ok = false
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            dump = runShell("dumpsys media_session")
            if (dump.contains("com.migsmusic")) {
                ok = true
                break
            }
            Thread.sleep(500)
        }
        check(ok) {
            "media_session dumpsys never mentioned com.migsmusic. First 4kB of dump:\n${dump.take(4000)}"
        }

        // Look for a per-session block. Media3 announces itself as the package's MediaSessionService.
        check(
            dump.contains("package=com.migsmusic") ||
                dump.contains("com.migsmusic/") ||
                dump.contains("MediaPlaybackService"),
        ) {
            "MediaSession dump does not show our service. Dump excerpt:\n${dump.take(4000)}"
        }
    }

    @Test
    fun foregroundServiceIsRunningDuringPlayback() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(UiTestTags.SongRow).fetchSemanticsNodes().size >= 1
        }

        composeRule.onAllNodesWithTag(UiTestTags.SongRow)[0].performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) { composeRule.hasNode(UiTestTags.MiniPlayer) }

        var ok = false
        var dump = ""
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            dump = runShell("dumpsys activity services com.migsmusic")
            if (dump.contains("MediaPlaybackService") && dump.contains("isForeground=true")) {
                ok = true
                break
            }
            Thread.sleep(500)
        }
        check(ok) {
            "MediaPlaybackService never appeared as foreground. Dump excerpt:\n${dump.take(4000)}"
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
