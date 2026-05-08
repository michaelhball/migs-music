# migs music

Android music player for local files. Pairs with [migs music Mac](https://github.com/michaelhball/migs-music-mac) for one-click playlist sync from Apple Music over USB.

Status: pre-release (v0.1.0 in flight). Built for Android 8+ (Oreo); developed and tested on a OnePlus running Android 16.

## What it does

- Plays your local audio library — anything Android's `MediaStore` indexes (MP3, FLAC, M4A, OPUS, etc.).
- Browses by song, album, artist, folder, playlist, and "Loves" (your hearted songs).
- Apple-Music-style segmented queue: History / Current / Up Next / Later / Remaining context. Drag to reorder Up Next; swipe to remove.
- Create / rename / delete / reorder / sort playlists. Restore import order if you change your mind.
- Mac sync: mirror Apple Music playlists from the [companion Mac app](https://github.com/michaelhball/migs-music-mac). Untick a playlist → it disappears from the phone next sync. Optional audio-file cleanup.
- Crossfade between tracks (configurable in Settings).
- Heart any song to add it to the local-only "Loves" virtual playlist — survives every sync.
- Auto-rescans when files appear or change in your music folder.
- Resumes where you left off — queue, position, shuffle, repeat all survive cold starts.
- Background playback with media notification, lockscreen controls (incl. album art), audio-focus handover.
- **No internet permission.** The app physically cannot phone home — no analytics, no crash reporting, no cloud sync.

## Installing

Until the Play Store listing is up:

1. Download the latest `app-release.apk` from [the Releases page](https://github.com/michaelhball/migs-music/releases/latest).
2. On your phone, open the file. Android will warn that "this type of file can harm your device" — that's the standard sideload warning, not specific to this app.
3. Tap **Install anyway**.

If Android asks you to allow installation from your browser (or wherever you downloaded the APK), tap **Settings** → enable **Allow from this source**, then go back and tap Install.

## First launch

1. Tap **Allow** on the "Music & audio" permission prompt — required to read your music library.
2. Tap **Allow** on the notification permission prompt — used for the now-playing notification with playback controls.
3. The app will scan `MediaStore` for your music and you should see your library within a few seconds.

## Permissions, in plain English

| Permission | Why it's there |
| --- | --- |
| Music & audio | Read your local music files. |
| Notifications | Show now-playing controls in the lockscreen and notification shade. |
| Foreground service | Keep playback going when the app is in the background. |
| Wake lock | Keep the CPU awake during playback so audio doesn't glitch. |

That's all. No location, no contacts, no internet, no advertising ID.

## Privacy

[Privacy policy](https://michaelhball.github.io/migs-music/privacy.html) — short version: no telemetry, no analytics, no crash reporting, no cloud. Music stays on your device. Mac sync over USB only when you explicitly run it.

## Troubleshooting

[TROUBLESHOOTING.md](TROUBLESHOOTING.md) — common problems and fixes.

If the app crashes and you're up for helping debug:

```bash
adb logcat -d > migs-music-crash.log
```

Open a [GitHub issue](https://github.com/michaelhball/migs-music/issues) and attach the log. There's no automated crash reporting.

## Developer docs

[CONTRIBUTING.md](CONTRIBUTING.md) — build, test, architecture, conventions.
[RELEASING.md](RELEASING.md) — version bumps, signing, release flow.
