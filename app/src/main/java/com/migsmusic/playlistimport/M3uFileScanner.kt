package com.migsmusic.playlistimport

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One M3U file found under the user's granted Music folder tree.
 *
 * [uri] is a SAF document URI suitable for read/delete via ContentResolver.
 * [absolutePath] is the underlying filesystem path. We carry both because some
 * device storage providers (notably OnePlus's ExternalStorageProvider) return 0
 * children for `/sdcard/Music` even when the directory is populated — so we
 * scan via direct `java.io.File` and only fall back to SAF for ops that need
 * write access.
 */
data class DiscoveredM3u(
    val uri: Uri,
    val displayName: String,
    val absolutePath: String? = null,
)

/**
 * Walks the granted [treeUri] for `.m3u` / `.m3u8` files. IO-bound — runs on [Dispatchers.IO].
 *
 * Tries SAF's `DocumentFile.listFiles()` first (works on stock Android). If that comes back
 * empty *and* the tree maps to a real filesystem path we can read, falls back to a direct
 * `java.io.File` walk and constructs SAF document URIs for the discovered files. This is
 * what makes the auto-import path survive on OnePlus / OPPO ColorOS where the platform's
 * ExternalStorageProvider returns 0 children for `/sdcard/Music` despite the directory
 * being populated and the persistent URI grant being valid (verified via logcat — see
 * commit message).
 */
suspend fun scanForM3uFiles(
    context: Context,
    treeUri: Uri,
): List<DiscoveredM3u> =
    withContext(Dispatchers.IO) {
        val safRoot = DocumentFile.fromTreeUri(context, treeUri)
        if (safRoot == null || !safRoot.canRead()) {
            Log.w("M3uFileScanner", "SAF root unreadable for $treeUri")
            return@withContext emptyList()
        }

        val viaSaf = mutableListOf<DiscoveredM3u>()
        runCatching { walkSaf(safRoot, viaSaf) }
            .onFailure { Log.w("M3uFileScanner", "SAF walk failed", it) }
        if (viaSaf.isNotEmpty()) {
            return@withContext viaSaf.sortedBy { it.displayName.lowercase() }
        }

        // SAF returned nothing. Fall back to a direct filesystem walk under the path the tree
        // URI points to (typically `/sdcard/Music` for `primary:Music`). For each match we
        // build the SAF document URI from the tree URI + relative path, so downstream delete
        // ops still go through SAF and respect the granted permission.
        val treeDocId = treeDocIdFor(context, treeUri)
        val fsRoot =
            filesystemPathForDocId(treeDocId)?.let(::File)?.takeIf { it.exists() && it.canRead() }
                ?: return@withContext emptyList()
        val viaFs = mutableListOf<DiscoveredM3u>()
        runCatching { walkFs(fsRoot, treeUri, treeDocId, viaFs) }
            .onFailure { Log.w("M3uFileScanner", "filesystem walk failed", it) }
        if (viaFs.isNotEmpty()) {
            Log.i("M3uFileScanner", "fell back to direct fs scan; found ${viaFs.size} m3u file(s)")
        }
        viaFs.sortedBy { it.displayName.lowercase() }
    }

private fun walkSaf(
    dir: DocumentFile,
    accumulator: MutableList<DiscoveredM3u>,
) {
    for (child in dir.listFiles()) {
        if (child.isDirectory) {
            walkSaf(child, accumulator)
            continue
        }
        val name = child.name ?: continue
        val lower = name.lowercase()
        if (lower.endsWith(".m3u") || lower.endsWith(".m3u8")) {
            accumulator += DiscoveredM3u(uri = child.uri, displayName = name)
        }
    }
}

private fun walkFs(
    dir: File,
    treeUri: Uri,
    treeDocId: String,
    accumulator: MutableList<DiscoveredM3u>,
) {
    val entries = dir.listFiles() ?: return
    for (child in entries) {
        if (child.isDirectory) {
            walkFs(child, treeUri, treeDocId, accumulator)
            continue
        }
        val name = child.name
        val lower = name.lowercase()
        if (!(lower.endsWith(".m3u") || lower.endsWith(".m3u8"))) continue
        // SAF doc id for a file under a `primary:Music` tree looks like `primary:Music/doot.m3u`.
        // We replicate that mapping for arbitrary nesting by joining the tree's doc id with
        // the path relative to the tree's filesystem root.
        val rootPath = filesystemPathForDocId(treeDocId) ?: continue
        val relative = child.absolutePath.removePrefix(rootPath).trimStart('/')
        val docId = if (relative.isEmpty()) treeDocId else "$treeDocId/$relative"
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        accumulator +=
            DiscoveredM3u(
                uri = docUri,
                displayName = name,
                absolutePath = child.absolutePath,
            )
    }
}

private fun treeDocIdFor(
    context: Context,
    treeUri: Uri,
): String =
    if (DocumentsContract.isDocumentUri(context, treeUri)) {
        DocumentsContract.getDocumentId(treeUri)
    } else {
        DocumentsContract.getTreeDocumentId(treeUri)
    }

/**
 * Maps a SAF tree document id to its underlying filesystem path, when one exists. Currently
 * only handles `primary:` (the device's primary storage). Removable SD cards would have a
 * UUID-prefixed doc id and need volume-specific lookup — out of scope for our single-folder
 * Music sync use case.
 */
private fun filesystemPathForDocId(docId: String): String? {
    if (!docId.startsWith("primary:")) return null
    val sub = docId.removePrefix("primary:")
    val base = "/storage/emulated/0"
    return if (sub.isEmpty()) base else "$base/$sub"
}
