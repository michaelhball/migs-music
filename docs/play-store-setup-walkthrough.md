---
title: Play Console setup walkthrough — migs music
---

# Play Console setup walkthrough

A step-by-step "click here, paste this" guide for getting migs music onto the Play Store Internal testing track. Follows the layout of the Play Console as of 2026-05-09; if Google rearranges things slightly, the section names should still be findable.

**Time budget: ~1 hour total** (15 min for the listing, 15 min for the policies/declarations, 15 min for service account / API access, 15 min for buffer).

---

## Prerequisites (5 min)

- Sign in to https://play.google.com/console with the Google account you'll publish under.
- Pay the $25 one-time developer fee (Settings → Account details if not already done).
- Have these files handy locally:
  - `workspace/play-store-icon-512.png` — the 512×512 app icon
  - `docs/play-store-listing.md` — copy-paste source for descriptions (also reproduced inline below)
  - Phone screenshots in `workspace/play-store-screenshots/` (will be captured separately)
  - Feature graphic — TODO, can be added later for Internal track but mandatory for Production

---

## Step 1: Create the app

1. Top-left dropdown → **All apps** → **Create app** (top-right).
2. Fill the form:

| Field | Value |
| --- | --- |
| App name | `migs music` |
| Default language | `English – en-US` |
| App or game | `App` |
| Free or paid | `Free` |
| Declaration: developer guidelines | ✅ tick |
| Declaration: US export laws | ✅ tick |

3. Click **Create app**.

You're now in the app's dashboard. The left rail shows the setup tasks; we'll work through them top to bottom.

---

## Step 2: Main store listing

Left rail → **Grow** → **Store listing** → **Main store listing**.

### App details

| Field | Value |
| --- | --- |
| App name | `migs music` |
| Short description (80 char) | `A fast, private, local music player. Sync playlists from your Mac.` |
| Full description (4000 char) | (paste the block below) |

#### Full description — paste this verbatim

```
migs music is a music player for your own files. No streaming, no cloud, no
account, no ads. It plays what's on your phone and gets out of the way.

Pair it with the companion Mac app to sync Apple Music playlists to your
phone over USB in seconds. Tick the playlists you want, click Sync, and the
audio + metadata land on your phone exactly mirrored — re-syncing is
incremental and quick.

Highlights

  • Plays everything Android indexes — MP3, FLAC, M4A, OPUS, OGG.
  • Browse by song, album, artist, folder, playlist, and "Loves" (your
    hearted songs).
  • Apple-Music-style segmented queue: History / Current / Up Next / Later
    / Remaining context. Drag to reorder Up Next; swipe to remove.
  • Heart any song to add it to your local-only "Loves" virtual playlist.
    Survives every sync, every reinstall.
  • Smooth crossfade between tracks (configurable).
  • Background playback, lock-screen controls with album art, audio focus
    handover, Bluetooth support.
  • Resumes where you left off — queue, position, shuffle, repeat all
    survive cold starts.
  • Sync from Apple Music on your Mac via the companion app — playlists
    arrive in seconds, only what changed gets pushed.
  • Auto-rescan when files appear or change.

Privacy

  • No internet permission. The app physically cannot phone home.
  • No analytics, no crash reporting, no tracking.
  • No advertising identifiers, no third-party SDKs.
  • Music stays on your device. The Mac sync only happens over USB when
    you click Sync.
  • Full privacy policy: https://michaelhball.github.io/migs-music/privacy.html

migs music is open source. Source: https://github.com/michaelhball/migs-music
```

### Graphics

| Asset | What to upload |
| --- | --- |
| App icon | `workspace/play-store-icon-512.png` |
| Feature graphic | **TODO — add later.** Internal track may let you proceed without; Production will block. |
| Phone screenshots | `workspace/play-store-screenshots/*.png` (4–5 files, see below) |
| 7-inch tablet | Skip |
| 10-inch tablet | Skip |

Click **Save**.

---

## Step 3: Store settings

Left rail → **Grow** → **Store settings**.

| Field | Value |
| --- | --- |
| App category | `Music & Audio` |
| Tags | Pick 3–5 of: `Music player`, `Local music`, `Audio player`, `Offline music`, `MP3 player` |
| Store listing contact details — Email | `michael.h.s.ball@gmail.com` |
| Store listing contact details — Website | `https://github.com/michaelhball/migs-music` |
| External marketing | Off |

Save.

---

## Step 4: Privacy policy

Left rail → **Policy and programs** → **App content** → **Privacy policy**.

Paste:

```
https://michaelhball.github.io/migs-music/privacy.html
```

Save.

---

## Step 5: App access

Left rail → **Policy and programs** → **App content** → **App access**.

> "All functionality is available without restrictions"

Tick that and save. (We don't have login screens or paywalls; everything is open out of the box.)

---

## Step 6: Ads

Left rail → **Policy and programs** → **App content** → **Ads**.

> "No, my app does not contain ads"

Save.

---

## Step 7: Content rating

Left rail → **Policy and programs** → **App content** → **Content rating**.

1. Email: `michael.h.s.ball@gmail.com`
2. Category: **Utility, Productivity, Communication, or Other**
3. Walk through the questionnaire — every answer is **No** for migs music. It contains no violence, no sex, no profanity, no controlled substances, no gambling, no UGC, no location sharing, no in-app purchases, no PII collection, etc.

Result: rated for everyone (the broadest possible).

Submit.

---

## Step 8: Target audience

Left rail → **Policy and programs** → **App content** → **Target audience**.

| Field | Value |
| --- | --- |
| Target age groups | `18 and over` (simplest; expand later if you want a younger audience) |
| Appeals to children | `No` |

Save.

---

## Step 9: Data safety

Left rail → **Policy and programs** → **App content** → **Data safety**.

This is the longest form. The good news is every answer is "no" or "we don't collect / share":

| Section | Answer |
| --- | --- |
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | **N/A** (we don't transmit data) |
| Do you provide a way for users to request that their data be deleted? | **N/A** (no user data is collected) |

Submit.

---

## Step 10: Government apps / News apps / Health / Financial / COVID-19

For each of these in the **App content** section: just declare **No**. None apply.

Save each.

---

## Step 11: Set up Play App Signing (recommended)

Left rail → **Test and release** → **Setup** → **App integrity** → **App signing**.

Choose **"Use Play App Signing"** when prompted. Google holds the master signing key; you keep the upload key (your existing `release-keystore.jks`). If you ever lose the upload key, Google can rotate it for you for free. If you sign updates yourself without Play App Signing and lose your key — the app is locked out forever.

The first AAB upload will register your upload key fingerprint. Google then serves users the version Google-signed against the master key.

---

## Step 12: Create the Internal testing track

Left rail → **Test and release** → **Testing** → **Internal testing**.

1. Click **Create new release** (top-right).
2. **First release:**
   - You can either upload your local v0.1.0 AAB manually here once, OR you can leave the release empty initially and let the GitHub Actions workflow upload the first build when the v0.1.0 tag is pushed.
   - Recommended: empty the release, save as draft, and let CI handle the upload.
3. **Release name:** `0.1.0` (matches the tag)
4. **Release notes** — paste:

```
v0.1.0 — first release.
- Local music playback with album, artist, folder, playlist, and "Loves" browsing.
- Mac companion app for one-click playlist sync from Apple Music over USB.
- Lock-screen + notification controls with album art.
- Background playback, queue persistence, crossfade.
- No telemetry, no cloud, no ads.
```

5. **Testers** — set up a tester list. Add your own Google account email (the one you'll install the test build under). Optionally add a couple of friends. The Play Store sends them an opt-in link.

6. Save as draft (don't roll out yet — wait for the first AAB to land).

---

## Step 13: Create the service account for CI uploads

Now we wire CI auto-upload, so future releases land on Internal track without manual clicks.

1. Open https://console.cloud.google.com — select the project that's linked to your Play Console (Play Console → Settings → API access shows the project ID; if no project exists yet, the API access page will offer to create one).
2. **IAM & Admin → Service Accounts → Create service account**:
   - Name: `play-deploy`
   - No project-level roles needed
3. Open the new service account → **Keys → Add key → Create new key → JSON** → Download. Treat the file as a private credential.
4. Back in Play Console → **Settings → API access**:
   - Find the service account by its email (looks like `play-deploy@<project-id>.iam.gserviceaccount.com`)
   - Click **Manage Play Console permissions** next to it
   - **App permissions → Add app → migs music**
   - **Account permissions → Release manager**
   - Save.
5. Add the JSON to GitHub Secrets:
   ```bash
   gh secret set PLAY_SERVICE_ACCOUNT_JSON < ~/Downloads/play-deploy-*.json --repo michaelhball/migs-music
   ```
   Then move the JSON to your password manager and delete the local copy. The secret is now in GitHub's secret store.

---

## Step 14: Cut the release

Once everything above is done and the release draft in Internal testing exists:

```bash
cd ~/projects/migs-music
./release.sh 0.1.0 --tag
git push origin main --tags
```

CI takes over:
- builds signed APK + AAB
- attaches both to GitHub Releases
- uploads AAB to Play Console Internal track (because `PLAY_SERVICE_ACCOUNT_JSON` is now set)

After the workflow goes green:
- Open the Play Console → Internal testing → the release should be **In review** for ~5 min, then **Available**.
- Open the tester opt-in link on your phone, install via Play Store.
- Verify the install works as a real "Play Store install."

---

## Step 15: Promotion to production (later)

Stays manual. When you trust the Internal build:

Left rail → **Internal testing** → top-right **Promote release** → **Closed testing** (closed beta) or **Production**.

Production requires Google review (1–3 days first time, often hours after). Internal does not.

---

## Common gotchas

- **"You need at least one screenshot for phone"** — capture screenshots first (see `workspace/play-store-screenshots/`) and upload to Main store listing.
- **"Feature graphic is required"** — only blocks Production. Internal track may let you defer.
- **"Your release contains a higher versionCode than your latest production release"** — fine for the first one. After production goes live, every new version's versionCode must monotonically increase. `release.sh` handles this.
- **"Service account doesn't have permission to access this app"** — check Play Console → Settings → API access shows the service account with the migs music app linked + Release manager permission.
