# Releasing migs music (Android)

End-to-end process for cutting a new version and getting it onto the Play Store.

## One-time setup

### 1. Generate the release keystore

Once you generate this, **never lose it**. If you lose it, you can never sign updates to the app under the same Play listing.

```bash
keytool -genkeypair -v \
    -keystore ~/migs-music-release.jks \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -alias migs-music
```

Pick a strong password. Store the `.jks` file somewhere outside this repo (a password manager or secured cloud backup).

### 2. Create `keystore.properties` in the repo root

Gitignored. Tells Gradle where to find the keystore + how to use it.

```properties
storeFile=/Users/you/migs-music-release.jks
storePassword=<your-password>
keyAlias=migs-music
keyPassword=<your-password>
```

The release build picks this up automatically; debug builds are unaffected.

### 3. Set up Play App Signing (optional but recommended)

When you create the Play Console listing, opt into **Play App Signing**. Google manages the actual signing key; you keep an *upload* key (the one above). If you ever lose the upload key, Google can rotate it for you. If you sign updates yourself without Play App Signing, lose the key = lose the app.

### 4. Play Console listing

- Sign up at https://play.google.com/console — $25 one-time.
- Create the app entry (name, default language, app or game, free or paid).
- Fill the App access form, the Privacy policy URL (mandatory because we declare `READ_MEDIA_AUDIO`), and the Data safety form (declare what's collected — for migs music: nothing remote, just on-device music access + notifications).
- Add screenshots: 2–8 phone, plus a 1024×500 feature graphic.
- Set up Internal testing track, add yourself as a tester.

## Each release

```bash
# 1. From the repo root, on a clean main branch:
./release.sh 0.2.0 --tag

# This:
#   - bumps versionCode (monotonic integer, required by Play Store)
#   - bumps versionName to 0.2.0
#   - runs lint + unit tests
#   - builds a signed Android App Bundle at:
#       app/build/outputs/bundle/release/app-release.aab
#   - commits the version bump + tags v0.2.0

# 2. Push the tag and the bump commit:
git push && git push --tags

# 3. Upload to Play Console:
#    Internal testing → New release → upload app/build/outputs/bundle/release/app-release.aab
#    Add release notes, save, review, roll out.

# 4. Smoke-test on the internal testing track. Once it looks good, promote to
#    closed beta → open beta → production. Each track has its own rollout %.
```

## Automated release on tag push (optional)

There's a `.github/workflows/release.yml` that builds a signed `.aab` automatically when you push a `vX.Y.Z` tag. It attaches the `.aab` to the GitHub Release page for that tag — uploading from there to the Play Console is still manual (the Play Developer API path is a follow-up).

Required GitHub Secrets (Settings → Secrets and variables → Actions):

- `KEYSTORE_BASE64` — base64-encoded contents of your `.jks`. Generate with `base64 -i ~/migs-music-release.jks | pbcopy` and paste.
- `KEYSTORE_PASSWORD` — keystore password.
- `KEY_ALIAS` — alias inside the keystore (`migs-music` if you followed the keytool example).
- `KEY_PASSWORD` — key password (typically same as keystore password if you used `keytool` defaults).

Once configured, `./release.sh 0.2.0 --tag && git push --tags` triggers the workflow.

## Future automation (still TODO)

- **Google Play Developer API for uploads.** Free. Set up a service account in the Google Cloud Console with the `androidpublisher.applications.upload` scope, download the JSON key, and call the API from `release.sh` or the workflow. One-shot upload to the Internal track without manual Play Console clicks.

For now, the manual upload step keeps you in the loop on every release, which is what you want until the app is mature.

## Versioning convention

- `versionName` is the user-facing string ("0.2.0"). Follows semver: bump major on breaking changes, minor on features, patch on fixes.
- `versionCode` is a monotonic integer. Play Store rejects uploads with the same versionCode as a prior version, so this MUST always go up. `release.sh` bumps it by 1 each time.

If you ever need to push a hotfix between releases, bump versionCode but keep versionName the same patch level (or bump to 0.2.0.1, the dot-something convention, by editing manually).

## Pre-release checklist

Before tagging:

- [ ] `./gradlew :app:ktlintCheck :app:testDebugUnitTest` — green.
- [ ] `scripts/device-smoke-test.sh` — green on a real device.
- [ ] Manually exercise the Mac sync → phone import flow end-to-end on the dev device.
- [ ] Bump the version, run `./release.sh <version> --tag`.
- [ ] Push tags + commit.
- [ ] Upload to Play Console Internal testing.
- [ ] Test on the internal testing track for at least a few hours / a workday.
- [ ] Promote.
