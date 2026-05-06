# migs music

Android music player for local files. Pairs with [migs music Mac](../migs-music-mac) for one-click playlist sync from Apple Music.

Status: in active development. Not yet on the Play Store.

## What it does

- Plays your local audio library (anything Android's `MediaStore` indexes — MP3, FLAC, M4A, etc.).
- Browses by song, album, artist, folder, playlist.
- Sort within a single artist by album / title / duration / date.
- Apple-Music-style segmented queue: History / Current / Up Next / Later / Remaining context. Reorder Up Next via drag.
- Create / rename / delete / reorder / sort playlists.
- Mirror sync from Mac Music.app (via the Mac app). Untick a playlist → it disappears from the phone next sync. Optional audio-file cleanup.
- Auto-rescans when files appear in the music folder.
- Resumes where you left off — queue, position, shuffle/repeat survive cold starts.
- Background playback with media notification, lockscreen controls, audio-focus handover.
- No internet permission. Cannot phone home.

## Installing

No Play Store listing yet. Build from source — see [CONTRIBUTING.md](CONTRIBUTING.md).

## Permissions

- Music & audio (read library).
- Notifications (lockscreen / shade controls).
- Music folder access via SAF (only used by the playlist sync flow, to read pushed `.m3u` files).

## Troubleshooting

[TROUBLESHOOTING.md](TROUBLESHOOTING.md) — common problems and fixes.

## Developer docs

[CONTRIBUTING.md](CONTRIBUTING.md) — build, test, architecture, conventions.
[RELEASING.md](RELEASING.md) — version bumps, signing, Play Store flow.
