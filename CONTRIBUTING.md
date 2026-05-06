# Contributing to migs music

How to build, run, test, and submit changes.

## Prerequisites

- **JDK 17** (any distribution; Temurin is what CI uses).
- **Android SDK 36** (set `ANDROID_HOME` or pass `sdk.dir` via `local.properties`).
- A device or emulator running Android 8.0+ (API 26+). The developer's device is a OnePlus running Android 14.

## One-time setup per checkout

```bash
git config core.hooksPath .githooks
```

This installs a pre-commit hook that runs `./gradlew ktlintCheck` against any commit that touches Kotlin files. CI runs the same check; the hook just catches it earlier. Bypass with `git commit --no-verify` only in genuine emergencies — failing to bypass cleanly will fail the push CI run.

## Build

```bash
./gradlew :app:compileDebugKotlin    # quickest sanity check
./gradlew :app:assembleDebug          # builds the debug APK
./gradlew :app:assembleAndroidTest    # builds the instrumentation APK
./gradlew :app:installDebug           # installs onto a connected device
```

Build outputs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

## Testing

### Unit tests (fast, no device)

```bash
./gradlew :app:testDebugUnitTest
```

Covers pure logic — queue engine, M3U parser, M3U matcher, playback session repo, consecutive-error tracker. Runs in seconds.

### Instrumented tests (requires a connected device or emulator)

```bash
scripts/device-smoke-test.sh
```

This script handles install, runs the full instrumented suite (25 tests across launch, navigation, playback, queue, playlists, audio focus, media session, swipe-away, etc.), and writes a log into `workspace/`.

Notes for instrumented testing:

- Each test class hosts a `@Before resetPlaybackForTest()` that wipes the singleton `PlaybackManager` state — queue, player, audio focus, media session, foreground service, persisted snapshot. Without this, tests inherit state from whichever class ran before, which used to be the dominant source of cross-test flakiness.
- `LibrarySyncObserver` (auto-rescan on MediaStore changes) is **disabled under instrumentation** — a stray notification from another audio app on the device used to kick off a multi-second rescan that broke `waitUntil` timing. Detection is via `Class.forName("androidx.test.platform.app.InstrumentationRegistry")`, which is only present in the test APK.
- See [workspace/TEST_FLAKINESS_NOTES.md](workspace/TEST_FLAKINESS_NOTES.md) for the history and current state.

## Linting

ktlint runs locally and in CI:

```bash
./gradlew :app:ktlintCheck    # verify
./gradlew :app:ktlintFormat   # auto-fix what's auto-fixable
```

Two rules are deliberately disabled in `.editorconfig`:

- `standard:function-naming` — `@Composable` functions are PascalCase by convention.
- `standard:property-naming` — backing properties (`_uiState`, etc.) follow the Kotlin coroutines docs convention; `UiTestTags` consts are camelCase by design.
- `standard:filename` — we organise files by responsibility (parser, matcher) not by sole class.

## Architecture

```
app/src/main/java/com/migsmusic/
├── MigsMusicApplication.kt     # AppContainer wiring; Coil image loader; Room DB
├── MainActivity.kt             # Single activity, hosts MigsMusicApp composable
├── AppPreferences.kt           # SharedPreferences wrapper
├── data/
│   ├── LibrarySyncObserver.kt  # Auto-rescan on MediaStore changes
│   ├── local/                  # Room entities, DAOs, models
│   └── repository/             # LibraryRepository, PlaylistRepository, PlaybackSessionRepository
├── playback/
│   ├── PlaybackManager.kt      # Singleton; owns ExoPlayer + queue + media session
│   ├── QueueEngine.kt          # Pure in-memory queue model (Main-only)
│   ├── ConsecutiveErrorTracker.kt
│   ├── MediaPlaybackService.kt # Foreground service hosting the MediaSession
│   └── QueueModels.kt
├── playlistimport/
│   ├── M3uParser.kt            # Pure parser — plain + Extended M3U
│   ├── M3uMatcher.kt           # Three-pass matcher (exact / normalised / basename); reusable index
│   ├── M3uFileScanner.kt       # Direct fs scan of /sdcard/Android/media/com.migsmusic/sync/
│   ├── AutoImportService.kt    # Per-file import + manifest-driven playlist prune
│   └── AutoImportReceiver.kt   # Manifest-declared receiver woken by Mac sync broadcast
└── ui/
    ├── MigsMusicApp.kt         # Top-level NavHost + bottom bar
    ├── MigsMusic{Songs,Albums,Folders,Playlists,Queue,Player}Screen.kt
    ├── MigsMusicCommon.kt      # ListRow, EmptyState, AlbumArtImage, etc.
    ├── MigsMusicDialogs.kt     # AddToPlaylist + name dialogs
    ├── {Library,Player,Playlists}ViewModel.kt
    └── UiTestTags.kt
```

Key architecture decisions:

- **Single Activity, Compose Navigation.** No fragments.
- **`PlaybackManager` is a singleton** on `AppContainer`. Always accessed on `Dispatchers.Main.immediate` — ExoPlayer mandates main-thread access for player APIs.
- **`QueueEngine` is pure-Kotlin and Main-only.** No locks; threading correctness is enforced by always dispatching mutations through `Main.immediate`.
- **Snapshot persistence is conflated** through a `Channel<Unit>(CONFLATED)` consumed by a single writer coroutine — rapid skips/transitions collapse to one save.
- **Library queries push to SQL.** Albums, artists, folder filtering are all Room `GROUP BY` queries — not in-memory.
- **WAL journal mode** so MediaStore-driven scans don't block UI reads. WAL is also explicitly checkpointed on Activity stop so writes don't get lost across `adb install -r` cycles in development.
- **Stable song identity via `absolutePath`.** MediaStore `_ID` reassigns when MediaScanner re-reads a file's tags. `LibraryRepository.scanDevice` detects these reassignments by joining old vs. new (id, absolutePath) pairs and remaps `playlist_songs.songId` references atomically before pruning, so playlists survive `_ID` churn.
- **Sync via app media dir, not SAF.** The Mac sync app pushes M3U + manifest to `/sdcard/Android/media/com.migsmusic/sync/` via `adb`. Owned by us, no permission grant required, no SAF picker (Android 11+ refuses to grant access to `/sdcard/Music` so SAF is a non-starter for our use case). Audio files still go to `/sdcard/Music/` where MediaStore indexes them.
- **`AutoImportReceiver`** is manifest-declared with `FLAG_INCLUDE_STOPPED_PACKAGES` support so the Mac sync's broadcast wakes the app from cold-stopped state.
- **Coil with custom `ImageLoader`** — 20% memory cache, 64MB disk cache for album art.

## Project layout for non-source files

- `docs/` — long-form documentation.
- `workspace/` — internal planning + handoff documents (committed; not user-facing).
- `scripts/` — operational scripts (the smoke test runner, etc).
- `gradle/` — wrapper + version catalog.
- `.githooks/` — committed git hooks. Not active until you run the `git config` command above.
- `.github/workflows/` — CI definitions.

## CI

GitHub Actions on push and PR to `main`:

1. Reject any file > 5 MB (catches accidental binary commits).
2. Validate Gradle wrapper checksums.
3. ktlint.
4. Compile debug.
5. Run unit tests.
6. Upload test + ktlint reports as artifacts on failure.

Instrumented tests are **not** in CI — they need a real device. Run `scripts/device-smoke-test.sh` before merging anything that could affect runtime behaviour.

## Codebase conventions

- **Comments only when the *why* is non-obvious.** No comments that restate the code, no "added for issue #123" notes — the PR description handles that.
- **Trailing commas** on multi-line argument lists (ktlint enforces).
- **Compose composables: PascalCase** (intentionally bypasses ktlint's default rule).
- **Backing properties: `_uiState` / `uiState` pattern** from the Kotlin coroutines docs.
- **No mocks for the database** — tests hit real Room. Confidence in migrations beats test speed.

## Notes / known issues

- `ksp.incremental=false` is set in `gradle.properties` to avoid a local KSP cache issue during Room compilation. Remove if it ever stops being necessary.
- Connected-device automation has been verified on a OnePlus `CPH2719`. Other vendors' Android skins may behave differently around audio focus arbitration; the `dumpsys media_session` parser in `AudioFocusTest` is scoped to our package to avoid this.
