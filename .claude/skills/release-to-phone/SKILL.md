---
name: release-to-phone
description: Get a release version onto the plugged-in phone — installs it if GitHub already built it, otherwise cuts it (tag → CI-signed build → download → adb install). Use when asked to release, ship, tag a version, update the phone, or put a release build on the phone.
---

# Release to phone

One script, idempotent on the version; run it rather than reproducing the steps:

```bash
scripts/release-to-phone.sh <version> [--replace] [--no-install]
```

- `v<version>` already released on GitHub → downloads `migs-music-<version>.apk` from that
  Release into `build/releases/` and `adb install -r`s it. Use this to update a phone from
  any computer.
- Tagged but the Release workflow hasn't finished → waits for it, then installs.
- Neither → bumps `versionCode`/`versionName` on latest `main`, runs lint + unit tests,
  commits, tags, pushes, waits for `.github/workflows/release.yml` to build and sign, then
  downloads and installs.

Signing happens on GitHub from repo secrets. Nothing is built or signed locally, so this
works from any machine with `gh` logged in. Installs are in place: the phone keeps its
playlists, loves and settings.

## Before running

1. `gh auth status` must succeed. If not, ask the user to run `! gh auth login`.
2. Cutting a new version needs a clean tree on `main`. Installing an existing version
   doesn't.
3. Pick the version: `gh release list --limit 3` shows the latest; bump patch for fixes,
   minor for features. Never reuse a tag.
4. Phone plugged in, USB mode "File transfer", USB debugging accepted (`adb devices` must
   list it as `device`). Without a phone, use `--no-install` and hand over the APK.

## Exit codes and what to do

- **3, signature mismatch**: the phone has a build signed with another key (a debug-signed
  `com.migsmusic` from before CI signing). Only pass `--replace` after telling the user it
  wipes that app's data (playlists, loves, settings) and they agree; they re-sync from the
  Mac app afterwards. Debug builds are a separate app (`com.migsmusic.debug`) and never
  collide with this.
- **3, version downgrade**: the phone already has a newer version. Pick a higher one.
- **2, no phone**: the APK is downloaded; rerun with the phone attached.
- **1, workflow failed**: the script prints the failed job log. Usual causes: missing
  repo secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), or
  lint/tests. Fix, commit, and re-run with the **next** version; never move or delete a tag.

## Rules

- **Release APKs come only from this workflow.** Never run `assembleRelease` /
  `bundleRelease` locally as a release and never create `keystore.properties` in the repo
  root; local release builds are deliberately unsigned.
- The tag also uploads the `.aab` to the Play Internal testing track once
  `PLAY_SERVICE_ACCOUNT_JSON` is configured; see `RELEASING.md`.
