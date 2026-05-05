# M3U Import — Implementation Plan

## Goal

User exports a playlist from Mac Music.app as `.m3u`, drags the audio files + the `.m3u` file to the phone via OpenMTP, then taps "Import from M3U" in migs-music. The app creates a playlist with songs in M3U order, matched against the existing on-device library.

The same audio file can already be referenced by many playlists (`playlist_songs` is a join table); no schema change.

## Workflow (user perspective)

1. Music.app → *File → Library → Export Playlist → Format: M3U*.
2. OpenMTP: drag MP3s into `/Music/` (any folder layout — or keep Music.app's `Artist/Album/Title.mp3` structure). Drop the `.m3u` next to them or anywhere.
3. migs-music: Playlists tab → overflow ⋮ → **Import from M3U file** → SAF picker → result dialog → Create.

OpenMTP already prompts skip-on-collision when re-dragging files, so file-level dedup is essentially free if the user keeps the destination folder consistent.

## Scope

In:
- M3U + Extended M3U parser.
- Library matcher (artist+title fuzzy, basename fallback).
- "Import from M3U file" flow on the Playlists screen (SAF file picker).
- Result dialog showing matched/unmatched count + unmatched list, editable playlist name.

Out (defer until needed):
- Auto-detection of M3U files in `/Music/` via ContentObserver.
- Mac-side helper / push-from-Mac.
- M3U export from migs-music.
- Manual remap of unmatched entries.
- Merge into existing playlist (always creates new; user renames if desired).

## File-by-file changes

| File | Change |
|---|---|
| `app/src/main/java/com/migsmusic/playback/M3uParser.kt` | NEW. Pure parser: `parseM3u(content: String): List<M3uEntry>`. No Android deps. |
| `app/src/main/java/com/migsmusic/playback/M3uMatcher.kt` | NEW. Pure matcher: `matchEntries(entries, songs): MatchResult`. No Android deps. |
| `app/src/main/java/com/migsmusic/data/repository/PlaylistRepository.kt` | Add `importMatchedSongs(name: String, songIds: List<Long>): Long`. |
| `app/src/main/java/com/migsmusic/ui/PlaylistsViewModel.kt` | Add `importM3u(uri, defaultName)` (reads file via ContentResolver, runs parser+matcher, returns staging state). Add `commitImport(name, songIds)`. |
| `app/src/main/java/com/migsmusic/ui/MigsMusicPlaylistsScreen.kt` | Add overflow menu → "Import from M3U file" entry; SAF launcher; result dialog. |
| `app/src/main/java/com/migsmusic/ui/UiTestTags.kt` | New tags for menu, dialog, dialog buttons. |
| `app/src/test/java/com/migsmusic/M3uParserTest.kt` | NEW. Cover all parser paths. |
| `app/src/test/java/com/migsmusic/M3uMatcherTest.kt` | NEW. Cover all matching paths. |
| `app/src/androidTest/java/com/migsmusic/M3uImportTest.kt` | NEW. End-to-end: write a fake M3U via `MediaStore.Downloads`, click Import, pick the file via UI Automator, verify playlist appears. (May skip the SAF tap if it's flaky and instead test via direct repo call with a content URI.) |

## Data shapes

```kotlin
data class M3uEntry(
    val rawPath: String,          // exact path token from the M3U
    val artist: String? = null,   // from #EXTINF if present
    val title: String? = null,    // from #EXTINF if present
    val durationSec: Int? = null, // from #EXTINF if present
)

sealed interface MatchResult {
    val matched: List<MatchedSong>      // SongEntity, in M3U order
    val unmatched: List<M3uEntry>       // entries we couldn't resolve, in M3U order
}

data class MatchedSong(val entry: M3uEntry, val song: SongEntity)
```

## Parser behaviour

- Splits by line endings (`\r?\n`), trims each.
- Skips empty lines.
- Comment lines starting with `#` are ignored unless they begin with `#EXTINF:`.
  - `#EXTINF:duration,Artist - Title` — parse duration as int (seconds; -1 means unknown). Split on first ` - ` (with spaces) to get artist/title; if no separator, the whole tail is the title.
- The next non-comment line is treated as the path; combined with any pending EXTINF metadata into one `M3uEntry`.
- UTF-8 by default. If decoding fails, fall back to ISO-8859-1 (handles old iTunes exports on some locales).
- Trim BOM if present at start of file.

## Matcher behaviour

For each `M3uEntry`, in order:

1. **Exact** — case-insensitive `(artist, title)` match against `SongEntity.artist + .title`. Ignores leading/trailing whitespace.
2. **Normalized** — lower-cased, strip diacritics, strip parenthesised qualifiers (`(feat. X)`, `(Remastered 2019)`, `[Live]`), collapse internal whitespace, normalize smart quotes. Re-match.
3. **Basename fallback** — derive `basenameWithoutExt(rawPath)` (handles both `/` and `\` separators), match case-insensitively against song titles or against the actual filename in `SongEntity.contentUri` if we can extract it. Useful when EXTINF is missing.
4. If still unresolved → goes to `unmatched`.

Strict by default; no false positives. We tell the user what's missing rather than guess.

When multiple library songs match the same `M3uEntry` (e.g. same title by different artists with no EXTINF artist info), prefer:
- One whose artist matches (if EXTINF artist provided);
- Else first in alphabetical order (deterministic).

## UI

### Playlists screen header
Existing "+ New Playlist" button stays. Add an overflow ⋮ menu with two items:
- **New playlist** (today's behaviour, moved into the menu so the chrome isn't crowded — or keep "+" and just add ⋮ alongside, TBD on aesthetic during impl).
- **Import from M3U file**.

### Import flow
1. Tap "Import from M3U file" → `ActivityResultContracts.OpenDocument` with `arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "*/*")` so the picker shows audio/playlist files but isn't restrictive enough to hide them on weird MIME setups.
2. ViewModel reads file content via `contentResolver.openInputStream(uri)`. Falls back gracefully on read errors with a snackbar/toast.
3. Parser + matcher runs on `Dispatchers.Default`. The whole library list is loaded once via `libraryRepository.observeAllSongs().first()` for matching.
4. **Result dialog** appears:
   - Title: "Import playlist"
   - Editable `OutlinedTextField` with default = M3U filename without extension.
   - Body: "Found N of M songs in your library."
   - If unmatched > 0, expandable section: "M tracks couldn't be matched" → list of "{artist} - {title}" or filename.
   - Buttons: **Cancel**, **Create Playlist** (disabled if N == 0 or name is blank).
5. On Create: `viewModel.commitImport(name, matchedSongIds)` → repository creates the playlist, adds songs in order. Dialog dismisses; new playlist appears in the list (existing flow).

## Edge cases & decisions

- **Empty M3U**: dialog shows "0 of 0", Create disabled, only Cancel works.
- **All unmatched**: dialog shows "0 of N", Create disabled, user gets a hint.
- **Same playlist name already exists**: just create another with the same name. We don't enforce unique names elsewhere; user can rename or delete.
- **Very large M3U** (10k+ entries): parser is O(N), matcher is O(N × library_size) but library is in-memory; should be sub-second. Will profile if it's slow.
- **Read failure** (URI revoked, file deleted between picker and read): catch IOException, show toast "Couldn't read file", abort.
- **Permission**: SAF gives a one-shot read URI; we don't need any extra permissions.

## Test plan

Unit (drives the bulk of correctness, no device needed):
- Parser: empty file, comments only, plain paths, EXTINF + path, EXTINF without trailing path, malformed EXTINF, BOM, mixed line endings, ISO-8859-1 fallback.
- Matcher: exact match, case-insensitive, feat./remaster stripping, smart-quote normalisation, basename fallback, no match, multiple library matches with artist disambiguation.
- Repository: import creates playlist with correct order; integrates with existing `playlist_songs` table.

Instrumented:
- One end-to-end happy path: stage a tiny M3U + verify a freshly-created playlist matches it.
- Skip the SAF picker UI in the test (flaky cross-version); call ViewModel directly with a `content://` URI we created.

## Estimated size

~600–800 lines of new code (parser + matcher + UI + tests), plus ~50 lines of changes to existing files. About 1–2 sessions of work. Phone testing only needed for the final SAF-flow check.

## What success looks like

- Export a Music.app playlist as M3U.
- Drag the .m3u + audio files to phone via OpenMTP into `/Music/<artist>/<album>/`.
- Tap Import in migs-music, pick the .m3u, accept the default name, tap Create.
- New playlist appears with the correct songs in the correct order.
- Any unmatched titles are reported up-front so the user knows to copy more files.
