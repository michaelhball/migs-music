package com.migsmusic

import android.Manifest
import android.os.Build
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.migsmusic.ui.UiTestTags
import kotlinx.coroutines.runBlocking

internal fun mediaPermissionRule(): GrantPermissionRule = GrantPermissionRule.grant(
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
)

/**
 * Wipes the singleton PlaybackManager's state — queue, player, audio focus, caches, persisted
 * snapshot. Call this from `@Before` in tests that interact with playback so they don't
 * inherit stale state from whichever class ran before. The PlaybackManager outlives any single
 * `@Test` (it's on `MigsMusicApplication.appContainer`); without an explicit reset, tests
 * have to defensively account for whatever the previous test left behind, which is the
 * dominant source of cross-test flakiness.
 */
internal fun resetPlaybackForTest() {
    val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        as MigsMusicApplication
    runBlocking {
        app.appContainer.playbackManager.resetForTest()
    }
}

internal fun SemanticsNodeInteractionsProvider.hasNode(tag: String): Boolean =
    onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

internal fun SemanticsNodeInteractionsProvider.nodeCount(tag: String): Int =
    onAllNodesWithTag(tag).fetchSemanticsNodes().size

internal fun collectTexts(node: SemanticsNode): List<String> {
    val out = mutableListOf<String>()
    node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
    node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { out += it }
    for (child in node.children) {
        out.addAll(collectTexts(child))
    }
    return out
}

internal fun SemanticsNodeInteractionsProvider.titleOfRow(tag: String, index: Int): String? {
    val nodes = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
    if (index !in nodes.indices) return null
    return collectTexts(nodes[index]).firstOrNull { it.isNotBlank() }
}

internal fun SemanticsNodeInteractionsProvider.firstTextUnder(tag: String): String? {
    val nodes = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
    return nodes.firstOrNull()?.let { collectTexts(it).firstOrNull { txt -> txt.isNotBlank() } }
}

internal fun <A : androidx.activity.ComponentActivity> AndroidComposeTestRule<ActivityScenarioRule<A>, A>.waitForLibraryReady(
    timeoutMillis: Long = 30_000L
) {
    waitUntil(timeoutMillis = timeoutMillis) {
        hasNode(UiTestTags.PermissionButton) ||
            hasNode(UiTestTags.SongRow)
    }
}

/**
 * Waits until the library is in a settled state suitable for tests:
 * - if a scan has just run, the ScanStatus shows "Indexed N songs"
 * - if the DB was already populated and no scan ran this session, the ScanStatus shows
 *   "No scan yet" but rows are renderable
 * - if scanning is in progress, wait for it to finish
 *
 * Returns once `>= minSongs` rows are rendered AND no scan is in flight.
 */
internal fun <A : androidx.activity.ComponentActivity> AndroidComposeTestRule<ActivityScenarioRule<A>, A>.waitForLibraryScanSettled(
    minSongs: Int = 5,
    timeoutMillis: Long = 90_000L,
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        val statusNodes = onAllNodesWithTag(UiTestTags.ScanStatus, useUnmergedTree = true)
            .fetchSemanticsNodes()
        val statusText = statusNodes.flatMap { collectTexts(it) }.firstOrNull().orEmpty()
        val isScanning = statusText.contains("Scanning")
        val hasIndexed = statusText.startsWith("Indexed")
        val rowCount = onAllNodesWithTag(UiTestTags.SongRow).fetchSemanticsNodes().size
        // Settled = enough rows visible AND not currently scanning.
        if (rowCount >= minSongs && !isScanning) return
        // If scan succeeded with the right count, also OK.
        if (hasIndexed) {
            val indexed = statusText.removePrefix("Indexed ").substringBefore(" ").toIntOrNull() ?: 0
            if (indexed >= minSongs) return
        }
        Thread.sleep(250)
    }
    val finalRowCount = onAllNodesWithTag(UiTestTags.SongRow).fetchSemanticsNodes().size
    val statusNodes = onAllNodesWithTag(UiTestTags.ScanStatus, useUnmergedTree = true)
        .fetchSemanticsNodes()
    val statusText = statusNodes.flatMap { collectTexts(it) }.firstOrNull()
    error("Library scan did not settle: rows=$finalRowCount status='$statusText' after ${timeoutMillis}ms")
}
