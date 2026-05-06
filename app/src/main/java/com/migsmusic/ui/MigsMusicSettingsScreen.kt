package com.migsmusic.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.migsmusic.BuildConfig
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
    onGoBack: () -> Unit,
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
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onGoBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

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
        SettingsSectionHeader("Sync")
        SettingsRow(
            title = "Clean up orphan audio files",
            subtitle =
                "Audio files marked as orphaned by a sync (where the song was removed from " +
                    "every synced playlist) are tracked here. Deleting requires a system " +
                    "confirmation dialog — not yet implemented; planned for a future version.",
        ) {
            Button(enabled = false, onClick = { /* TODO: MediaStore.createDeleteRequest flow */ }) {
                Text("Coming soon")
            }
        }

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
