# MIGS Music — UX gap review (2026-05-04)

All tests green: 25/25 in 57s. With the engineering foundation solid, here's where the app falls short of standard music-app UX. Sorted by impact.

## Tier 1 — clearly missing, would surprise a normal user

### 1. No album art anywhere
Currently the entire app is text-only. No art on:
- Player screen (the big square in the middle of the screen — the most prominent visual in any music app)
- Mini-player thumbnail (small square at the left of the bottom bar)
- Album list rows
- Notification / lockscreen (we set `setArtworkUri` on MediaItem but the system reports `artworkBitmap is null` — needs investigation)

We already store `albumArtUri` per song (the `MediaStore.Audio.Albums` URI for the cached embedded art). Would need a Coil dependency to actually display them in Compose.

### 2. Mini-player has no progress indicator
There's no visible playback position when the mini-player is shown. Standard pattern is a thin line at the top of the mini-player card that fills as the song progresses. Without it, you can't tell how far through a song you are without expanding the player.

### 3. Reorder by Up/Down buttons instead of drag
Both queue and playlist-detail use ↑/↓ text buttons. Modern apps use long-press + drag. The Up/Down approach feels like a 2010 app.

### 4. No swipe-to-remove on queue / playlist rows
Standard pattern: swipe a queue/playlist row left to remove. We have a trash icon button instead, which is functional but slow.

### 5. Folder browser is flat, not hierarchical
You wanted this on day one (`Music/Artist/Album/track.mp3`). Right now Folders shows a flat list of leaf folders. Drilling in via Artist subfolders and then Album subfolders is missing. Means folder browsing today is basically the same as Albums view.

## Tier 2 — common UX expectations, less critical

### 6. No shuffle toggle inside the player
Shuffle is only available at the very top of the Songs tab ("Shuffle All"). When you're already playing something and want to shuffle the rest of the queue, there's no in-player control. Apple Music puts shuffle next to repeat in the player.

### 7. Mini-player horizontal swipe to skip
Lots of users skip songs by swiping the mini-player left/right. Currently you have to tap the small skip buttons.

### 8. No sort options on the songs list
List is title A-Z. No way to sort by artist, date added, duration, or play count. Apple Music's "Recently Added" view is one of the most-used surfaces in the app — we don't have anything like it.

### 9. No multi-select in playlists / queue
To remove 5 songs from a playlist, you tap remove 5 times. Multi-select + bulk remove is a common shortcut.

### 10. Tapping a song row in Albums / Artists detail goes straight to play
There's no way to "view this album" without playing it. (Actually we *do* have separate AlbumDetailRoute / ArtistDetailRoute — but the Songs row tap action everywhere starts playback. That's one click; some apps separate "open detail" from "play.")

## Tier 3 — nice-to-haves, lower priority

### 11. No persistent "last scan" status
After a successful scan, the "Indexed N songs" status text disappears as soon as you create a new Activity (status is per-VM, not persistent). Means even though the library is loaded, the UI shows "No scan yet" — confusing. Easy fix: persist `lastScanCount` to shared prefs and read on init.

### 12. No settings screen
No way to control: scan-on-launch behavior, theme (dark/light/system), playback gap removal, default sort order, etc.

### 13. Mini-player tap zone is small
Current mini-player is short. Tapping near the title works but the area is small. Common pattern: the entire bar is tappable, with side-icons being just visual hints.

### 14. No empty-states / first-run guidance
When you launch with no music on the device, the Songs tab shows an empty list with no message. Same for empty playlists, empty queue. A simple "No music found — tap Rescan after copying songs to your phone" placeholder would help.

## Already solid

For balance — these standard features are properly wired up:
- ✅ Lockscreen + notification + Bluetooth/headset transport controls (Media3 MediaSession)
- ✅ Audio focus management — we pause when other apps play, others pause when we play
- ✅ Pause-on-swipe-away — your latest request, just shipped
- ✅ Apple-Music-style segmented queue (Play Next / Play Later before natural)
- ✅ Background playback with foreground notification
- ✅ Resume queue across cold start
- ✅ Skip past corrupted files instead of getting stuck
- ✅ Swipe down on player to dismiss — your latest request, just shipped

## Recommendation

For a functional v1 that "works as expected," I'd ship Tier 1 (#1–5). They're all on the critical path of "this is what people expect a music app to look and feel like." Tier 2 is icing.

Approximate effort:
- Album art everywhere: ~half a day (add Coil, plumb through composables, debug the system notification artwork). Biggest visual lift; biggest payoff.
- Mini-player progress: ~1 hour
- Drag-to-reorder: ~2-3 hours (use a community library or write a custom Modifier; needs careful state handling to not desync with the engine)
- Swipe-to-remove: ~1 hour
- Hierarchical folders: ~2 hours of routing + scan changes

Want me to pick these off in that order?
