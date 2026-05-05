# Next batch of UI improvements (2026-05-05)

Triaged from a fresh walkthrough of the app. Sorted by my read of bang-for-buck. **None of these are flashy** — they're all functional gaps a normal music-app user would notice.

## Tier 1 — would ship without asking

### 1. Mini-player horizontal swipe to skip
The full player has horizontal swipe → prev/next. The mini-player still requires you to hit a small skip button. Add the same gesture to the mini-player Card. ~15 min, mirrors what's already in PlayerRoute.

### 2. Toast / snackbar on add-to-queue / add-to-playlist
Tap "Play next" or "Play later" or "Add to playlist" → currently silent. The user has to switch to Queue / Playlists tab to verify it worked. A bottom snackbar saying *"Added to queue"* / *"Added to <playlist name>"* with an Undo action would be ~30 min and removes a recurring "did that work?" question.

### 3. Drill-into Album / Artist from a song row's context menu
The song-row "..." menu has Play next / Play later / Add to playlist. Add **Go to album** and **Go to artist** as menu items. Mirrors what we did inside the player (tappable artist/album), but available everywhere a song is shown.

### 4. Sleep timer
Standard music-app feature. Set a duration (15 / 30 / 60 / "end of current song"); after it elapses, pause playback. Reachable from the player's overflow menu. ~1 hour to implement (timer in PlaybackManager + a small dialog UI).

### 5. Player-screen long-press → context menu
Same pattern as song rows. Long-press the player's body → menu with: Save queue as playlist, Add current song to playlist, Go to album, Go to artist, Sleep timer. Consolidates several actions that today either don't exist or are buried.

## Tier 2 — strong UX but more debatable

### 6. Animated equaliser bars next to currently-playing row
Today the current row is highlighted with a tinted background. A tiny 3-bar animated equaliser icon at the leading edge (replacing the album-art thumbnail when the row is the active one) is the universal "this is what's playing right now" affordance. Tasteful, not flashy.

### 7. Recently played / Recently added top sections on Songs tab
The Songs tab is just a flat A→Z list. Apple Music / Spotify / YouTube Music all surface a "Recently Added" rail at the top of a long library view. We'd need to track lastPlayedAt and lastAddedAt — `dateAddedSeconds` is already on SongEntity, so "Recently added" is free; "Recently played" needs a new field.

### 8. Long-press playlist row → rename / duplicate / delete menu
Today playlist rows have inline Rename + Delete buttons. Same problem as song rows had — clutters the list. Consolidate into a "..." overflow menu (or long-press), matching the song-row pattern.

### 9. Sort options for playlists / album list / artist list
We have sort on the Songs tab. Albums/Artists/Playlists default to title A-Z with no override. Consistency win.

### 10. Multi-select on song lists
Tap-and-hold a song → enters multi-select. Tap more → toggles. Bulk add to playlist / play next / play later / remove (where applicable). Bigger change but the missing piece for power users with large libraries.

## Tier 3 — nice but smaller wins

### 11. "Up next" peek on the player screen
A 1-2 row preview of upcoming songs at the bottom of the player so you see what's coming without leaving the player.

### 12. Search clear button moves cursor focus away
When you tap the X to clear search, today the keyboard stays up and cursor is in the field. Auto-defocus would be tidier.

### 13. Folder header showing the path crumbs
When deep in a folder, show `Music / Artist / Album` breadcrumb so you know where you are. Tap any segment to jump there.

### 14. Album-detail and artist-detail screens get a header card
Currently they're a song list with Play / Shuffle buttons. Add a header card with: art + album name + artist + total tracks + total duration + first-added date. Gives the screen identity.

### 15. Per-track skip-forward 10s / skip-back 10s on player
Standard for podcasts; less common for music but useful for long tracks (live recordings, mixes, classical pieces). Two extra buttons on the player.

### 16. Silent-on-headphones-disconnect (already wired via setHandleAudioBecomingNoisy)
This is already implemented. Listed for completeness so we don't accidentally re-suggest it.

### 17. Persist player-route across app restart
If you were on the player screen when you killed the app, reopening should put you back on the player (current behavior: drops you on Songs tab). Small `currentRoute` save in prefs.

## Tier 4 — power-user / niche

### 18. Smart playlists (rules-based, auto-updating)
"Played this week", "Most played", "Never played", "Songs I added this month". Needs play-count tracking. Could be a settings-screen option to enable.

### 19. Crossfade between songs (with X-second overlap)
ExoPlayer supports this natively via `MediaItem.LiveConfiguration`-style settings; would need to bridge into our queue logic. Niche.

### 20. Replay gain / equaliser
Out of scope for v1 unless you actively want it.

### 21. Lyrics
Adds a dependency surface (LRC / Musixmatch API / etc). Out of scope unless explicitly desired.

## My picks if you say "go"

Day-1 batch (~3-4 hours):
- #1 Mini-player swipe to skip
- #2 Toast/snackbar feedback on queue/playlist actions
- #3 Album/Artist links in song-row menu
- #4 Sleep timer

Day-2 batch (~2-3 hours):
- #6 Now-playing equaliser indicator
- #8 Long-press playlist menu (parity)
- #9 Sort consistency

After that we'd be in genuinely-polished territory. Anything below tier 2 is either niche or premature.
