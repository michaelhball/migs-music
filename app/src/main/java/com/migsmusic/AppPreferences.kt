package com.migsmusic

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.migsmusic.ui.SongSortOrder

/**
 * Tiny wrapper around SharedPreferences for user-visible settings that should survive
 * cold starts: sort choice, shuffle state.
 */
class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("migs-music-prefs", Context.MODE_PRIVATE)

    var songSortOrder: SongSortOrder
        get() =
            prefs.getString(KEY_SONG_SORT, null)
                ?.let { name -> runCatching { SongSortOrder.valueOf(name) }.getOrNull() }
                ?: SongSortOrder.TITLE_ASC
        set(value) = prefs.edit { putString(KEY_SONG_SORT, value.name) }

    var shuffleEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHUFFLE, false)
        set(value) = prefs.edit { putBoolean(KEY_SHUFFLE, value) }

    /**
     * SAF tree URI for the user's Music folder. Granted via [ActivityResultContracts.OpenDocumentTree]
     * the first time the user wants to auto-detect M3U playlist files. Persisted across launches
     * so the user only picks once. `null` = not yet granted (or revoked).
     */
    var musicFolderTreeUri: String?
        get() = prefs.getString(KEY_MUSIC_FOLDER_URI, null)
        set(value) = prefs.edit { putString(KEY_MUSIC_FOLDER_URI, value) }

    private companion object {
        const val KEY_SONG_SORT = "song_sort_order"
        const val KEY_SHUFFLE = "shuffle_enabled"
        const val KEY_MUSIC_FOLDER_URI = "music_folder_tree_uri"
    }
}
