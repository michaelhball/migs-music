package com.migsmusic.playlistimport

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One M3U file found under the user's granted Music folder tree.
 */
data class DiscoveredM3u(
    val uri: Uri,
    val displayName: String,
)

/**
 * Recursively walks the SAF tree at [treeUri] and returns every `.m3u` / `.m3u8` file
 * found, in alphabetical order by display name. IO-bound — runs on [Dispatchers.IO].
 *
 * Returns an empty list if the URI is invalid, permission has been revoked, or the user's
 * tree has nothing matching. The caller can rely on the result being safe to render.
 */
suspend fun scanForM3uFiles(
    context: Context,
    treeUri: Uri,
): List<DiscoveredM3u> =
    withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        if (!root.canRead()) return@withContext emptyList()

        val out = mutableListOf<DiscoveredM3u>()
        walk(root, out)
        out.sortedBy { it.displayName.lowercase() }
    }

private fun walk(
    dir: DocumentFile,
    accumulator: MutableList<DiscoveredM3u>,
) {
    // listFiles() can be slow on huge trees but is cached by SAF for the directory; bounded
    // by the user's Music folder size which is typically small.
    for (child in dir.listFiles()) {
        if (child.isDirectory) {
            walk(child, accumulator)
            continue
        }
        val name = child.name ?: continue
        val lower = name.lowercase()
        if (lower.endsWith(".m3u") || lower.endsWith(".m3u8")) {
            accumulator += DiscoveredM3u(uri = child.uri, displayName = name)
        }
    }
}
