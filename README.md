# MIGS Music

Local-first Android music player focused on core library playback with a fast queue model and minimal scope.

## Current Status

Implemented:

- local library scan from `MediaStore`
- browse by songs, albums, artists, folders, and playlists
- playback with `Media3`
- background-capable media session service
- queue with `Play Next` and `Play Later`
- queue reordering
- playlist create, rename, delete, add, remove, and reorder
- playlist/folder/album/artist playback in visible order or shuffled order
- session restore after restart

## Build

Verified locally with:

- `./gradlew :app:compileDebugKotlin`
- `./gradlew testDebugUnitTest`
- `./gradlew assembleDebug`
- `./gradlew assembleAndroidTest`

Debug APK:

- [app-debug.apk](/Users/michaelball/projects/migs-music/app/build/outputs/apk/debug/app-debug.apk)

Android test APK:

- [app-debug-androidTest.apk](/Users/michaelball/projects/migs-music/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk)

## Testing Readiness

The project is ready for extensive manual testing and connected-device validation.

What is already in good shape:

- app builds cleanly
- unit tests for queue behavior pass
- Android instrumentation tests run on a connected OnePlus device
- automated smoke coverage verifies launch, primary tab navigation, song playback start, mini-player controls, and full-player entry
- playback/session state is hardened against library changes and missing files
- search and library grouping were reviewed for larger-library performance

What still needs real-world validation:

- lockscreen controls
- media notification controls
- Bluetooth/headset transport controls
- background playback lifecycle on a real device
- long-session stability with actual user media

## Next Steps

Use the walkthrough in [docs/testing-walkthrough.md](/Users/michaelball/projects/migs-music/docs/testing-walkthrough.md).

For a connected phone that has already been authorized for USB debugging, run:

```bash
scripts/device-smoke-test.sh
```

## Notes

- Connected-device automation has been verified with `scripts/device-smoke-test.sh` on a OnePlus `CPH2719`.
- `ksp.incremental=false` is set in [gradle.properties](/Users/michaelball/projects/migs-music/gradle.properties) to avoid a local KSP cache issue during Room compilation.
- An emulator smoke-test path was started successfully, but it was intentionally paused when memory pressure became a concern.
