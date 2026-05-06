package com.migsmusic.playlistimport

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One M3U file found under the app's sync directory. [absolutePath] is what we read +
 * delete via direct `java.io.File`. [uri] is a `file://` URI for the (rare) call sites
 * that still want a Uri shape (e.g. the manual-import dialog flow which builds on
 * `ContentResolver.openInputStream`).
 */
data class DiscoveredM3u(
    val uri: Uri,
    val displayName: String,
    val absolutePath: String,
)

/**
 * Hardcoded sync directory: `/sdcard/Android/media/<package>/sync/`. Owned by us, so no
 * SAF picker / no permission grant required. The Mac sync flow pushes M3U files +
 * `.migs-sync-manifest` here via adb. Audio files still go to the conventional
 * `/sdcard/Music/` (where MediaStore indexes them and other music apps expect them).
 *
 * Why not user-pickable: Android 11+ scoped storage refuses to grant SAF access to
 * "media collection" subdirectories under `/sdcard/` — Music, Pictures, Movies — even
 * when the user explicitly chooses one. The picker shows "Can't use this folder. To
 * protect your privacy, choose another folder." So we own a private dir nobody else
 * needs to write to (except adb push during sync, which works against this path).
 */
const val SYNC_DIR_PATH: String = "/sdcard/Android/media/com.migsmusic/sync"

/**
 * Lists every `.m3u` / `.m3u8` file in the app's sync directory. IO-bound — runs on
 * [Dispatchers.IO]. No SAF, no permission, no grant. Empty list if the directory
 * doesn't exist yet (fresh install, no syncs run).
 */
suspend fun scanForM3uFiles(): List<DiscoveredM3u> =
    withContext(Dispatchers.IO) {
        val dir = File(SYNC_DIR_PATH)
        if (!dir.exists() || !dir.canRead()) {
            Log.i("M3uFileScanner", "sync dir absent or unreadable: $SYNC_DIR_PATH")
            return@withContext emptyList()
        }
        val entries = dir.listFiles() ?: return@withContext emptyList()
        val out = mutableListOf<DiscoveredM3u>()
        for (child in entries) {
            if (child.isDirectory) continue
            val name = child.name
            val lower = name.lowercase()
            if (!(lower.endsWith(".m3u") || lower.endsWith(".m3u8"))) continue
            out +=
                DiscoveredM3u(
                    uri = Uri.fromFile(child),
                    displayName = name,
                    absolutePath = child.absolutePath,
                )
        }
        out.sortedBy { it.displayName.lowercase() }
    }
