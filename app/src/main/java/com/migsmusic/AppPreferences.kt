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

    // Sort applied to a single artist's song list (the Artist Detail screen). Separate from
    // the global songSortOrder so changing one doesn't surprise the other — defaults to
    // ALBUM_ASC because grouping by album is the expected default when looking at one artist.
    var artistDetailSongSortOrder: SongSortOrder
        get() =
            prefs.getString(KEY_ARTIST_DETAIL_SONG_SORT, null)
                ?.let { name -> runCatching { SongSortOrder.valueOf(name) }.getOrNull() }
                ?: SongSortOrder.ALBUM_ASC
        set(value) = prefs.edit { putString(KEY_ARTIST_DETAIL_SONG_SORT, value.name) }

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
     * When true, tapping a song row in the queue asks for confirmation before jumping to
     * it. Mirrors how desktop music apps treat double-click vs single-click — guards
     * against accidental song switches when scrolling/inspecting the queue. Defaults to
     * true because the cost of the occasional extra tap is much smaller than the
     * cost of an accidental skip mid-listen.
     */
    var confirmQueueJump: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_QUEUE_JUMP, true)
        set(value) = prefs.edit { putBoolean(KEY_CONFIRM_QUEUE_JUMP, value) }

    /**
     * Crossfade duration in milliseconds. 0 = disabled (track-to-track is gapless via
     * ExoPlayer but with no overlap). Capped at 12s — anything longer feels weird with
     * average song lengths and gives ExoPlayer too small a non-fade window to deliver
     * an actual playable middle of the track.
     */
    var crossfadeMs: Long
        get() = prefs.getLong(KEY_CROSSFADE_MS, 0L).coerceIn(0L, 12_000L)
        set(value) = prefs.edit { putLong(KEY_CROSSFADE_MS, value.coerceIn(0L, 12_000L)) }

    /**
     * Whether the user was on the full-screen player route the last time we observed nav state.
     * On cold start, if true, we route the user back to the player so they don't have to drill
     * back in. Updated whenever the current route changes.
     */
    var wasOnPlayerRoute: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_ROUTE, false)
        set(value) = prefs.edit { putBoolean(KEY_PLAYER_ROUTE, value) }

    /**
     * Cached list of `.m3u` / `.m3u8` files discovered under the user's Music folder, persisted
     * across cold starts so the Playlists tab can show them instantly while the SAF tree walk
     * runs in the background. Stored as a newline-separated TSV (`uri\tdisplayName` per row);
     * we use TSV instead of JSON to avoid an extra dependency and keep parsing trivial — both
     * URIs and display names are URL-safe enough that a real `\t` or `\n` in either is
     * vanishingly rare. Defensive parsing skips any row that doesn't have exactly two fields.
     */
    var cachedDiscoveredM3uTsv: String
        get() = prefs.getString(KEY_DISCOVERED_M3U_CACHE, null).orEmpty()
        set(value) = prefs.edit { putString(KEY_DISCOVERED_M3U_CACHE, value) }

    private companion object {
        const val KEY_SONG_SORT = "song_sort_order"
        const val KEY_ALBUM_SORT = "album_sort_order"
        const val KEY_ARTIST_SORT = "artist_sort_order"
        const val KEY_ARTIST_DETAIL_SONG_SORT = "artist_detail_song_sort_order"
        const val KEY_PLAYLIST_SORT = "playlist_sort_order"
        const val KEY_SHUFFLE = "shuffle_enabled"
        const val KEY_CONFIRM_QUEUE_JUMP = "confirm_queue_jump"
        const val KEY_CROSSFADE_MS = "crossfade_ms"
        const val KEY_PLAYER_ROUTE = "was_on_player_route"
        const val KEY_DISCOVERED_M3U_CACHE = "discovered_m3u_cache_tsv"
    }
}
