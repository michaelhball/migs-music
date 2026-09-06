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

## Debug vs release on the phone

Debug builds install as a **separate app**: `com.migsmusic.debug`, labelled "migs music dev".
The release install (`com.migsmusic`, signed by CI — see `.claude/skills/release-to-phone`)
is never replaced or wiped by `installDebug` or the smoke script. The Mac sync app only
targets the release package; to get playlists into a debug build run
`scripts/sync-debug-playlists.sh` (replays the Mac app's sync against the debug package,
never deletes audio).

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
