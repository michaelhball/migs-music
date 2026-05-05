package com.migsmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.migsmusic.data.local.model.AlbumSummary
import com.migsmusic.data.local.model.ArtistSummary
import com.migsmusic.data.local.model.FolderSummary
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Composable
internal fun ListRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    titleFontWeight: FontWeight? = null,
    leading: @Composable (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .background(color = containerColor)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = titleFontWeight,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        actions?.invoke()
    }
}

@Composable
internal fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    testTag: String = UiTestTags.EmptyState,
    fillSize: Boolean = true,
) {
    Box(
        modifier = (if (fillSize) modifier.fillMaxSize() else modifier.fillMaxWidth())
            .padding(horizontal = 32.dp, vertical = 64.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
internal fun SmallActionButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    TextButton(modifier = modifier, onClick = onClick, enabled = enabled) {
        Text(label)
    }
}

@Composable
internal fun AlbumArtImage(
    uri: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (uri.isNullOrBlank()) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize(0.5f),
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(uri)
                    .crossfade(false) // animations break Compose-test idle resource
                    .build(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = androidx.compose.ui.graphics.painter.ColorPainter(
                    MaterialTheme.colorScheme.surfaceContainerHighest
                ),
            )
        }
    }
}

@Composable
internal fun SortMenu(
    current: SongSortOrder,
    onSelect: (SongSortOrder) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.testTag(UiTestTags.SortButton),
        ) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
            Spacer(modifier = Modifier.width(4.dp))
            Text(current.label, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SongSortOrder.values().forEach { order ->
                DropdownMenuItem(
                    text = { Text(order.label) },
                    onClick = {
                        onSelect(order)
                        open = false
                    },
                    modifier = Modifier.testTag(UiTestTags.sortOption(order.name)),
                )
            }
        }
    }
}

internal fun FolderSummary.encodedPath(): String =
    URLEncoder.encode(path, StandardCharsets.UTF_8.toString())

internal fun AlbumSummary.encodedKey(): String =
    URLEncoder.encode(key, StandardCharsets.UTF_8.toString())

internal fun ArtistSummary.encodedName(): String =
    URLEncoder.encode(name, StandardCharsets.UTF_8.toString())

internal fun repeatModeLabel(repeatMode: Int): String = when (repeatMode) {
    1 -> "All"
    2 -> "One"
    else -> "Off"
}

internal fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/** "23 songs · 1h 24m" or "5 songs · 18m" — used in playlist + folder + queue headers. */
internal fun formatCountAndDuration(count: Int, totalMs: Long): String {
    val totalSeconds = (totalMs / 1000).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val durationStr = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    val songWord = if (count == 1) "song" else "songs"
    return "$count $songWord · $durationStr"
}
