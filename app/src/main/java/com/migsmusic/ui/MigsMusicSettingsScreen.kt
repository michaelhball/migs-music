package com.migsmusic.ui

import android.app.Activity
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.migsmusic.BuildConfig
import com.migsmusic.data.OrphanAudioTracker
import com.migsmusic.data.repository.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Settings / housekeeping screen. Currently a sketch — covers the simple cases (rescan,
 * about) and reserves space for the bigger items that need lifecycle-foreground confirms
 * (orphan-audio cleanup via `MediaStore.createDeleteRequest`, planned).
 *
 * Reachable via the gear icon on the Songs tab top-right. Single back button to return —
 * doesn't add a Settings tab to the bottom nav (would crowd it).
 */
@Composable
internal fun SettingsRoute(
    libraryRepository: LibraryRepository,
    playerViewModel: PlayerViewModel,
    orphanAudioTracker: OrphanAudioTracker,
) {
    var lastRescanCount by remember { mutableStateOf<Int?>(null) }
    var rescanning by remember { mutableStateOf(false) }
    // Application-scoped, not viewModelScope, so a long rescan completes even if the user
    // navigates away. Cancellable would also be reasonable; not bothering for a stub.
    val rescanScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        )

        SettingsSectionHeader("Library")
        SettingsRow(
            title = "Rescan music files",
            subtitle =
                when {
                    rescanning -> "Scanning…"
                    lastRescanCount != null -> "Found $lastRescanCount songs."
                    else -> "Re-read MediaStore. Useful if files added by other apps haven't appeared."
                },
        ) {
            Button(
                enabled = !rescanning,
                onClick = {
                    rescanning = true
                    rescanScope.launch {
                        val count = runCatching { libraryRepository.scanDevice() }.getOrNull()
                        rescanning = false
                        if (count != null) lastRescanCount = count
                    }
                },
            ) { Text(if (rescanning) "Scanning…" else "Rescan") }
        }

        HorizontalDivider()
        SettingsSectionHeader("Playback")
        val confirmQueueJump by playerViewModel.confirmQueueJump.collectAsState()
        SettingsRow(
            title = "Confirm before jumping in queue",
            subtitle =
                "Tapping a song in the queue asks for confirmation before switching to " +
                    "it. Off = single tap jumps immediately.",
        ) {
            Switch(
                checked = confirmQueueJump,
                onCheckedChange = { playerViewModel.setConfirmQueueJump(it) },
            )
        }

        HorizontalDivider()
        SettingsSectionHeader("Sync")
        OrphanAudioCleanupRow(orphanAudioTracker = orphanAudioTracker)

        HorizontalDivider()
        SettingsSectionHeader("About")
        SettingsRow(
            title = "Version",
            subtitle = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
        )
        SettingsRow(
            title = "Source code",
            subtitle = "github.com/michaelhball/migs-music",
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * Clean-up row for files that the sync flow marked as orphaned. The actual deletion goes
 * through `MediaStore.createDeleteRequest` — the only legal way to delete files we don't
 * own (anything in /sdcard/Music). The system shows a confirm dialog; on user approval the
 * files are gone and we clear the tracker. On cancel/error we leave the tracker alone so a
 * future tap retries.
 *
 * createDeleteRequest is API 30+. minSdk on this app is 26, so we degrade gracefully — pre-30
 * users see a "not supported" subtitle and a disabled button. In practice no one running
 * Android 8/9/10 is hitting this code path because the sync feature itself requires app-owned
 * media-dir writes which post-10 Androids handle differently.
 */
@Composable
private fun OrphanAudioCleanupRow(orphanAudioTracker: OrphanAudioTracker) {
    val orphanCount by orphanAudioTracker.count.collectAsState()
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            // RESULT_OK = user approved, the system already removed the files. Cancel/back =
            // tracker stays so the user can retry. Other result codes are also a "no" — no
            // partial cleanup of the tracker either way.
            if (result.resultCode == Activity.RESULT_OK) {
                orphanAudioTracker.clear()
            }
        }
    SettingsRow(
        title = "Clean up orphan audio files",
        subtitle =
            when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R ->
                    "Requires Android 11 or later (system delete dialog wasn't available before)."
                orphanCount == 0 ->
                    "Nothing to clean up. Files appear here when a sync removes the last " +
                        "playlist that referenced them."
                else ->
                    "$orphanCount file${if (orphanCount == 1) "" else "s"} waiting to be removed " +
                        "from your phone. Tap Clean up to confirm via the system dialog."
            },
    ) {
        Button(
            enabled =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && orphanCount > 0,
            onClick = onClick@{
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@onClick
                val uris = orphanAudioTracker.all()
                if (uris.isEmpty()) return@onClick
                val request =
                    MediaStore.createDeleteRequest(context.contentResolver, uris)
                launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            },
        ) { Text("Clean up") }
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trailing != null) trailing()
    }
}
