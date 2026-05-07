package com.migsmusic.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks audio files that the sync flow has marked for deletion. The actual file lives
 * under /sdcard/Music and can only be deleted via [android.provider.MediaStore.createDeleteRequest],
 * which must run from a foreground Activity (background contexts can't show the system
 * confirm dialog). So when [com.migsmusic.playlistimport.AutoImportService] detects a song
 * is no longer referenced by any playlist (synced or manual), we drop its SongEntity row
 * AND record its content URI here. The Settings screen surfaces a "Clean up N orphan
 * files" action that builds the URI list and fires the system delete dialog.
 *
 * Persisted via SharedPreferences (StringSet of contentUri strings). No DB migration needed.
 */
class OrphanAudioTracker(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val _count = MutableStateFlow(currentCount())
    val count: StateFlow<Int> = _count.asStateFlow()

    fun add(contentUris: Collection<String>) {
        if (contentUris.isEmpty()) return
        val current = prefs.getStringSet(KEY, emptySet()) ?: emptySet()
        prefs.edit { putStringSet(KEY, current + contentUris) }
        _count.value = currentCount()
    }

    /** Snapshot of the current orphan URIs as Android Uri objects. Malformed entries are skipped. */
    fun all(): List<Uri> {
        val set = prefs.getStringSet(KEY, emptySet()) ?: emptySet()
        return set.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
    }

    fun clear() {
        prefs.edit { remove(KEY) }
        _count.value = 0
    }

    private fun currentCount(): Int = prefs.getStringSet(KEY, emptySet())?.size ?: 0

    private companion object {
        const val PREF_NAME = "migs-music-orphan-audio"
        const val KEY = "orphan_content_uris"
    }
}
