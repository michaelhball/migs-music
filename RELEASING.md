# Releasing migs music (Android)

End-to-end process for cutting a new version. **Release builds are produced only by the
`Release` GitHub Actions workflow** (`.github/workflows/release.yml`) on a `vX.Y.Z` tag —
never on a laptop. Local machines build debug APKs (`com.migsmusic.debug`) only.

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

### 2. Put the keystore into GitHub Actions secrets

Settings → Secrets and variables → Actions on the repo:

- `KEYSTORE_BASE64` — `base64 -i ~/migs-music-release.jks | pbcopy`
- `KEYSTORE_PASSWORD`, `KEY_PASSWORD` — the password you picked
- `KEY_ALIAS` — `migs-music`

With `gh` logged in, from the repo root:

```bash
PW=<password>
base64 -i ~/migs-music-release.jks | gh secret set KEYSTORE_BASE64
printf '%s' "$PW" | gh secret set KEYSTORE_PASSWORD
printf '%s' migs-music | gh secret set KEY_ALIAS
printf '%s' "$PW" | gh secret set KEY_PASSWORD
```

Keep the `.jks` and the password in a password manager. Do **not** create a
`keystore.properties` in the repo root: the Gradle signing config only attaches when that
file exists, and its absence is what guarantees a local `assembleRelease` is unsigned and
can't be mistaken for a real release.

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
scripts/release-to-phone.sh 0.3.0              # bump, commit, tag v0.3.0, push, wait for CI,
                                               # download migs-music-0.3.0.apk, adb install -r
scripts/release-to-phone.sh 0.3.0 --no-install # same, but stop after the download
```

The workflow signs with the secrets above and attaches `migs-music-<version>.apk` and
`.aab` to the GitHub Release for the tag. Updates install in place on the phone (same key,
higher `versionCode`) — no uninstall, no data loss. The Claude Code skill
`.claude/skills/release-to-phone/SKILL.md` wraps the same script.

If a release is broken, fix forward: commit, then cut the **next** version. Never move or
delete a tag.

## What the tag workflow does

`.github/workflows/release.yml` builds a signed `.apk` + `.aab` when a `vX.Y.Z` tag is pushed and attaches both to the GitHub Release for that tag. Uploading the `.aab` to the Play Console is manual unless the Play step below is configured.

Required GitHub Secrets (Settings → Secrets and variables → Actions):

- `KEYSTORE_BASE64` — base64-encoded contents of your `.jks`. Generate with `base64 -i ~/migs-music-release.jks | pbcopy` and paste.
- `KEYSTORE_PASSWORD` — keystore password.
- `KEY_ALIAS` — alias inside the keystore (`migs-music` if you followed the keytool example).
- `KEY_PASSWORD` — key password (typically same as keystore password if you used `keytool` defaults).


## Play Store auto-upload (Internal track)

The release workflow uploads the AAB to the Play Store **Internal testing** track automatically when a `v*` tag is pushed — but only after the one-time setup below. Until that's done, the workflow runs without the Play step (silently skipped) and you still get a GitHub Release with the APK + AAB attached.

### One-time: create the Play Console listing

1. Sign up at https://play.google.com/console — $25 one-time.
2. **Create app** → name "migs music", default English, App, Free, accept the declarations.
3. Set the **Privacy policy URL** in Policy → App content: `https://michaelhball.github.io/migs-music/privacy.html`.
4. Fill the **Data safety** form: declare "No data collected" everywhere. The privacy policy backs that up.
5. Complete the **Content rating** questionnaire (it's a music player; everything is "no").
6. Add target audience, news/COVID/etc declarations.
7. Set up an **Internal testing** track (Test → Internal testing → Create new release). Add yourself as a tester via your Google account email.
8. Use **Play App Signing** when prompted — Google holds the master key, your release-keystore.jks is the *upload* key. Lose the upload key → Google rotates for free. Don't sign with your own key directly; you'd lock yourself out.

No first AAB upload needed at this stage — the workflow handles that.

### One-time: create the service account

Google Cloud Console (https://console.cloud.google.com) → the project linked to your Play Console (Play Console → Settings → API access shows it).

1. **IAM & Admin → Service Accounts → Create**. Name it something like `play-deploy`. No roles needed at the GCP project level.
2. Open the service account → **Keys → Add key → Create new → JSON**. Download the JSON file. Treat it like a password.
3. **In the Play Console** (not Cloud Console), go to **Settings → API access**. Find the service account's email address (looks like `play-deploy@<project>.iam.gserviceaccount.com`). Click **Grant access**, set the app permission to **Release manager** (or just Internal-track-upload if Google adds finer granularity), confirm.

### One-time: add the secret to GitHub

```bash
gh secret set PLAY_SERVICE_ACCOUNT_JSON < ~/Downloads/play-deploy-*.json
# Then move/back up + delete the local copy. The JSON is now in
# GitHub's secret store and shouldn't sit on disk in plaintext.
```

After that, pushing a `v*` tag triggers the workflow which uploads the AAB to the Internal track. Internal testers (you + anyone you invite) see the new version in their Play Store within a few minutes.

### Each release

Same as before — the workflow just does more:

```bash
scripts/release-to-phone.sh 0.2.0
```

Then watch [Actions](https://github.com/michaelhball/migs-music/actions) for the build. When it goes green:

- GitHub Release page has the .apk and .aab attached.
- Play Console Internal track has the new version, marked "completed", visible to your testers.

### Promotion to closed / open / production

Stays manual on purpose. Open the Play Console → Internal testing → Promote release → pick the next track. Closed and Open testing tracks may take a Google review (hours to a day); Production takes 1-3 days the first time, often hours after.

## Versioning convention

- `versionName` is the user-facing string ("0.2.0"). Follows semver: bump major on breaking changes, minor on features, patch on fixes.
- `versionCode` is a monotonic integer. Play Store rejects uploads with the same versionCode as a prior version, so this MUST always go up. `scripts/release-to-phone.sh` bumps it by 1 each time.

If you ever need to push a hotfix between releases, bump versionCode but keep versionName the same patch level (or bump to 0.2.0.1, the dot-something convention, by editing manually).

## Pre-release checklist

Before tagging:

- [ ] `./gradlew :app:ktlintCheck :app:testDebugUnitTest` — green.
- [ ] `scripts/device-smoke-test.sh` — green on a real device.
- [ ] Manually exercise the Mac sync → phone import flow end-to-end on the dev device.
- [ ] Run `scripts/release-to-phone.sh <version>`. (Bumps version, commits, tags, pushes.) CI takes over: builds APK + AAB, attaches to GitHub Release, uploads AAB to Play Internal track (if `PLAY_SERVICE_ACCOUNT_JSON` is configured).
- [ ] Smoke-test on the Internal track for at least a few hours.
- [ ] Promote in Play Console (manual click) when satisfied.
