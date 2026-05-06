# Followups

Things we've consciously parked. Each entry includes enough context for whoever picks it up to start without rebuilding the conversation.

## Use stable IDs for songs (not MediaStore _ID)

**Why:** MediaStore `_ID` is volatile under tag re-scans. When MediaScanner re-reads ID3 tags (which we trigger via `MediaScannerConnection.scanFile` for tagless files), it briefly clears the file's `IS_MUSIC` flag and our `IS_MUSIC != 0` query stops returning that row — making the song look "missing" to our scan. Our `pruneMissingSongs` used to delete those rows, and the `CASCADE` foreign key on `playlist_songs.songId` would then wipe playlist contents (a real, observed bug — committed as critical fix `d1d94f5` 2026-05-06).

**Current state:** `pruneMissingSongs` is disabled. Stale rows for genuinely-deleted files now linger in the `songs` table forever, which is a much smaller user-facing issue than playlists losing content. The user's actually-broken case (delete a song, see it lingering in a playlist with no playable file) is a polish issue, not a data-loss one.

**Real fix:** Stop using MediaStore `_ID` as the `SongEntity` primary key. Use a stable identifier we control — content URI hash, absolute file path, or a UUID we generate on first sight. That makes both `playlist_songs.songId` and `playback_snapshot.currentSongId` references survive any MediaStore churn. Then re-enable pruning safely (no cascade, or smarter prune that compares by file path).

**Estimated work:** ~half-day refactor: schema migration v3→v4 with a synthesised stable id, update everything that references `SongEntity.id`, restore pruning.

## Investigate "MIGS Music" appearing twice in the app's top area

**Why:** User reported seeing the title twice. Likely the system status bar reflecting the launcher label PLUS our `TopAppBar { Text("MIGS Music") }` — but worth confirming. Could just be removing the explicit `TopAppBar` title and relying on screen-level headers (Songs / Playlists / etc.) for context.

**Estimated work:** 10 minutes once we look at it on-device.

## A lot of UI/UX still needs a rework

**Why:** User has flagged several rough edges as we've gone. The recent ones (action overcrowding on rows, ⋮ menus, drag-only reorder, header areas) have been fixed locally. But the user has explicitly noted "the whole UI needs a rework at some point" — there's room for a coherent design pass instead of one-off polish.

**Estimated work:** open-ended. Worth scoping when the user has time to think about it.

## Mini-player shouldn't activate from playlist navigation

User-reported: opening a playlist appears to populate the mini-player with a song even when nothing was playing. Probably a side effect somewhere in the playlist-detail route or the auto-import flow that's calling `playContext` it shouldn't be. Needs investigation.

**Estimated work:** 30 min once we can reproduce.

## Mini-player should stop when its source playlist is deleted

If the user is playing from playlist X and deletes X, playback currently continues because the queue references songs by id, not by playlist. Ideally deleting a playlist whose songs are currently in the queue should clear / stop playback.

**Where to fix:** `PlaylistsViewModel.deletePlaylist` could check whether the active queue's `currentSong.songId` is in the playlist's `playlist_songs` rows and call `playbackManager.clearQueue()` if so. Or simpler: on every delete, check if `playbackManager.currentSongId` is in the deleted playlist's songIds and stop if yes.

**Estimated work:** 30 min.
