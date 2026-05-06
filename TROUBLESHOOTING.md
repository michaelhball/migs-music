# Troubleshooting

Common problems and how to fix them. If something here doesn't help, check `adb logcat` for errors mentioning `migsmusic` and the Android side, or open an issue.

## Sync from Mac didn't appear on the phone

The Mac app reports "✓ Synced" but the playlist doesn't show up.

1. **Open migs music on the phone.** Even though the receiver is supposed to wake the app from cold, OEM battery-saver settings sometimes block manifest broadcasts to "stopped" apps. Opening the app once gets it into the running state.
2. **Check the sync directory has files:**
   ```
   adb shell "ls /sdcard/Android/media/com.migsmusic/sync/"
   ```
   If you see `*.m3u` files lingering, the import didn't run. Tap the **Playlists** tab — it triggers a refresh on entry, which catches anything the broadcast missed.
3. **Logcat for hard errors:**
   ```
   adb logcat -d | grep -E "AutoImport|MigsMusic"
   ```
   `AutoImportService` logs every import outcome. `Failed` lines tell you the reason.

## "Couldn't import X" snackbar

The auto-importer hit a real error reading or parsing the M3U file. Common causes:

- **Empty M3U file**: the Mac sync produced no track list, usually because the source playlist contained only streaming-only Apple Music tracks. Download the actual MP3s in Music.app and re-sync.
- **Parse error**: file contains malformed `#EXTINF` lines or non-UTF-8 bytes. Open the file (`adb pull /sdcard/Android/media/com.migsmusic/sync/<name>.m3u -`) and check it manually.
- **Read denied**: shouldn't happen post-Android-11 since the sync directory is app-owned, but if seen it means another app or root tool is holding the file. Reboot the phone.

## Songs in a synced playlist won't play

You synced a playlist with N songs. The playlist shows up but tapping a song does nothing or skips immediately.

- **Audio file isn't actually on the phone.** The M3U references it, but `adb push` failed or the file was deleted later. Check:
  ```
  adb shell "find /sdcard/Music -name '<song-filename>'"
  ```
  Empty result = not on the phone. Re-sync from Mac.
- **MediaStore hasn't indexed the file yet.** Wait 30s for `LibrarySyncObserver` to pick it up, or trigger a manual rescan via **Settings → Rescan music files**.
- **The file is at an unexpected path.** migs music reads from `MediaStore`, not the filesystem directly — files in `/sdcard/Music/` are indexed; files in `/sdcard/Android/data/<some-app>/` are not.

## Mini-player keeps showing a stale paused song

The mini-player is showing a track from a previous session. Tapping play would resume it, which feels weird if you've been away from the app a while.

- **Expected behaviour.** migs music persists the queue across cold starts so you can resume where you left off. If you want to clear it, tap the mini-player → full player → context menu (long-press) → "Save queue as playlist" then dismiss; or just play something else.
- **Future improvement:** a "Clear queue" affordance is on the roadmap.

## App says "permission required" on first launch

migs music needs two runtime permissions:

- **`READ_MEDIA_AUDIO`** (Android 13+) or **`READ_EXTERNAL_STORAGE`** (Android ≤12) — to read your music library. Tap **Grant** when prompted.
- **`POST_NOTIFICATIONS`** (Android 13+) — to show the lockscreen / notification-shade playback controls. Asked for *after* media permission is granted. If denied, playback still works; you just won't see the lockscreen widget.

If you denied either by accident, go to **System Settings → Apps → migs music → Permissions** and re-enable.

## Library is missing songs that are clearly on the phone

- **Are they in `/sdcard/Music/` or a subfolder?** MediaStore scans the standard music directories. Files in app-specific dirs (`/sdcard/Android/data/...`) won't appear.
- **Are the files tagged?** MediaStore shows tagless files as "Unknown title / Unknown artist" — they're there, just hard to find. Search for them or check the Folders tab.
- **Force a rescan:** Settings → Rescan music files.

## "doot" playlist (or any other) lost its songs after a debug rebuild

If you're a developer running `./gradlew :app:installDebug` repeatedly:

- WAL data can be lost across rapid `adb install -r` cycles. The app checkpoints the WAL on activity stop, but a force-close + immediate reinstall can race with that.
- Re-sync from the Mac side or force a clean state with `adb shell pm clear com.migsmusic` and re-grant permissions.

## Crashes / freezes

- Capture `adb logcat -d > crash.log` immediately after the crash and open an issue.
- Enable instrumented test mode for repro: `scripts/device-smoke-test.sh` runs the full instrumented suite which exercises most flows.

## I want to remove migs music entirely

```
adb uninstall com.migsmusic
adb shell rm -rf /sdcard/Android/media/com.migsmusic/
```

Audio files in `/sdcard/Music/` are NOT touched — they were already there before migs music, and removing them isn't its business.
