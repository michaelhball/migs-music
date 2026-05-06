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

## Future automation

The 4-step flow could collapse into a single command. To wire up later:

- **Google Play Developer API for uploads.** Free. Set up a service account in the Google Cloud Console with the `androidpublisher.applications.upload` scope, download the JSON key, and call the API from `release.sh`. One-shot upload to the Internal track without leaving the terminal.
- **GitHub Actions on tag push.** A workflow that runs `release.sh` and uploads via the API. Keeps your laptop out of the loop entirely. Needs the keystore + service-account JSON as encrypted GitHub secrets.

For now, the manual step keeps you in the loop on every release, which is what you want until the app is mature.

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
