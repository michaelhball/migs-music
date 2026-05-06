# MIGS Music — Code Audit (2026-05-06)

Scope: every file under `app/src/main/java` + `app/src/main/res`. Cross-checked against
`workspace/FOLLOWUPS.md`, `workspace/TEST_FLAKINESS_NOTES.md`, `CLAUDE.md`, and the last 50
commits. The codebase is in good shape overall — playback core (PlaybackManager / QueueEngine
/ snapshot persistence) is well-thought-out, conflated channel writer is solid, the incremental
sync diff is a real win, and the Compose hot-path optimisations (split position flow,
`drawBehind` progress bar, per-row lambda memoization) show real care. Findings below are
mostly around the new sync flow, a handful of hot-path Room queries, and a few Compose details.

---

### 1. 🔴 SAF tree-walk uses `DocumentFile.fromTreeUri(...).listFiles()` recursively on Main-thread-blocking paths
**File:** `app/src/main/java/com/migsmusic/playlistimport/M3uFileScanner.kt:24-54`
**Issue:** `DocumentFile.listFiles()` performs a synchronous ContentResolver query per directory and is famously slow under SAF — easily hundreds of ms per child directory on a Music tree of any depth. The wrapping `withContext(Dispatchers.IO)` keeps it off Main, but `refreshAvailableM3uFiles` is called *every time the user enters the Playlists tab* (`LaunchedEffect(musicFolderUri)` re-fires after every nav cycle that recreates the composition; `musicFolderUri` is read from a StateFlow whose value is stable but the LaunchedEffect's keyed launch still suspends until done). Combined with `libraryRepository.observeAllSongs().first()` snapshotting the entire library each time, every Playlists-tab visit kicks a full SAF crawl + library snapshot + per-file parse/match. On a 1k–10k song library and a Music folder with even modest nesting, that's seconds of background IO and DB load that the user never sees but pays for in battery.
**Fix:** Add a "last scanned" cache key (e.g. `lastM3uScanTimestampMs` in AppPreferences, plus a 30s short-circuit) so repeat tab entries don't re-walk. Or move the trigger to `LaunchedEffect(Unit)` once per VM lifetime, and expose a manual "Refresh" affordance for users who just dropped in a new file. Long term: switch from `DocumentFile.listFiles` to direct `DocumentsContract.buildChildDocumentsUriUsingTree` + cursor — ~5-10× faster.
**Effort:** 30 min for the cache key gate; half-day for the DocumentsContract switch.

---

### 2. 🔴 `observeSubfolders` rebuilds folder map from full song table on every emission
**File:** `app/src/main/java/com/migsmusic/data/repository/LibraryRepository.kt:45-86`, `app/src/main/java/com/migsmusic/data/local/dao/SongDao.kt:15-16`
**Issue:** `observeSubfolders(parentPath)` maps `songDao.observeAllSongs()` (every column of every song) and rebuilds a `mutableMapOf<String,Int>` accumulator from scratch on every cursor change. At 5k songs this is ~5k full-row materializations per emission, and **any** write to the songs table (a single MediaStore tick — common during MTP transfers) re-runs it. Worse, `HierarchicalFolderView` collects `subfoldersOf(parentPath)` AND `directSongsIn(parentPath)` AND `songsRecursivelyIn(parentPath)` — three Flow subscriptions backed by full or near-full table reads, each re-emitting on every songs-table write. So one MediaStore notification while the user is inside the Folders tab fans out to 3× full-table reads + a Kotlin map rebuild. That's a real perf cliff at 10k songs / large libraries.
**Fix:** Push subfolder discovery to SQL — a single `SELECT folderPath, COUNT(*) FROM songs WHERE folderPath = :p OR folderPath LIKE :p || '/%' GROUP BY ...` with a Kotlin-side "next segment" extraction over just the distinct folderPath set (typically <100 rows even at 10k songs) is dramatically lighter than mapping the full song list. Or: add a `(folderPath)` index and a dedicated DAO query that returns just `(folderPath, COUNT(*))` rows.
**Effort:** 1 hour.

---

### 3. 🔴 `LaunchedEffect(musicFolderUri)` re-asserts SAF persistence + triggers background scan on every Playlists-tab nav
**File:** `app/src/main/java/com/migsmusic/ui/MigsMusicPlaylistsScreen.kt:115-125`
**Issue:** Tab navigation uses `restoreState = true` with `popUpTo(start) { saveState = true }`, which keeps the composable's state alive across navigations. But `PlaylistsRoute` is a `composable("playlists")` destination — re-entering the tab re-runs the composable from scratch (Compose's NavHost reuses the entry but re-runs composition) and the `LaunchedEffect(musicFolderUri)` re-launches because its key is freshly observed each composition. Result: every Playlists-tab tap re-takes URI persistence (idempotent but wakes ContentResolver) AND fires `refreshAvailableM3uFiles` (see #1). The auto-import flow then re-parses, re-matches, and re-deletes every M3U it finds on every tap. If a third-party file adds a `.m3u` mid-session, it'll be silently absorbed on the next tab switch — fine — but if the user's intent is "I'll deal with it later" they'll find it disappeared by the time they tap into Playlists again.
**Fix:** Either combine with #1 (cache-key gate) or guard with a `rememberSaveable { mutableStateOf(false) }` flag that only fires once per process; manual refresh via the overflow ⋮.
**Effort:** 15 min.

---

### 4. 🟡 `previewM3uImport` and `refreshAvailableM3uFiles` both snapshot library via `observeAllSongs().first()` — silently blocks on slow first emission
**File:** `app/src/main/java/com/migsmusic/ui/PlaylistsViewModel.kt:204, 306`
**Issue:** `observeAllSongs()` is a Room Flow that emits on subscription, but the very first emission can be delayed up to `Dispatchers.IO` round-trip on a cold DB. Calling `.first()` from `viewModelScope` on a fresh start (e.g. user pastes an M3U during cold launch) suspends until that happens. There's no error/timeout path; if the DB query is interrupted the import dialog never shows anything. Also: `getAllSongs()` already exists as a suspend on SongDao for one-shot reads; using the Flow's first emission just adds invalidation-tracker overhead.
**Fix:** Add `LibraryRepository.getAllSongsOnce(): List<SongEntity>` delegating to `songDao.getAllSongs()` and use it from both call sites.
**Effort:** 5 min.

---

### 5. 🟡 `albumArtUri` from MediaStore is per-album-id, not per-song — every track of an album shares one URI, fine in steady state, but `MAX(albumArtUri)` in `observeAlbums` ignores that some songs of the same album have albumId=null
**File:** `app/src/main/java/com/migsmusic/data/local/dao/SongDao.kt:63-76`, `app/src/main/java/com/migsmusic/data/repository/LibraryRepository.kt:151-157`
**Issue:** Albums are grouped by `(album, artist)` text but art comes from `MAX(albumArtUri)`. If an album has mixed albumId rows (which happens with hand-tagged files where MediaStore couldn't resolve an albumId), the "MAX" deterministically picks one but means art is sometimes silently absent for an album that does have art on the majority of its tracks. Edge case but visible.
**Fix:** `MAX(CASE WHEN albumArtUri IS NOT NULL THEN albumArtUri END)` or `COALESCE(...)` ordering — minor SQL change; or pick the most-common albumArtUri per group.
**Effort:** 15 min.

---

### 6. 🟡 Player route's two-pointerInput-stack interferes with Slider drag below
**File:** `app/src/main/java/com/migsmusic/ui/MigsMusicPlayerScreen.kt:108-159`
**Issue:** The outer `Box` has TWO `pointerInput(Unit)` modifiers: one for `detectDragGestures` (swipe-to-dismiss / skip), one for `detectTapGestures(onLongPress)`. Compose's pointer dispatch routes events to the deepest hit target first, so the inner Slider works when its hit-test wins. But on rapid touches near the slider, the outer drag-detector can pick up the gesture before the slider does, especially if the user starts a horizontal drag near the slider edge — the slider stays at its old value while the queue advances to the next track. There's no `awaitFirstDown(requireUnconsumed = true)` to let the slider claim the gesture first, and the drag detector commits at threshold without consulting children.
**Fix:** Move the swipe-dismiss/skip detection up to a different element (e.g. wrap Slider in its own Box, attach drags only to the album-art region above), or gate the drag detector on `dragY > skipThresholdPx` with a higher initial threshold so micro-jitter near the slider doesn't trip skip-track. Also worth replacing `detectDragGestures` with `awaitEachGesture` that explicitly checks consumption.
**Effort:** 30 min.

---

### 7. 🟡 `refreshAvailableM3uFiles` reads + parses + matches every M3U sequentially with no progress signal
**File:** `app/src/main/java/com/migsmusic/ui/PlaylistsViewModel.kt:293-318`
**Issue:** The for-loop inside the `viewModelScope.launch` does sync IO + parse + DB upsert per file. With N M3Us each containing M entries, total work is roughly `N × (file IO + parse + matchM3uEntries(M, library))`. The matcher pre-indexes `library` per call, so each file does another round of normalize+associateBy over the full library — for 10 M3Us against 5k songs, that's 5×3 = 15 indices built from scratch. The user sees *nothing* on screen during this — `_availableM3uFiles` is only set at the end.
**Fix:** Build the three matcher indices ONCE outside the loop and pass them in; either expose `M3uMatcher.buildIndex(library)` and `matchM3uEntriesUsingIndex(...)` or pre-call `matchM3uEntries` once with all combined entries and split. Also consider emitting progress (a count) via a separate StateFlow so the UI can show a spinner.
**Effort:** 30 min for the index reuse; 1 hour with progress.

---

### 8. 🟡 `embedArtworkForCurrent` reads SongEntity from Room instead of using `queueSongCache`
**File:** `app/src/main/java/com/migsmusic/playback/PlaybackManager.kt:739-765`
**Issue:** Every media-item transition calls `embedArtworkForCurrent`, which calls `libraryRepository.getSongsByIds(listOf(currentEntry.songId))` even though the same song is already in `queueSongCache`. On a long queue, that's an extra Room IO per skip. The `queueSongCache` was specifically introduced to avoid this elsewhere.
**Fix:** `val song = queueSongCache[currentEntry.songId] ?: libraryRepository.getSongsByIds(listOf(currentEntry.songId)).firstOrNull() ?: return@launch`.
**Effort:** 5 min.

---

### 9. 🟡 PlayerScreen's `state.durationMs.coerceAtLeast(1L).toFloat()` is recomputed every recomposition and forces an extra draw
**File:** `app/src/main/java/com/migsmusic/ui/MigsMusicPlayerScreen.kt:269,278`
**Issue:** `Slider` reads `valueRange = 0f..state.durationMs.coerceAtLeast(1L).toFloat()` directly. When `state.durationMs` flickers (it does — `publishUiState` writes it whenever the player listener fires, and the position ticker can also overwrite it), the range changes every recomposition, which forces Slider to re-measure its thumb position. Combined with `sliderPosition` being a derivedStateOf, the slider gets multiple cascading updates per second.
**Fix:** `val durationFloat by remember(state.durationMs) { mutableStateOf(state.durationMs.coerceAtLeast(1L).toFloat()) }`, then use `durationFloat` in the slider.
**Effort:** 5 min.

---

### 10. 🟡 `MainActivity.checkMusicPermission()` + `requiredMusicPermission()` not wrapped in `remember` inside Compose
**File:** `app/src/main/java/com/migsmusic/MainActivity.kt:33-39`
**Issue:** `var hasPermission by remember { mutableStateOf(checkMusicPermission()) }` calls `checkMusicPermission()` on the activity lambda every initial composition, which is fine on cold start, but if the user grants permission via system settings rather than the in-app prompt, the state never updates because there's no `lifecycleScope` re-check on resume. The permission would correctly read true on next cold start but the user has to back out and relaunch.
**Fix:** Hook `LocalLifecycleOwner.current.lifecycle` and re-check on `Lifecycle.Event.ON_RESUME` (a `DisposableEffect`).
**Effort:** 15 min.

---

### 11. 🟡 `LibrarySyncObserver` runs every scan on `Dispatchers.IO` but `scanDevice` chunks `upsertAll` outside any explicit transaction
**File:** `app/src/main/java/com/migsmusic/data/repository/LibraryRepository.kt:206-208`
**Issue:** Each chunk's `songDao.upsertAll(chunk)` is its own implicit transaction. For 5k songs that's ~5 separate WAL commits, each with fsync overhead. Wrapping the full chunk loop in a single `@Transaction` makes the upsert atomic AND much faster, since SQLite batches writes inside one transaction.
**Fix:** Add `@Transaction suspend fun upsertAllChunked(chunks: List<List<SongEntity>>)` to SongDao that loops `upsertAll(chunk)` inside one transaction. Keeps chunking for memory pressure, gets one commit.
**Effort:** 15 min.

---

### 12. 🟡 `searchSongs` query uses `LIKE '%' || :query || '%'` — no FTS, no index possible
**File:** `app/src/main/java/com/migsmusic/data/local/dao/SongDao.kt:109-119`
**Issue:** Each search keystroke after the 150ms debounce runs a full table scan with four LIKE-with-leading-wildcard predicates. SQLite cannot use an index for leading-wildcard LIKE. At 10k songs this is a noticeable latency on cold cache. Not a blocker but a real perf cliff at scale.
**Fix:** Add an FTS4 virtual table (`songs_fts(title, artist, album, folderName)`) populated by triggers; use `MATCH` instead of LIKE. ~30-line schema migration + a new DAO method. Big payoff at scale.
**Effort:** half-day including migration tests.

---

### 13. 🟡 No POST_NOTIFICATIONS runtime permission request on Tiramisu+ — silent dropped media notification on first launch
**File:** `app/src/main/AndroidManifest.xml:10`, `app/src/main/java/com/migsmusic/MainActivity.kt`
**Issue:** Manifest declares `POST_NOTIFICATIONS` but the activity never requests it at runtime. On Android 13+ the OS auto-denies the first time we try to post the playback notification. Users will play a song and see nothing on the lockscreen / shade until they go into system settings to grant. This is a real UX regression on modern devices.
**Fix:** Add a runtime request flow either alongside the music-permission gate or just before the first `ensureServiceStarted()` (i.e. first playback). The simplest is to ask for it after music-permission grant, in the same `PermissionGate` flow.
**Effort:** 30 min.

---

### 14. 🟡 SAF auto-import's per-file `runCatching { ... }.getOrElse { Failed }` swallows everything silently
**File:** `app/src/main/java/com/migsmusic/ui/PlaylistsViewModel.kt:333-364`
**Issue:** If an M3U file is malformed, has unreadable bytes, or its `delete()` throws (e.g. revoked SAF write permission), the user gets no signal — file vanishes from `unprocessed` if it was Imported, lingers if Failed, with no way to know which. The auto-import is hidden behind the synced-playlist abstraction. If the Mac-side script ships a bad M3U, the user has zero feedback to debug it.
**Fix:** Capture failures into a separate StateFlow / one-shot snackbar surface — at minimum log the exception via `Log.w(TAG, "auto-import failed for ${file.displayName}", e)` and show a snackbar "Couldn't import N files" if any AutoImportOutcome.Failed showed up. The current swallow makes "the M3U disappeared but no playlist appeared" untraceable.
**Effort:** 30 min.

---

### 15. 🟡 `tryIncrementalSync` move-detection fails for any move spanning more than one position
**File:** `app/src/main/java/com/migsmusic/playback/PlaybackManager.kt:625-642`
**Issue:** The move detector finds `firstDiff`, identifies the moved entry, and then verifies that removing-then-reinserting at the new position reconstructs `newIds`. It correctly handles single-step moves but for a move from index 3 to 7 in a 10-item queue, after removing at 3, the rebuilt list will diverge at the FIRST changed position (index 3), but the entry at the new position 7 in `newIds` may have shifted — the equality check only succeeds when there's exactly one moved item with no further shifts. With drag-to-reorder this is the common case, but if the user does multiple rapid reorders before the previous syncPlayer settles, the new state may have multiple diffs and incremental sync falls through to full rebuild. Behaviorally fine, just defeats the optimization.
**Fix:** Either accept this as the documented limitation, or rewrite as: compute the LCS between the two lists, and if exactly one element is missing from each side and they're the same element, apply `moveMediaItem(fromPos, toPos)`.
**Effort:** 30 min.

---

### 16. 🟡 `PlaylistsViewModel.playlistSongs(playlistId)` creates a fresh StateFlow per call
**File:** `app/src/main/java/com/migsmusic/ui/PlaylistsViewModel.kt:96-98`
**Issue:** Every call returns `playlistRepository.observePlaylistSongs(playlistId).stateIn(viewModelScope, ...)` — a new StateFlow each invocation. The screen wraps this in `remember(playlistId) { ... }`, which works, but if any other call site calls without remember, it'll spawn duplicate `stateIn` collectors (each holds the upstream Flow active for the VM's lifetime — leaks). Also: each navigation into the same playlist creates a new collector that the previous remember has already abandoned, but Lazily means it stays alive forever in `viewModelScope`. Across many re-entries, you accumulate orphan collectors per playlist.
**Fix:** Cache by playlistId in a `MutableMap<Long, StateFlow<List<PlaylistSong>>>` inside the VM, or just return the cold Flow and let the screen `collectAsStateWithLifecycle()` it directly (lifecycle-scoped, auto-disposed).
**Effort:** 15 min.

---

### 17. 🟡 `PlaybackManager` position ticker writes `_uiState.update { copy(durationMs = duration) }` every 500ms when duration first becomes known
**File:** `app/src/main/java/com/migsmusic/playback/PlaybackManager.kt:213-215`
**Issue:** The ticker checks `duration != _uiState.value.durationMs` before updating, but when ExoPlayer reports duration as one of several intermediate values (–9223372036854775807L → real duration in steps), the ticker thrashes the StateFlow several times per song. Every update re-renders the player route's slider value range (#9). Net effect: brief jank at the start of every track.
**Fix:** Only accept duration once (after `Player.STATE_READY`); subsequent updates should require a >100ms delta to filter player rounding.
**Effort:** 15 min.

---

### 18. 🟢 Several small Compose hot-path quibbles
- `MigsMusicCommon.AlbumArtImage:138` reads `MaterialTheme.colorScheme.surfaceContainerHighest` on every recomposition for the placeholder Box — fine, but `error = ColorPainter(MaterialTheme...)` allocates a new ColorPainter on each call. `remember(scheme) { ColorPainter(scheme) }`.
- `MigsMusicSongsScreen.SongRow:251` opens a `Box` wrapper purely to host the DropdownMenu anchor; could fold into the ListRow if we moved menu state up — minor.
- `MigsMusicQueueScreen:64-68` recomputes `headerCount/historyCount/currentCount` on every recomposition; `remember(state.history.size, state.currentSong, state.upcoming.size) {...}` would skip recomputing on position-only updates. Tiny.
- `MigsMusicCommon.SortMenu:172`'s DropdownMenu emits `DropdownMenuItem` per entry per recomposition. Switching to `key(option)` lambdas would help in long lists; for our short enum lists it's negligible.
- `MigsMusicPlaylistsScreen:478-489` recomputes the sorted list inside `remember(songsRaw, contentSort)` — correct, but `songsRaw` is a `List` so equality is structural; if the upstream re-emits an equal list (which Room's flow can do during invalidation churn), we re-sort. `derivedStateOf` would be safer.

**Effort:** 30 min total to apply all five.

---

### 19. 🟢 `LibraryViewModel.sortSongs` runs O(N log N) on every search keystroke + sort change, on Default dispatcher
**File:** `app/src/main/java/com/migsmusic/ui/LibraryViewModel.kt:142-165`
**Issue:** Sorting 5k-10k songs in memory after every search keystroke (post 150ms debounce) is fine on Default — but `compareBy { it.title.lowercase() }` allocates a new lowercase string per row per comparison, and sorts call comparators O(N log N) times. For 10k songs that's ~130k allocations per sort. `sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })` avoids the allocation.
**Fix:** Replace `.lowercase()` comparators with `String.CASE_INSENSITIVE_ORDER`-based ones. Trivial.
**Effort:** 15 min.

---

### 20. 🟢 `MigsMusicApp.kt` has `java.net.URLEncoder.encode(...)` inlined in seven places
**File:** `app/src/main/java/com/migsmusic/ui/MigsMusicApp.kt:215, 219, 245, 281, 286, 296, 318, 326, 366, 372`
**Issue:** Mostly cosmetic — but the same `URLEncoder.encode(x, "UTF-8")` is repeated identically. There's already `encodedKey()`/`encodedName()`/`encodedPath()` helpers in `MigsMusicCommon.kt` for one-shot uses; the album-key construction (`"$album|||$artist"`) lives in MigsMusicApp.kt directly six times. One helper `fun navAlbumKey(album: String, artist: String): String` would dedupe.
**Fix:** Add the helper, replace the call sites.
**Effort:** 15 min.

---

### 21. 🟢 `SongDao.observeAllSongs()` returns SELECT *, but search bar's flatMapLatest also pulls full rows — only title/artist/album/folderName are needed for the search predicate
**File:** `app/src/main/java/com/migsmusic/data/local/dao/SongDao.kt:109-119`
**Issue:** Same point as #12: not much we can do about the leading-wildcard, but if the search hot path returned just the projection we render in `SongRow` (id, title, artist, album, albumArtUri), per-emission marshalling cost would drop. Not urgent at current library sizes.
**Effort:** 30 min — only worth doing once #12 lands.

---

### 22. 🟢 `MIGRATION_2_3` doesn't use `ColumnInfo.defaultValue`, leaving `originalPosition` nullable in schema even though new inserts always set it
**File:** `app/src/main/java/com/migsmusic/data/local/AppDatabase.kt:39-45`, `app/src/main/java/com/migsmusic/data/local/entity/PlaylistSongEntity.kt:41`
**Issue:** PlaylistSongEntity declares `originalPosition: Int? = null` "nullable for forward compat with rows from before the v3 migration". But the migration backfilled every existing row, and every code path now writes a non-null value. Keeping the field nullable means every reader has to handle nullability that can't actually occur. Minor cleanup; would require a v4→v5 migration to make the column NOT NULL.
**Effort:** half-day if you actually do the migration; skip otherwise.

---

### 23. 🟢 `accessibility`: `IconButton(onClick = ...) { Icon(..., contentDescription = ...) }` is the right pattern but `IconButton` itself has no `Modifier.semantics` extension — TalkBack reads icon descriptions but doesn't announce "button". Compose's IconButton does set `Role.Button` automatically, so this is fine; but `SmallActionButton` / `TextButton` callers in the queue screen omit explicit role, which is OK because TextButton announces as button. The folder breadcrumb segments at MigsMusicFoldersScreen.kt:250 are clickable Text with no Role.Button — they read as static text. Wrap in `Modifier.semantics { role = Role.Button }`.
**Effort:** 15 min.

---

### 24. 🟢 `M3uParser`'s BOM strip uses a literal U+FEFF char in source — works but invisible to readers
**File:** `app/src/main/java/com/migsmusic/playlistimport/M3uParser.kt:35`
**Issue:** `content.trimStart('﻿')` would be more readable than the literal character. Pure aesthetics.
**Effort:** 2 min.

---

### 25. 🟢 `workspace/` directory has 44 smoke-run logs accumulated; not in `.gitignore`?
**Issue:** `workspace/smoke-run-*.log` files are tracked-on-disk noise. If they're accidentally getting committed (commits 46ae362..0c67ebf don't show them, so probably not), they'll bloat the repo over time.
**Fix:** Confirm `workspace/smoke-run-*.log` is in `.gitignore`; if not, add it. (Out of scope of code audit but worth a glance.)

---

## Tests / load-bearing coverage

The unit test inventory (`QueueEngineTest`, `ConsecutiveErrorTrackerTest`, `M3uMatcherTest`, `M3uParserTest`, `PlaybackSessionRepositoryTest`) covers the pure-logic surfaces well. Notable gaps that would catch real regressions:

- **`tryIncrementalSync` has no test.** It's complex enough that a regression would silently fall back to full rebuild without anyone noticing — exactly the case where a perf bug hides for months. Add unit tests for: equal queues (return true, no mutation), single insert at end / middle / before-current (last must return false), single removal of current (false), single removal elsewhere (true), single move skipping current (true), move to/from current (false), unrelated multi-diff (false).
- **`upsertSyncedPlaylist` has no test.** The replace-vs-create branching is the heart of the sync feature; it deserves a Room-backed test asserting (1) same name, syncedFromMac=true → contents replaced, id stable; (2) same name, syncedFromMac=false → new row, original untouched; (3) reorder-then-resync → contents reset to fresh order.
- **`autoImportSingleFile` end-to-end has no test.** A small fake ContentResolver + DocumentFile would let us assert the delete-on-success behavior, the empty-match skip, and the parse-failure path.

---

## Known-and-parked (already in `workspace/FOLLOWUPS.md`)

These were checked and are NOT re-flagged above:

- Stable song IDs replacing MediaStore `_ID`.
- "MIGS Music" appearing twice in app top.
- The whole-UI-rework note.
- Mini-player activating on playlist navigation.
- Mini-player not stopping when its source playlist is deleted.

---

## Top 5 wins (highest ROI to ship first)

1. **#3 + #1: Cache-key gate on `refreshAvailableM3uFiles`.** Eliminates repeated SAF crawls + library snapshots on every Playlists-tab visit. ~15 min, large battery + perceived-perf improvement, especially on devices with deep Music folders.
2. **#13: Runtime POST_NOTIFICATIONS request on Android 13+.** First-launch UX bug — playback notifications silently drop. ~30 min, clear correctness fix on every modern device.
3. **#2: Push subfolder discovery to SQL.** Eliminates a real perf cliff at 10k+ songs in the Folders tab. ~1 hour, biggest single payoff for power users.
4. **#11: Wrap `scanDevice`'s chunked upserts in a single transaction.** ~15 min, makes cold-start scan visibly faster on large libraries (and quieter on disk).
5. **#14: Surface auto-import failures via snackbar/log.** ~30 min, vital for debugging the sync flow when something goes wrong on the Mac side.
