# Test flakiness notes

## What was done (2026-05-05)

Two structural fixes substantially improved suite stability:

1. **`PlaybackManager.resetForTest()` + `@Before` reset hook in every playback-touching test class.** The PlaybackManager singleton survives between `@Test` methods on a fresh `MainActivity`, so each test inherited the previous test's queue, audio focus, MediaSession, foreground service, and cached state. The reset stops the player, clears the queue/cache/error counter, releases the MediaSession, stops the foreground service, and zeroes the persisted snapshot. Wired into `@Before` for every test class that touches the player: AudioFocusTest, EdgeCasesTest, LifecycleTest, MainActivityTest, MediaKeyTest, MediaSessionTest, PlaylistFlowsTest, QueueFlowsTest, RobustnessTest, SearchAndPlayerTest, SwipeAwayTest.

2. **`LibrarySyncObserver` disabled under instrumentation.** A stray MediaStore notification (any audio-touching app on the device can cause one) kicks off a multi-second rescan that re-emits 1845 songs through every observer and breaks `waitUntil` timing. We detect instrumentation by checking for `androidx.test.platform.app.InstrumentationRegistry` on the classpath — only present in the test APK — so production builds keep auto-rescan enabled.

3. **Playlist accumulator cleanup in PlaylistFlowsTest's `@Before`.** Test playlists named `Migs-Test-${UUID}` accumulated in the DB across runs (29 leftovers when this was discovered). The PlaylistsRoute LazyColumn only renders the first ~8 alphabetically, and a freshly-created test playlist would sort below the fold — making `onAllNodesWithText` time out even though the playlist was in the DB. Fix: wipe all playlists at the start of each PlaylistFlows test.

## Current state — measured over 10+ smoke runs

- `openReorderRemoveDelete` (original primary flake): **never recurred** post-fix.
- Pass rate per full-suite run: **80–90%** (vs the prior baseline that often produced 4–6 failures per run).
- Remaining intermittents, each seen ≤1 time per ~10 runs, none consistently flaky:
  - `launchesAndShowsCoreUi` (MainActivityTest)
  - `navigatesPrimaryTabs` (MainActivityTest)
  - `rapidPlayLaterSpamResultsInExpectedQueueLength` (EdgeCasesTest)
  - `rapidTabSwitchingDoesNotCrash` (RobustnessTest)

Each of these passes overwhelmingly. Worth investigating individually if they get worse, but not chase one-off flakes.

## Potential future improvements

If the residual ~10–20% flake rate becomes a problem:

1. **`rapidPlayLaterSpam`** uses `runOnMainSync { repeat(4) { pm.playLater(...) } }`. Each `playLater` launches a coroutine, and four `syncPlayer` calls can interleave during `loadSongsById` suspensions. The 15s wait should swallow this in nearly all cases; if it tightens, consider a small `composeRule.waitForIdle()` between repeats.

2. **`rapidTabSwitchingDoesNotCrash`** uses `assertCleanLogcat` looking for `"Player is accessed"`, `"FATAL EXCEPTION"`, etc. in the device logcat. False positives are possible if any logged exception lingers from prior tests within the buffer's tail. Could narrow the time window (e.g. timestamps).

3. **`MainActivityTest` flakes** appear to be Activity start-up race during back-to-back test runs. A small `composeRule.waitForIdle()` after the rule chain (or in `@Before`) might smooth it; alternatively a deferred `composeRule.activityRule.scenario.onActivity { ... }` step.

4. The audio-focus dumpsys parser (AudioFocusTest) was tightened previously and is not currently flaking.

## Don't touch

These are working well, no need to revisit:
- Coil image loading + lockscreen art
- `SharingStarted.Lazily` flows in PlaylistsViewModel and LibraryViewModel
- Once-per-process auto-scan guard via `AtomicBoolean`
- Drag-to-reorder + ReorderableItem usage (no nested SwipeToDismissBox)
- `combinedClickable` + DropdownMenu song-row context menu
- The `resetForTest()` Main.immediate dispatch — `writeSnapshotNow` reads `player.currentPosition`, which throws off-thread.
