# Claude Code config — migs-music

## Workflow

**Commit + push tested work freely. One logical change per commit. Don't ask first.**

After every tested logical change (a feature, a bug fix, a refactor):

1. Run `./gradlew :app:ktlintCheck :app:testDebugUnitTest`. Fix anything that's red.
2. Stage just the files belonging to that one logical change.
3. `git commit` with a clear message that explains the *why*.
4. `git push origin main`.

**Don't bundle.** A UX polish + a data-loss fix + a docs update should be three commits, not one. If a batch of unrelated work happens to land together, split it before pushing.

**Don't bypass the pre-commit hook.** It runs `ktlintCheck` against staged Kotlin files. If it fails, fix the lint issue and re-stage — never pass `--no-verify`.

**Push is the natural conclusion of "tested and works locally"** — not a step that needs a separate prompt from the user. The user has explicitly authorised direct push to `main`, and slowing the loop down with confirmations adds nothing.

**Hold off only when**:

- Code is genuinely experimental / not yet tested.
- The change is destructive (force-push, branch deletion, history rewrite).
- The user has flagged the work as draft.

If something fails after a push (e.g. a smoke run finds a regression), patch with a follow-up commit. Don't try to amend or force-push to clean up.

## Project conventions

- **Comments only when the *why* is non-obvious.** Don't restate code; don't reference issue numbers; don't add "added for X feature" notes — those belong in the PR / commit message.
- **Trailing commas** on multi-line argument lists (ktlint enforces).
- **Compose composables: PascalCase.** This is intentional — `.editorconfig` disables ktlint's default `function-naming` rule for that reason.
- **Backing properties: `_uiState` / `uiState`** pattern from the Kotlin coroutines docs.
- **No mocks for the database** — tests hit real Room. Confidence in migrations beats test speed.
- **Schema changes go through migrations**, not destructive fallback. See `MIGRATION_2_3` in `AppDatabase.kt` for the pattern.

## Builds: two apps, two processes

### Release builds — GitHub Actions only

- **Release APKs/AABs are built and signed ONLY by the `Release` workflow**
  (`.github/workflows/release.yml`), triggered by a `vX.Y.Z` tag. Never build or sign a
  release on a laptop, never create `keystore.properties` in the repo root (its absence is
  what keeps a local `assembleRelease` unsigned). The key lives in repo secrets + the
  user's password manager.
- The one command, from any computer with `gh` logged in and the phone on adb:
  `scripts/release-to-phone.sh <version>` (skill: `.claude/skills/release-to-phone`).
  It is idempotent on the version: if `v<version>` is already released it just installs
  that APK; if it's tagged but still building it waits; otherwise it bumps
  `versionCode`/`versionName` on latest `main`, commits, tags, pushes, waits for CI, then
  downloads and `adb install -r`s the APK.
- Installs are **in place** (same key, higher `versionCode`): the phone keeps playlists,
  loves and settings. No re-sync needed after an update.
- Package `com.migsmusic`, label "migs music". This is what the Mac sync app targets.
- Broken release? Fix forward and cut the next version. Never move or delete a tag.

### Debug builds — local, a separate app

- `./gradlew :app:installDebug` builds the working tree and installs it as
  **`com.migsmusic.debug`, labelled "migs music dev"**, side by side with the release. It
  has its own data, its own sync inbox (`/sdcard/Android/media/com.migsmusic.debug/sync`),
  and can never replace or wipe the release install.
- Signed with the **checked-in `debug.keystore`** (password `android`, alias
  `androiddebugkey`), not the machine's `~/.android` one, so `installDebug` from any
  computer updates the debug app in place (or adds it if absent). That key can only sign
  the debug app; the release key never leaves CI secrets.
- First launch on the OnePlus: grant music access by hand in the app (ask the user to tap
  Allow). `adb shell pm grant` and the test harness's permission rule are both refused by
  this ROM. The inbox is only imported when the AUTO_IMPORT broadcast arrives, so if you
  synced before the permission was granted, run `scripts/sync-debug-playlists.sh` again
  afterwards.
- Needs the sibling repo at `~/projects/migs-music-mac` and the MigsMusicMac app installed
  (its bundled `migs-tracks` helper reads the Music library).
- Playlists: the Mac menu-bar app only syncs to the release package, so run
  `scripts/sync-debug-playlists.sh` — it replays the Mac app's sync (manifest → bundled
  sync script → AUTO_IMPORT broadcast) against the debug package, using the playlists
  ticked in the Mac app, and never deletes audio.
- Instrumented tests (`scripts/device-smoke-test.sh`, or `am instrument` against
  `com.migsmusic.debug.test`) run **only** against the debug app. They wipe its playlists
  in setup; re-run `scripts/sync-debug-playlists.sh` afterwards.

## Useful commands

```bash
./gradlew :app:compileDebugKotlin                    # quickest sanity check
./gradlew :app:testDebugUnitTest                     # unit tests (no device)
./gradlew :app:installDebug                          # install onto a connected device
scripts/device-smoke-test.sh                         # full instrumented suite (debug app only)
scripts/sync-debug-playlists.sh                      # Mac-app playlists → the debug build
scripts/release-to-phone.sh 0.3.0                    # tag, CI-signed build, install release
./gradlew :app:ktlintCheck                           # lint
./gradlew :app:ktlintFormat                          # auto-fix lint
```

## Sibling repo

Mac-side tooling lives in [`~/projects/migs-music-mac`](../migs-music-mac), separate git repo. Same workflow rules apply there.
