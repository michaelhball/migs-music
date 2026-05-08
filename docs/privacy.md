---
title: Privacy Policy — migs music
---

# Privacy Policy

**App:** migs music (Android) and migs music Mac
**Last updated:** 2026-05-08
**Maintainer:** Michael Ball ([michaelhball on GitHub](https://github.com/michaelhball))

migs music is a personal music player for your own files. It does not collect, transmit, sell, or share any data about you or your usage. This page exists to make that explicit.

## What the Android app does

- **Reads your local audio library** through Android's `MediaStore`, with the `READ_MEDIA_AUDIO` permission you grant on first launch. Files stay on your device — they are not uploaded anywhere.
- **Stores playlists, hearts ("loves"), playback position, and queue state** in a local SQLite database inside the app's private data directory. This data never leaves the device.
- **Plays media** through the standard Android Media3 / ExoPlayer stack. Lock-screen and notification artwork is generated locally from your music files; no album art is fetched online.
- **Receives synced playlists** from the companion Mac app over USB (via ADB), if and only if you explicitly run a sync from the Mac side. No wireless networking is used.

## What the Mac app does

- **Reads your Apple Music library** through Apple's `iTunesLibrary` framework, with the "Media & Apple Music" permission you grant in System Settings → Privacy & Security. The data stays on your Mac.
- **Talks to your phone over USB** using the Android Debug Bridge (`adb`) — only to push playlists and audio files when you click Sync. No phone data is read other than a directory listing of `/sdcard/Music` to deduplicate against what's already there.
- **Optionally checks GitHub for new releases** by making a single HTTPS request to `api.github.com/repos/michaelhball/migs-music-mac/releases/latest`. This sends only the standard HTTP request information that any browser fetch would (your IP address, user-agent). It does not include any identifier specific to you or your library. You can disable update checks by ignoring the in-app update banner.

## What is not collected

- **No analytics.** No Google Analytics, no Firebase Analytics, no telemetry of any kind.
- **No crash reporting.** No Crashlytics, no Sentry, no automated error reporting. If the app crashes and you'd like to help debug it, please send a `adb logcat` output via [GitHub Issues](https://github.com/michaelhball/migs-music/issues) — that's a manual choice, not something the app does on its own.
- **No advertising identifiers.** The app doesn't use the Android Advertising ID or any equivalent.
- **No cloud sync.** Your library, playlists, and hearts live on your device only. There is no cloud backup or "sign in" flow.
- **No third-party SDKs that phone home.** The app's only network call is the optional GitHub release check on the Mac side. Other than that, both apps are fully offline.

## Permissions, in plain English

| Permission | Why it's there |
| --- | --- |
| `READ_MEDIA_AUDIO` (Android 13+) | Read your local music files so we can play them. |
| `READ_EXTERNAL_STORAGE` (Android 12 and below) | Older equivalent of the above. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Keep playback going while the app is in the background. |
| `POST_NOTIFICATIONS` | Show the now-playing notification with playback controls. |
| `WAKE_LOCK` | Keep the CPU awake during playback so audio doesn't glitch. |
| `Media & Apple Music` (macOS) | Read your Apple Music library so we know what's syncable. |

## Children's privacy

The apps are not directed at children under 13 and do not knowingly collect any data from anyone of any age — see the rest of this page.

## Changes

If this policy ever changes, the "Last updated" date above will be bumped and the change will appear in the [git history of this file](https://github.com/michaelhball/migs-music/commits/main/docs/privacy.md).

## Contact

Open an issue at [github.com/michaelhball/migs-music/issues](https://github.com/michaelhball/migs-music/issues) — that's the only support channel.
