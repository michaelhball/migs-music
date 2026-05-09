---
title: Play Store listing copy — migs music
---

# Play Store listing — migs music

Reference document for the Play Console "Main store listing" and "Release notes" fields. Edit and reuse on each release; the source of truth is the Play Console itself once the listing is live.

## App name

```
migs music
```

(Play Store: 30 character max. Used: 10.)

## Short description (80 char max)

```
A fast, private, local music player. Sync playlists from your Mac.
```

(Used: 65.)

Alternative shorter: `Local music. No cloud, no ads, no telemetry.` (44 chars) — leaves room for an emoji or for I18N expansion.

## Full description (4000 char max)

```
migs music is a music player for your own files. No streaming, no cloud, no
account, no ads. It plays what's on your phone and gets out of the way.

Pair it with the companion Mac app to sync Apple Music playlists to your
phone over USB in seconds. Tick the playlists you want, click Sync, and the
audio + metadata land on your phone exactly mirrored — re-syncing is
incremental and quick.

Highlights

  • Plays everything Android indexes — MP3, FLAC, M4A, OPUS, OGG.
  • Browse by song, album, artist, folder, playlist, and "Loves" (your
    hearted songs).
  • Apple-Music-style segmented queue: History / Current / Up Next / Later
    / Remaining context. Drag to reorder Up Next; swipe to remove.
  • Heart any song to add it to your local-only "Loves" virtual playlist.
    Survives every sync, every reinstall.
  • Smooth crossfade between tracks (configurable).
  • Background playback, lock-screen controls with album art, audio focus
    handover, Bluetooth support.
  • Resumes where you left off — queue, position, shuffle, repeat all
    survive cold starts.
  • Sync from Apple Music on your Mac via the companion app — playlists
    arrive in seconds, only what changed gets pushed.
  • Auto-rescan when files appear or change.

Privacy

  • No internet permission. The app physically cannot phone home.
  • No analytics, no crash reporting, no tracking.
  • No advertising ID, no third-party SDKs.
  • Music stays on your device. The Mac sync only happens over USB when
    you click Sync.
  • Full privacy policy: https://michaelhball.github.io/migs-music/privacy.html

migs music is open source. Source: https://github.com/michaelhball/migs-music
```

(Used: ~1900. Plenty of room for future feature additions.)

## What's new (release notes — per release)

Play Store shows the most recent "What's new" copy per locale. Up to 500 chars per locale. Default English template:

```
v0.1.0 — first release.
- Local music playback with album, artist, folder, playlist, and "Loves" browsing.
- Mac companion app for one-click playlist sync from Apple Music over USB.
- Lock-screen + notification controls with album art.
- Background playback, queue persistence, crossfade.
- No telemetry, no cloud, no ads.
```

For minor releases keep it punchy and user-facing:

```
v0.1.1
- Faster Mac→phone sync (~5x for resyncs with no new audio).
- Lock-screen album art now reliable on OnePlus.
- Auto-update via in-app prompt on the Mac side.
```

## Categorisation

- **Category**: Music & Audio
- **Tags**: pick from Play Console's list when prompted; "music player" / "audio player" are the obvious ones.

## Contact details

- **Email**: michael.h.s.ball@gmail.com (required and visible to users)
- **Website**: https://github.com/michaelhball/migs-music
- **Privacy policy**: https://michaelhball.github.io/migs-music/privacy.html

## Graphics

| Asset | Dimensions | Required? | Status |
| --- | --- | --- | --- |
| App icon | 512×512 PNG | Required | Done — `workspace/play-store-icon-512.png` (rendered from `workspace/icon-concepts/_CHOSEN-current.svg`) |
| Feature graphic | 1024×500 PNG | Required | **TODO** — needs designed; placeholder ok for v0.1.0 if pressed |
| Phone screenshots | 1080×1920 (or 9:16-ish) | Min 2, max 8 | TODO — capture from device once features are stable |
| 7-inch tablet screenshots | 1200×1920 | Optional | Skip |
| 10-inch tablet screenshots | 1920×1200 | Optional | Skip |

For the feature graphic: a simple gradient with the bunny-music-note icon centered + "migs music — local, private, fast" text would be enough to clear the bar. Can be done in Figma or any image editor in 30 minutes. Or by an actual designer if you want it nice.

For phone screenshots: take 4–5 — songs list, player full-screen, playlist detail, queue view, Loves. The Play Console accepts JPEGs at common phone aspect ratios; just take them via `adb shell screencap` from the dev device.

## Content rating

Run the Play Console questionnaire — for migs music every answer is "no" (no violence, no user-generated content, no in-app purchases, no ads, no location data, etc.). Result: rated for everyone.

## Data safety

Declare:
- "No data collected"
- "No data shared"
- The privacy policy URL above

Play Console will flag this needs to match what the manifest declares. We have `READ_MEDIA_AUDIO` + `POST_NOTIFICATIONS` etc., none of which are "data collected from users" — they're permissions to read on-device files. The form has a checkbox for "this app does not collect or share any user data" — tick it and submit.
