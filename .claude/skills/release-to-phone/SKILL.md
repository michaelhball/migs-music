---
name: release-to-phone
description: Cut a tagged release built and signed by GitHub Actions, download the APK, and install it on the plugged-in phone. Use when asked to release, ship, tag a version, or put a release build on the phone.
---

# Release to phone

Everything is driven by one script; run it rather than reproducing the steps:

```bash
scripts/release-to-phone.sh <version> [--replace] [--no-install] [--install-only]
```

It bumps `versionCode`/`versionName`, runs lint + unit tests, commits, tags `v<version>`,
pushes, waits for the **Release** workflow (`.github/workflows/release.yml`) to build the
signed APK + AAB, downloads `migs-music-<version>.apk` into `build/releases/`, and installs
it with `adb install -r`. Signing happens on GitHub from repo secrets, so no local keystore
is needed and this works from any computer.

## Before running

1. Tree must be clean and on `main`. Commit or stash first.
2. `gh auth status` must succeed. If not, ask the user to run `! gh auth login`.
3. Pick the version: `git tag --sort=-v:refname | head -1` shows the latest; bump patch
   for fixes, minor for features. Never reuse a tag.
4. Phone plugged in, USB mode "File transfer", USB debugging accepted (`adb devices`
   must list it as `device`). Without a phone, use `--no-install` and hand over the APK.

## Installing a release that already exists

If the tag is already pushed and built (e.g. installing onto a second phone, or after a
failed install), pass `--install-only`: it skips the bump/tag/wait and just downloads
that version's APK from the GitHub Release and installs it.

## Signature mismatch

A phone that has a **debug** build installed cannot be updated in place by a release
build (different signing key). The script exits with code 3 and says so. Only pass
`--replace` after telling the user it wipes the app's data on the phone (playlists,
loves, settings) and they agree; they re-sync from the Mac app afterwards. Once a release
build is on the phone, keep installing release builds — debug installs would hit the
same mismatch in the other direction.

## If the workflow fails

The script prints the failed job log. Common causes: missing repo secrets
(`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` under Settings →
Secrets and variables → Actions), or lint/test failures that CI catches. Fix, commit,
and re-run with the **next** version number; don't move or delete the tag.

## Rules

- **Release APKs come only from this workflow.** Never run `assembleRelease` /
  `bundleRelease` locally as a release and never create `keystore.properties` in the repo
  root; local release builds are deliberately unsigned. The key lives in GitHub secrets
  and the user's password manager.
- The tag pushed here also uploads the `.aab` to the Play Internal testing track once
  `PLAY_SERVICE_ACCOUNT_JSON` is configured; see `RELEASING.md`.
