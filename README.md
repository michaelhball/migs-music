# MIGS Music

A simple, lightning-fast Android music player for files you actually own. No accounts, no streaming, no ads — just your music, organised the way it's already organised on your computer.

## What it does

- **Plays your local audio library.** Anything Android's `MediaStore` indexes — MP3s, FLACs, M4As, etc.
- **Browses by song, album, artist, folder, and playlist.** Folders preserve whatever structure you have on disk; albums and artists are derived from track tags.
- **Queue with Play Next / Play Later** — Apple-Music-style segmented queue you can reorder and clear.
- **Playlists.** Create, rename, delete, reorder. The same song can live in many playlists with no duplication.
- **Imports playlists from your Mac's Music app** as M3U files (see below).
- **Auto-rescans** when you drop new files onto the phone — no "refresh library" button to remember.
- **Resumes where you left off.** Queue, position, shuffle/repeat state all survive cold starts.
- **Background playback** with a media notification, lockscreen controls, audio-focus handover when other apps interrupt.
- **No internet permission.** It can't phone home because it can't go online.

## Getting your music on the phone

You're going to use OpenMTP (or any other MTP transfer app) to drag files from your Mac onto the phone:

1. Connect the phone via USB; pick **File transfer** mode on the phone.
2. Open OpenMTP, navigate to the phone's `Music/` folder.
3. Drag the audio files (and folders) you want across. Music.app's existing `~/Music/Music/Media/Music/Artist/Album/` structure transfers cleanly — drop the whole tree.
4. OpenMTP prompts on filename collisions, so re-dragging the same album later just skips files that are already there.

The app's auto-rescan picks up the new files within a couple of seconds. No tap required.

## Importing a playlist from Mac Music.app

The big workflow this app is designed around: **export a playlist as M3U on the Mac, drop it onto the phone, import it.**

### On the Mac

1. Open the **Music** app.
2. Click the playlist in the sidebar.
3. **File → Library → Export Playlist…**
4. In the *Format* dropdown at the bottom of the save dialog, change from "Music Files" to **M3U**.
5. Save it somewhere convenient (Desktop is fine).

⚠️ If the playlist contains streaming-only tracks (Apple Music subscription tracks you've never downloaded as files), they won't be playable on the phone — they'll show up as **unmatched** during import and you'll need to copy the actual MP3s for those tracks too. Tracks you imported as MP3s yourself, or any downloaded purchase, will work.

### On the phone

There are two ways to import:

**Auto-detect (recommended, set up once)**

1. Drag the `.m3u` file plus the corresponding audio files into your phone's `Music/` folder via OpenMTP.
2. Open MIGS Music → **Playlists** tab.
3. The first time only, you'll see a banner asking you to pick your Music folder. Tap **Pick Music folder**, browse to it, allow access. (The grant survives restarts; you'll never be asked again.)
4. Any `.m3u` files anywhere under that folder show up automatically in an **Available to import** section at the top of the Playlists tab.
5. Tap **Import**. A dialog shows how many tracks were matched against your library — if any are unmatched, you'll see them listed (typically because the audio file isn't on the phone yet).
6. Edit the playlist name if you want, tap **Create Playlist**. Done.

**Manual file picker (one-off imports)**

If the M3U lives somewhere outside your Music folder, tap the **⋮** overflow on the Playlists screen → **Import from M3U file**, then pick the file directly via Android's file picker. Same dialog flow.

### How matching works

Each track in the M3U is matched against your on-device library in three passes:

1. Exact case-insensitive `(artist, title)` match.
2. Normalised match — strips qualifiers like *(feat. X)*, *(Remastered)*, smart quotes, accents.
3. Filename basename fallback — when the M3U has no track metadata, the filename minus track-number prefix (`01 - Hello.mp3` → `Hello`) is matched against song titles.

Anything still unmatched is reported in the dialog so you know what's missing. Re-importing later (after copying more files) creates a separate playlist with the now-larger match count — manage duplicates by hand.

## Queue model

The queue is segmented into:

- **History** — songs you've played (or skipped past).
- **Current** — what's playing now.
- **Up Next** — songs you explicitly added via *Play Next*. They play in the order you added them.
- **Later** — songs you added via *Play Later*. Play after Up Next.
- **Remaining context** — the rest of whatever album / playlist / folder you're working through.

You can reorder Up Next via drag handles and clear the entire upcoming section in one tap.

## Status

This is in active development on a OnePlus running Android 14. It works well on the developer's device with ~1800 songs; expect some rough edges with very large libraries (tens of thousands of songs).

What's known to work well: cold-start library scan, playback, queue, playlists (including reorder), session restore, audio focus handover, lockscreen / notification controls, M3U import, swipe-to-dismiss → playback stops cleanly.

What's not yet built (intentionally — see [workspace/UI_NEXT_BATCH.md](workspace/UI_NEXT_BATCH.md) for the queue): mini-player swipe-to-skip, snackbar feedback on add-to-queue actions, sleep timer, multi-select, smart playlists, crossfade, lyrics.

## Contributing

If you want to build, run, test, or hack on the app, see [CONTRIBUTING.md](CONTRIBUTING.md).
