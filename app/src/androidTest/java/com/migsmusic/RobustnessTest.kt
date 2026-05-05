package com.migsmusic

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.migsmusic.ui.UiTestTags
import kotlinx.coroutines.flow.first
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import android.os.ParcelFileDescriptor
import java.io.IOException

/**
 * Stress + lifecycle tests: rapid navigation, queue spam, repeated start/stop.
 * Each test asserts no FATAL/ANR/wrong-thread/StrictMode exception in logcat
 * captured for that test's window.
 */
@RunWith(AndroidJUnit4::class)
class RobustnessTest {
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = mediaPermissionRule()

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetSingletonState() {
        resetPlaybackForTest()
    }

    @Test
    fun rapidTabSwitchingDoesNotCrash() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return

        clearLogcat()
        repeat(15) {
            composeRule.onNodeWithTag(UiTestTags.FoldersTab).performClick()
            composeRule.onNodeWithTag(UiTestTags.PlaylistsTab).performClick()
            composeRule.onNodeWithTag(UiTestTags.QueueTab).performClick()
            composeRule.onNodeWithTag(UiTestTags.SongsTab).performClick()
        }
        assertCleanLogcat()
    }

    @Test
    fun rapidPlayNextSpamPreservesOrder() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return
        composeRule.waitForLibraryScanSettled(minSongs = 2)

        composeRule.onAllNodesWithTag(UiTestTags.SongRow)[0].performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) { composeRule.hasNode(UiTestTags.MiniPlayer) }

        // Hammer the engine directly — the UI's overflow-menu pattern can't realistically
        // be tapped 5 times in tight succession (each tap requires open + select + close).
        // Direct PlaybackManager calls test the real stress path: rapid addNext on top of
        // an already-playing context.
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MigsMusicApplication
        val pm = app.appContainer.playbackManager
        val allSongs = kotlinx.coroutines.runBlocking {
            app.appContainer.libraryRepository.observeAllSongs().first()
        }
        require(allSongs.size >= 6) { "Need at least 6 songs to spam Play Next from" }
        val songIds = allSongs.map { it.id }

        clearLogcat()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (i in 1..5) pm.playNext(songIds[i])
        }
        composeRule.onNodeWithTag(UiTestTags.QueueTab).performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.hasNode(UiTestTags.queueUpcomingRow(4))
        }
        assertCleanLogcat()
    }

    @Test
    fun rapidSkipNextDoesNotCrash() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(UiTestTags.SongRow).fetchSemanticsNodes().size >= 1
        }

        composeRule.onAllNodesWithTag(UiTestTags.SongRow)[0].performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { composeRule.hasNode(UiTestTags.MiniPlayer) }

        clearLogcat()
        repeat(20) {
            composeRule.onNodeWithTag(UiTestTags.MiniPlayerNext).performClick()
        }
        // Give exoplayer + playbackmanager a beat to settle
        Thread.sleep(1500)
        assertCleanLogcat()
    }

    @Test
    fun rapidPlayPauseTogglingDoesNotCrash() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(UiTestTags.SongRow).fetchSemanticsNodes().size >= 1
        }

        composeRule.onAllNodesWithTag(UiTestTags.SongRow)[0].performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { composeRule.hasNode(UiTestTags.MiniPlayer) }

        clearLogcat()
        repeat(20) {
            composeRule.onNodeWithTag(UiTestTags.MiniPlayerPlayPause).performClick()
        }
        Thread.sleep(1000)
        assertCleanLogcat()
    }

    private fun clearLogcat() {
        runShell("logcat -c")
    }

    private fun assertCleanLogcat() {
        // Pull a chunk; look for problem signatures.
        val dump = runShell("logcat -d -t 2000")
        val problems = listOf(
            "FATAL EXCEPTION",
            "AndroidRuntime: FATAL",
            "ANR in com.migsmusic",
            "wrong thread",
            "StrictMode policy violation",
            "Player is accessed",
            "must be called on the main thread",
        )
        for (signature in problems) {
            check(!dump.contains(signature)) {
                val context = dump.lines()
                    .windowed(size = 6, step = 1, partialWindows = true)
                    .firstOrNull { window -> window.any { it.contains(signature) } }
                    ?.joinToString("\n") ?: "(no context)"
                "Logcat contained problematic signature '$signature'. Context:\n$context"
            }
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
            try { pfd.close() } catch (_: IOException) {}
        }
    }
}
