# Album art on lock screen — regression history + invariants

The lock-screen / notification-shade / Quick Settings album art on this app has regressed at least twice. This file captures the root cause and the invariants that prevent the regression. **Read this before touching `PlaybackManager.buildMediaItem` or `loadArtworkBytes`.**

## What "lock-screen art works" means in practice

When you play a song, the OS shows the album art:

- on the lock screen
- in the notification shade media tile
- in the Quick Settings media controls
- in Bluetooth/Auto media-info displays

These all read from the active `MediaSession`'s `MediaMetadata`. We populate that metadata via Media3's `MediaItem.mediaMetadata`. Two attributes on `MediaMetadata` can supply art:

- `setArtworkUri(uri)` — the OS's bitmap loader resolves the URI to bytes
- `setArtworkData(bytes, type)` — we hand it the raw JPEG/PNG bytes directly

## Why setting both breaks OnePlus

On OnePlus's `MediaStore.Audio.Albums` provider (and some other OEMs), the URI returned by `MediaStore.Audio.Media.ALBUM_ART` for many tracks **does not actually serve bytes** when opened from a background context. Reasons vary: scoped-storage quirks, sandbox-violation rejections, the OEM provider's lazy extraction not having run, or the provider returning `null`/empty stream for files MediaScanner indexed without art metadata.

When we call `setArtworkUri` *and* `setArtworkData`:

1. The `MediaSession` first attempts to resolve the artworkUri via the system bitmap loader.
2. The bitmap loader fails silently and reports "no art" to the session's metadata.
3. The session caches "no art" for this MediaItem.
4. Our `setArtworkData(bytes)` arrives *after* and updates the metadata, but the lock-screen / shade card has already been rendered with the cached "no art" state.
5. Some OS surfaces never re-render after the late update. The lock screen stays blank.

The symptom: a song plays, controls work, but the art slot is empty.

## The invariants

1. **NEVER call `setArtworkUri` on a MediaItem we own.** Period. The MediaItem's `MediaMetadata.Builder` chain in `buildMediaItem` deliberately omits it. Don't add it back, even "to fall back to the URI when bytes aren't available." There is no such case — if path-1 (URI) bytes load fine, path-2 (`MediaMetadataRetriever.embeddedPicture`) loads the same bytes too. Set `setArtworkData` directly via `embedArtworkForCurrent` and that's the only source of truth.
2. **Treat empty bytes from path 1 as a miss.** Some OEM providers happily open the URI stream and return zero bytes when they don't actually have the art. Without the `isNotEmpty` guard in `loadArtworkBytes`, we'd ship those zero bytes to `setArtworkData` and leave the user with a blank lock screen and no fallback.
3. **`embedArtworkForCurrent` runs on every `onMediaItemTransition`.** That's the single bottleneck where we get the bytes loaded and pushed into the session. Don't skip it on edge cases (skipToNext/skipToPrevious already covered; resumeFromSnapshot also covered).

## How to reproduce a regression manually

You need a real OnePlus (or a device whose `MediaStore.Audio.Albums.ALBUM_ART` URI returns null/empty bytes — most OnePlus / OPPO / Realme do; Pixel does not).

1. `./gradlew :app:installDebug`
2. Play any song that has embedded ID3 art.
3. Lock the phone. Look at the lock-screen card. **Album art must be visible.**
4. Pull down the notification shade with the screen on. **Same — art visible.**
5. Skip to the next song. Wait ~2s for the metadata to settle. **Art updates to the new song's.**

If any of those four shows blank art for a song that has embedded art, the regression is back. Look at `loadArtworkBytes` and the `MediaMetadata.Builder` chain in `buildMediaItem`.

## Prior regressions

| Date | Commit | What broke | What fixed |
| --- | --- | --- | --- |
| 2026-05-08 | (pre-79dad41) | OEM bitmap loader couldn't resolve ALBUM_ART URI, no fallback. | `79dad41` added MediaMetadataRetriever fallback in `loadArtworkBytes`. |
| 2026-05-09 | (this commit) | Setting both `setArtworkUri` and `setArtworkData` raced; OS cached the artless URI result before our bytes arrived. Path 1 also returned zero-byte streams that bypassed the path-2 fallback. | Stop calling `setArtworkUri` entirely; `isNotEmpty` guard on path 1; this regression doc. |
