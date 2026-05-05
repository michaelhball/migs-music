package com.migsmusic

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
 * Verifies that ExoPlayer's audio-focus integration works in both directions:
 * - When another app takes audio focus, our playback pauses.
 * - When focus returns to us, playback resumes.
 *
 * Uses the system AudioManager from the test's context to simulate another app
 * requesting focus — the platform-level focus arbitration is real, so this tests
 * the end-to-end behavior, not just our config.
 */
@RunWith(AndroidJUnit4::class)
class AudioFocusTest {
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = mediaPermissionRule()

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetSingletonState() {
        resetPlaybackForTest()
    }

    @Test
    fun otherAppTakingFocusPausesOurPlayback() {
        composeRule.waitForLibraryReady()
        if (composeRule.hasNode(UiTestTags.PermissionButton)) return
        composeRule.waitForLibraryScanSettled(minSongs = 1)

        // Start playback and confirm playing (label says "Pause")
        composeRule.onAllNodesWithTag(UiTestTags.SongRow)[0].performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) { composeRule.hasNode(UiTestTags.MiniPlayer) }
        composeRule.onNodeWithTag(UiTestTags.MiniPlayer).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.hasNode(UiTestTags.PlayerScreen) }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.firstTextUnder(UiTestTags.PlayerPlayPause) == "Pause"
        }

        // Steal audio focus from the test process — simulates another media app starting.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val request =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener { /* no-op */ }
                .build()
        val result = audioManager.requestAudioFocus(request)
        require(result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "Focus request not granted (got $result); cannot validate focus behavior"
        }

        try {
            // Our playback should pause within a couple of seconds.
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.firstTextUnder(UiTestTags.PlayerPlayPause) == "Play"
            }
            // And the platform-level state should reflect not-playing.
            waitForDumpsysAbsent("state=PLAYING(3)", 5_000)
        } finally {
            audioManager.abandonAudioFocusRequest(request)
        }
    }

    private fun waitForDumpsysAbsent(
        needle: String,
        timeoutMillis: Long,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var lastState: String? = null
        var lastDump = ""
        while (System.currentTimeMillis() < deadline) {
            lastDump = runShell("dumpsys media_session")
            lastState = ourSessionState(lastDump)
            // null = our session not in dump → counts as "absent"
            // non-null and missing needle → state changed → return
            if (lastState == null || !lastState.contains(needle)) return
            Thread.sleep(300)
        }
        error(
            "MediaSession state for com.migsmusic never lost '$needle'.\n" +
                "Last state: $lastState\nDump excerpt:\n${lastDump.take(3000)}",
        )
    }

    /**
     * dumpsys media_session contains many sessions on a typical device. Find the line that
     * holds OUR session's PlaybackState specifically, by anchoring on `package=com.migsmusic`
     * and scanning forward until the next session block or end of input.
     */
    private fun ourSessionState(dump: String): String? {
        val lines = dump.lines()
        for (i in lines.indices) {
            if (!lines[i].contains("package=com.migsmusic")) continue
            for (j in (i + 1)..(i + 50).coerceAtMost(lines.lastIndex)) {
                if (lines[j].contains("package=")) break // entered a new session block
                if (lines[j].contains("state=PlaybackState{state=")) {
                    return lines[j].trim()
                }
            }
        }
        return null
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
