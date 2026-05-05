package com.migsmusic

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.migsmusic.ui.AlbumSortOrder
import com.migsmusic.ui.ArtistSortOrder
import com.migsmusic.ui.PlaylistSortOrder
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

    var albumSortOrder: AlbumSortOrder
        get() =
            prefs.getString(KEY_ALBUM_SORT, null)
                ?.let { name -> runCatching { AlbumSortOrder.valueOf(name) }.getOrNull() }
                ?: AlbumSortOrder.TITLE_ASC
        set(value) = prefs.edit { putString(KEY_ALBUM_SORT, value.name) }

    var artistSortOrder: ArtistSortOrder
        get() =
            prefs.getString(KEY_ARTIST_SORT, null)
                ?.let { name -> runCatching { ArtistSortOrder.valueOf(name) }.getOrNull() }
                ?: ArtistSortOrder.NAME_ASC
        set(value) = prefs.edit { putString(KEY_ARTIST_SORT, value.name) }

    var playlistSortOrder: PlaylistSortOrder
        get() =
            prefs.getString(KEY_PLAYLIST_SORT, null)
                ?.let { name -> runCatching { PlaylistSortOrder.valueOf(name) }.getOrNull() }
                ?: PlaylistSortOrder.NAME_ASC
        set(value) = prefs.edit { putString(KEY_PLAYLIST_SORT, value.name) }

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

    /**
     * Whether the user was on the full-screen player route the last time we observed nav state.
     * On cold start, if true, we route the user back to the player so they don't have to drill
     * back in. Updated whenever the current route changes.
     */
    var wasOnPlayerRoute: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_ROUTE, false)
        set(value) = prefs.edit { putBoolean(KEY_PLAYER_ROUTE, value) }

    private companion object {
        const val KEY_SONG_SORT = "song_sort_order"
        const val KEY_ALBUM_SORT = "album_sort_order"
        const val KEY_ARTIST_SORT = "artist_sort_order"
        const val KEY_PLAYLIST_SORT = "playlist_sort_order"
        const val KEY_SHUFFLE = "shuffle_enabled"
        const val KEY_MUSIC_FOLDER_URI = "music_folder_tree_uri"
        const val KEY_PLAYER_ROUTE = "was_on_player_route"
    }
}
