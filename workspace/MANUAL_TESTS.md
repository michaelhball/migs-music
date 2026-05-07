# Manual tests

Things that need a real phone (and sometimes a Mac too) to verify. Not currently
covered by automated tests because they involve system-level dialogs, the Music.app
AppleScript bridge, the Mac↔phone sync round-trip, or other things the test runner
can't simulate. Run these when you have time / next phone-side session.

Tick when done. Add new entries as we accumulate phone-only work.

---

## Sync round-trip — per-song removal cleans up audio

**Setup**

- Mac app open, phone connected via USB, "Delete audio files when unsynced" toggled ON.
- Pick a synced playlist that has at least 2 songs, where at least one of those songs
  is *not* in any other synced playlist on the phone (so it'll be a clean orphan).

**Steps**

1. In Music.app, remove one of the unique songs from that playlist.
2. Hit Sync on the Mac menu.
3. On the phone, open Settings tab.
4. Verify "Clean up orphan audio files" shows "1 file waiting" (or however many you removed).
5. Tap **Clean up**. The system delete confirmation should appear.
6. Confirm. Toast / dialog dismisses.
7. Verify Settings now reads "Nothing to clean up."
8. Verify the audio file is genuinely gone from `/sdcard/Music/...` (e.g. via `adb shell ls`).

**What this tests**

- AutoImportService correctly identifies orphans on per-playlist replace.
- OrphanAudioTracker captures content URIs.
- `MediaStore.createDeleteRequest` flow works end-to-end on this device.
- Files actually leave the phone.

---

## Fresh-install sync flow

**Setup**

- Uninstall migs music from the phone (`adb uninstall com.migsmusic`).
- Mac app open, phone connected, at least one playlist ticked.

**Steps**

1. Reinstall via `./gradlew :app:installDebug`.
2. Open the app once, grant `READ_MEDIA_AUDIO` permission, close the app.
3. Hit Sync on the Mac.
4. Verify on the phone: the synced playlists appear with their songs, no
   intermediate prompts (no SAF picker, no "set up M3U folder" anything).

**What this tests**

- The post-SAF-removal sync path works on a clean install.
- No leftover prompts from old code paths.

---

## "Preparing playback…" notification doesn't linger

**Steps**

1. Force-stop migs music.
2. Open the app, tap a song.
3. Lock the phone immediately.

**Verify**

- The lock-screen / notification-shade media card shows the **actual song** (title
  + artist + art), not "Preparing playback…" or the app name as a placeholder.
- If the placeholder shows briefly during the tap → media-session-active window,
  that's expected; it just shouldn't *stay* there.

**What this tests**

- Commit 9f76480 (eager `DefaultMediaNotificationProvider` install + bootstrap
  notification reads `playbackManager.uiState.value.currentSong`).

---

## Lock-screen widget — actions work

**Steps**

1. Play a song. Lock the phone.

**Verify**

- Album art is visible.
- Play/pause toggles audio without unlocking.
- Skip-next moves to the next song.
- Skip-previous moves to the previous (or restarts current, depending on system).
- Tapping the *body* of the card (not a control) opens the app at the player route.

**Known limitations (don't worry about)**

- Button sizes are system-controlled on Android 11+.
- ~half-second tap latency on OnePlus is typical baseline; not a bug to chase.

---

## Mini-player auto-activation paranoia

**Steps**

1. Force-stop migs music.
2. Open the app, navigate into a playlist's detail view *without* tapping any song.

**Verify**

- The mini-player at the bottom does *not* appear (nothing should be playing).
- Going back, the mini-player still doesn't appear.

**What this tests**

- Old user-reported bug: opening a playlist sometimes auto-populated the mini-player.
- Current code shows no errant `playPlaylist`/`playContext` call in nav paths, but
  worth a 30s sanity check.
