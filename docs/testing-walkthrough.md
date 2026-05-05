# MIGS Music Testing Walkthrough

This document is the practical handoff for the next testing pass.

It is written to support two goals:

- resume quickly without re-reading the whole codebase
- prioritize the tests that are most likely to expose real bugs in a local Android music player

## Current State

The app currently supports:

- library scan from `MediaStore`
- browse by songs, albums, artists, folders, and playlists
- playlist create, rename, delete, add, remove, reorder
- queue with `Play Next`, `Play Later`, clear upcoming, and reordering
- playback session restore
- full player screen plus mini-player
- `Media3` `MediaSessionService` playback architecture

Important implementation references:

- playback core: [PlaybackManager.kt](/Users/michaelball/projects/migs-music/app/src/main/java/com/migsmusic/playback/PlaybackManager.kt)
- queue rules: [QueueEngine.kt](/Users/michaelball/projects/migs-music/app/src/main/java/com/migsmusic/playback/QueueEngine.kt)
- library scan: [LibraryRepository.kt](/Users/michaelball/projects/migs-music/app/src/main/java/com/migsmusic/data/repository/LibraryRepository.kt)
- app shell: [MigsMusicApp.kt](/Users/michaelball/projects/migs-music/app/src/main/java/com/migsmusic/ui/MigsMusicApp.kt)
- product behavior spec: [v1-spec.md](/Users/michaelball/projects/migs-music/docs/v1-spec.md)

## Most Important Testing Priorities

Test these first:

1. Queue correctness
2. Folder playback ordering
3. Playlist ordering and reordering
4. Restore behavior after scan changes
5. Background/lockscreen/media notification controls

These are the areas most likely to produce user-visible bugs.

## Build Commands

Core local validation:

```bash
./gradlew :app:compileDebugKotlin
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleAndroidTest
```

Useful artifact paths:

- debug APK: [app-debug.apk](/Users/michaelball/projects/migs-music/app/build/outputs/apk/debug/app-debug.apk)
- Android test APK: [app-debug-androidTest.apk](/Users/michaelball/projects/migs-music/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk)

## Device Testing

Preferred path is a real Android device.

1. Connect the phone over USB.
2. Enable USB debugging.
3. Verify the device is visible:

```bash
adb devices -l
```

4. Install the app:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

5. Launch the app:

```bash
adb shell am start -n com.migsmusic/.MainActivity
```

### One-Command Smoke Test

After the phone has been authorized for USB debugging once, use:

```bash
scripts/device-smoke-test.sh
```

This script:

- builds the debug APK and Android test APK
- installs both APKs
- runs the instrumentation smoke test directly through `adb`
- mutes the media stream before playback automation
- launches the app
- captures a screenshot
- captures recent logs

Artifacts are written to:

- [build/device-smoke](/Users/michaelball/projects/migs-music/build/device-smoke)

This does not bypass the initial USB debugging trust prompt. That prompt is an Android security boundary and still requires one manual confirmation on the phone.

Latest verified device run:

- device: OnePlus `CPH2719`
- Android version reported by device: `16`
- library indexed during test session: `1845` songs in `6` folders
- instrumentation result: `OK (3 tests)`
- captured screenshot: [launch.png](/Users/michaelball/projects/migs-music/build/device-smoke/launch.png)
- captured logs: [logcat.txt](/Users/michaelball/projects/migs-music/build/device-smoke/logcat.txt)

## Emulator Testing

Only use this if memory headroom is acceptable.

Known AVDs on this machine:

- `Pixel_8`
- `Medium_Phone`

Boot headless:

```bash
~/Library/Android/sdk/emulator/emulator -avd Pixel_8 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -netdelay none -netspeed full
```

Wait for boot:

```bash
adb wait-for-device shell getprop sys.boot_completed
```

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Optional instrumentation smoke test:

```bash
./gradlew connectedDebugAndroidTest
```

If memory becomes a concern, stop immediately:

```bash
adb emu kill
./gradlew --stop
```

## Manual Test Matrix

### 1. Library Scan

Validate:

- first launch with permission request
- scan completes successfully
- songs appear after scan
- folders reflect actual Android music path structure
- deleted files disappear after rescan
- newly added files appear after rescan

Specifically watch for:

- duplicate songs
- stale entries after file deletion
- very slow rescans on larger libraries

### 2. Song Playback

Validate:

- tap song starts playback
- next/previous works
- seek works from the full player
- repeat cycles `Off -> All -> One -> Off`
- mini-player reflects current song
- full player opens from mini-player

### 3. Queue Semantics

This is the highest-priority behavioral area.

Validate:

- `Play Next` inserts ahead of untouched context songs
- multiple `Play Next` calls preserve insertion order
- `Play Later` stays after the `Play Next` block but before untouched context songs
- queue reorder changes actual future playback order
- `Clear Up Next` clears future queue items

Reference example that must work:

- start playlist `A, B, C, D, E` from `B`
- add `X` as next
- add `Y` as later
- add `Z` as next
- expected upcoming order: `X, Z, Y, C, D, E`

### 4. Folder Behavior

Validate:

- folder lists are stable across rescans
- folder playback follows displayed order
- shuffle folder keeps playback valid
- folder behavior still makes sense for Android-style paths like `Music/Artist/Album/...`

### 5. Playlist Behavior

Validate:

- create playlist
- rename playlist
- add songs from songs/albums/artists/folders
- remove songs
- move songs up/down
- playback respects playlist order
- shuffle playlist still works

### 6. Restore and Robustness

Validate:

- start playback, close app, reopen app
- current song/queue restore correctly
- remove or rename a file externally, rescan, ensure playback state remains sane
- if current song disappears, app should not crash and should recover gracefully

### 7. Lockscreen / Notification / Background

This still needs real device validation.

Validate:

- notification appears during playback
- play/pause/next/previous work from notification
- lockscreen controls appear and work
- screen off playback continues
- session metadata is correct for current song

### 8. Bluetooth / Headset

If hardware is available, validate:

- play/pause button
- next/previous button
- unplugging headphones does not produce bad behavior

## Automated Tests Today

Current tests:

- queue unit tests: [QueueEngineTest.kt](/Users/michaelball/projects/migs-music/app/src/test/java/com/migsmusic/QueueEngineTest.kt)
- connected-device smoke tests: [MainActivityTest.kt](/Users/michaelball/projects/migs-music/app/src/androidTest/java/com/migsmusic/MainActivityTest.kt)

The connected-device smoke tests currently cover:

- launch and permission/library gate readiness
- primary tab navigation across Songs, Folders, Playlists, and Queue
- starting playback from the first visible song
- mini-player previous/play-pause/next controls being present
- opening the full player from the mini-player

If adding tests next, prioritize:

1. restore behavior after songs disappear
2. playlist reorder persistence
3. playback refresh after rescan
4. notification/media-session behavior if testable

## Known Technical Context

- `ksp.incremental=false` is intentionally set in [gradle.properties](/Users/michaelball/projects/migs-music/gradle.properties) because local KSP cache behavior was unstable.
- Room schema export is currently disabled to keep iteration stable.
- The app database is currently at version `2`.
- Queue/session restoration was recently hardened to tolerate missing library items.

## Remaining Product Gaps

These are not blockers for broad testing, but they are still open:

- no home-screen widget
- no hierarchical folder browser yet
- no artwork loading/caching UI layer yet
- lockscreen/media notification behavior is not yet fully validated on a real device
- automated tests do not yet create/edit playlists or validate lockscreen controls

## Recommended Resume Order

When coming back to this project, do the next session in this order:

1. Run `scripts/device-smoke-test.sh` with the phone connected.
2. Check [build/device-smoke/instrumentation.txt](/Users/michaelball/projects/migs-music/build/device-smoke/instrumentation.txt) for `OK`.
3. Check [build/device-smoke/logcat.txt](/Users/michaelball/projects/migs-music/build/device-smoke/logcat.txt) for app crashes or Media3 thread errors.
4. Execute the manual queue/folder/playlist matrix.
5. Validate lockscreen and notification controls.
6. Only then return to emulator work or UI mockups.
