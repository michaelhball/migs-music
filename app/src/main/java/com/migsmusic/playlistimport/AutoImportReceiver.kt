package com.migsmusic.playlistimport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.migsmusic.MigsMusicApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Triggered by `adb shell am broadcast -a com.migsmusic.AUTO_IMPORT -p com.migsmusic`,
 * sent by the Mac sync app immediately after it finishes pushing a playlist's M3U file.
 * Walks the user's granted Music folder, auto-imports every `.m3u` it finds, and deletes
 * the consumed files — same code path as the Playlists tab's on-entry refresh, just
 * triggered remotely.
 *
 * The receiver is **manifest-declared** so it can wake the app from cold: even if MIGS
 * Music isn't running, `am broadcast` will instantiate [MigsMusicApplication], deliver
 * the intent here, and we run the import. The user opens the app afterwards and sees
 * the playlist already in place.
 *
 * Uses goAsync() because the work is suspending (file IO, Room writes). We have ~10s
 * of process lifetime to complete; in practice import runs in well under 1s for a
 * single-playlist sync.
 */
class AutoImportReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        val app = context.applicationContext as? MigsMusicApplication ?: return
        val treeUriString = app.appContainer.preferences.musicFolderTreeUri
        if (treeUriString.isNullOrBlank()) {
            Log.w(TAG, "Ignoring broadcast: no music folder URI granted yet")
            return
        }
        val treeUri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return

        // goAsync keeps the broadcast lifetime alive across the suspend boundary. finish()
        // must be called when we're done — wrap in try/finally so a thrown exception can't
        // leak the receiver.
        val pending = goAsync()
        // SupervisorJob so a single failure doesn't tear down the whole launch hierarchy if
        // we ever fan out to multiple files. Default dispatcher: import work is mostly IO.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                val summary = app.appContainer.autoImportService.importAllInTree(treeUri)
                if (summary.failures.isNotEmpty()) {
                    Log.w(TAG, "${summary.failures.size} file(s) failed during auto-import:")
                    summary.failures.forEach { (file, reason) ->
                        Log.w(TAG, "  ${file.displayName}: $reason")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Auto-import failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "AutoImportReceiver"
    }
}
