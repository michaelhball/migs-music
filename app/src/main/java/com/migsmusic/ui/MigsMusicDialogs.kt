package com.migsmusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.migsmusic.data.local.model.PlaylistSummary

/**
 * Opens an "Add to playlist" picker for any songId. Returns a stable opener; the dialog itself
 * renders inline whenever a pending songId is set. Replaces the duplicated open/close +
 * AddToPlaylistDialog block that used to live at every screen with a `playlistsViewModel`.
 *
 * The opener is a no-op when [playlistsViewModel] is null, so screens that don't require
 * playlist support (the standalone folder browser) can still wire up `onAddToPlaylist` without
 * a null check at every call site.
 */
@Composable
internal fun rememberAddToPlaylistTrigger(playlistsViewModel: PlaylistsViewModel?): (Long) -> Unit {
    if (playlistsViewModel == null) return remember { { _: Long -> } }
    var pendingSongId by remember { mutableLongStateOf(-1L) }
    if (pendingSongId != -1L) {
        val playlists by playlistsViewModel.playlists.collectAsStateWithLifecycle()
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { pendingSongId = -1L },
            onCreatePlaylist = { name ->
                playlistsViewModel.createPlaylistAndAddSong(name, pendingSongId)
                pendingSongId = -1L
            },
            onAddToPlaylist = { playlistId ->
                playlistsViewModel.addSong(playlistId, pendingSongId)
                pendingSongId = -1L
            },
        )
    }
    return { songId -> pendingSongId = songId }
}

@Composable
internal fun AddToPlaylistDialog(
    playlists: List<PlaylistSummary>,
    onDismiss: () -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onAddToPlaylist: (Long) -> Unit,
) {
    var createMode by remember { mutableStateOf(false) }

    if (createMode) {
        NameDialog(
            title = "New playlist",
            onDismiss = onDismiss,
            onConfirm = {
                onCreatePlaylist(it)
                createMode = false
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(UiTestTags.AddToPlaylistDialog),
        title = { Text("Add to playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { createMode = true },
                    modifier = Modifier.testTag(UiTestTags.AddToPlaylistCreate),
                ) {
                    Text("Create playlist")
                }
                playlists.forEach { playlist ->
                    TextButton(
                        onClick = { onAddToPlaylist(playlist.id) },
                        modifier = Modifier.testTag(UiTestTags.AddToPlaylistRow),
                    ) {
                        Text(playlist.name)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(UiTestTags.AddToPlaylistClose),
            ) {
                Text("Close")
            }
        },
    )
}

@Composable
internal fun NameDialog(
    title: String,
    initialValue: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) onConfirm(value) }),
                modifier = Modifier.testTag(UiTestTags.DialogTextField),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.isNotBlank(),
                modifier = Modifier.testTag(UiTestTags.DialogConfirm),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(UiTestTags.DialogCancel),
            ) {
                Text("Cancel")
            }
        },
    )
}
