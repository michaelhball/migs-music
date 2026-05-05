# MIGS Music v1 Technical Spec

## Product Goal

Build a fast, local-first Android music player for OnePlus devices with a minimal feature set, strong playback reliability, and a crisp UI. The app should focus on core music playback only and avoid feature creep.

This is a local library app, not a streaming product.

## In Scope

- Local on-device music playback
- Library scan from Android media storage
- Browse by songs, albums, artists, folders, and playlists
- Search across library metadata
- Now Playing screen
- Background playback
- Lock screen and notification controls
- Bluetooth and headset transport controls
- Queue management
- Repeat modes: off, all, one
- Shuffle for library views, folders, and playlists
- Persistent playback state and queue restore after app restart
- App-managed playlists

## Out of Scope

- Streaming services
- Cloud sync or accounts
- Lyrics
- EQ / DSP
- Social features
- Podcasts or video
- Metadata editing
- Tag management UI
- Smart recommendations

## Core UX Rules

The app should behave like a simple, reliable music library player:

- Tapping a song starts playback immediately and builds a contextual queue from the current list/view.
- Every playable collection can be played either in listed order or shuffled.
- Folders and playlists preserve their displayed order as a first-class playback order.
- Queue edits feel additive and predictable.
- Manual queue insertions take precedence over the natural context queue.

## Playback Model

Playback is built from three concepts:

1. Context list
2. Active queue
3. Manual insertions

### Context list

The context list is the ordered list derived from the user action that started playback. Examples:

- all songs
- one album
- one artist's songs
- one folder
- one playlist
- search results

This list has a defined order. That order may be:

- natural displayed order
- user-selected sort order
- shuffled order

### Active queue

The active queue is what the player will actually consume. It contains:

- previously played items
- current item
- upcoming manual insertions
- remaining context items

### Manual insertions

Manual insertions are explicit user additions into the queue and must come before the untouched remainder of the context list.

This is the required Apple Music-style behavior:

- `Play Next` inserts immediately after the current song and after any already-scheduled `Play Next` items, preserving insertion order.
- `Play Later` inserts after the full `Play Next` block and after any already-scheduled `Play Later` items, but before the remaining natural context queue.
- Neither action appends to the very end behind the untouched context list.

Example:

- Context queue from playlist: `A, B, C, D, E`
- Currently playing: `B`
- Remaining natural context: `C, D, E`
- User adds `X` with `Play Next`
- User adds `Y` with `Play Later`
- User adds `Z` with `Play Next`

Upcoming queue becomes:

- `X, Z, Y, C, D, E`

This behavior must be deterministic and test-covered.

## Queue Semantics

The queue should be represented logically as:

- history
- current item
- next insertions
- later insertions
- remaining context items

Effective upcoming playback order is:

1. `next insertions`
2. `later insertions`
3. `remaining context items`

### Queue actions

Required actions:

- Play now
- Play next
- Play later
- Add to playlist
- Remove from queue
- Move within queue
- Clear manual queue additions
- Clear all upcoming queue items

### Queue behavior details

- Reordering should be allowed inside the visible upcoming queue.
- If the user manually reorders queue items, the resulting upcoming order becomes the source of truth.
- If a currently playing context queue exists and the user removes future context items from the queue, they should stay removed for the current session.
- Starting playback from a new context replaces the existing context queue and clears prior manual insertions.
- `Play Next` and `Play Later` target the current active session queue, not some global deferred list.

## Folder and Playlist Ordering

Folders and playlists must be playable in their own explicit order.

This means:

- Folder playback can use filesystem/media-store derived order as displayed in the folder screen.
- Playlist playback uses playlist item order exactly as shown in the playlist.
- Users can shuffle within a folder or playlist without losing the underlying saved order.
- After shuffle is disabled, playback returns to the canonical folder or playlist order.

### Ordering requirements

For folders:

- Default folder ordering should be stable and predictable.
- Recommended default: file path sort plus track/disc metadata where appropriate if the folder view groups album tracks.
- The chosen rule must be explicit and consistent across scan runs.

For playlists:

- Playlist item position is persisted in the database.
- Drag-to-reorder updates persisted positions.
- Playback from a playlist in non-shuffle mode must follow that saved order exactly.

## Functional Requirements

### Library

- Scan local audio files via `MediaStore`
- Index songs, albums, artists, folders
- Detect additions, deletions, and metadata changes
- Store normalized metadata in local database for fast queries

### Playback

- Play/pause
- Seek
- Next/previous
- Skip within active queue
- Shuffle
- Repeat off/all/one
- Resume last session
- Continue playback with screen off

### Search

- Search songs, albums, artists, folders, playlists
- Fast local results from indexed data

### Playlists

- Create playlist
- Rename playlist
- Delete playlist
- Add single or multiple songs
- Remove songs
- Reorder songs
- Play in playlist order
- Shuffle within playlist

### Queue

- Show upcoming queue and playback history
- Add song as next
- Add song as later
- Reorder upcoming items
- Remove upcoming items
- Preserve manual insertion priority over untouched context order

## Recommended Tech Stack

- Kotlin
- Jetpack Compose
- Media3:
  - ExoPlayer
  - MediaSession
  - MediaSessionService
- Room
- Hilt
- Kotlin Coroutines and Flow
- WorkManager for deferred rescans if needed

## Architecture

Recommended package structure:

- `app`
- `core`
- `data`
- `playback`
- `feature/library`
- `feature/search`
- `feature/playlists`
- `feature/queue`
- `feature/player`

Recommended architectural style:

- single-activity app
- Compose navigation
- unidirectional data flow
- repositories in data layer
- playback isolated behind a dedicated session/service boundary

## Data Model

### Song

- `id`
- `mediaStoreId`
- `contentUri`
- `title`
- `artist`
- `album`
- `albumArtist`
- `durationMs`
- `trackNumber`
- `discNumber`
- `folderPath`
- `mimeType`
- `dateAdded`
- `dateModified`
- `albumArtUri`

### Album

- `id`
- `name`
- `artist`
- `year`
- `artUri`

### Artist

- `id`
- `name`

### Folder

- `id`
- `path`
- `displayName`

### Playlist

- `id`
- `name`
- `createdAt`
- `updatedAt`

### PlaylistItem

- `playlistId`
- `songId`
- `position`
- `addedAt`

### PlaybackSessionState

- `currentSongId`
- `currentPositionMs`
- `repeatMode`
- `shuffleEnabled`
- `contextType`
- `contextId`
- `contextOrderDefinition`
- serialized active queue state

### QueueItem

Recommended fields:

- `songId`
- `sourceContextType`
- `sourceContextId`
- `insertionType`
- `orderKey`

Where `insertionType` is one of:

- `CONTEXT`
- `PLAY_NEXT`
- `PLAY_LATER`

This allows the queue builder to preserve priority and restore state cleanly.

## Queue Implementation Approach

Use an internal queue model rather than relying only on ExoPlayer playlist order as the source of truth.

Recommended internal structures:

- `pastItems: List<QueueEntry>`
- `currentItem: QueueEntry`
- `nextItems: MutableList<QueueEntry>`
- `laterItems: MutableList<QueueEntry>`
- `remainingContextItems: MutableList<QueueEntry>`

The player-facing list is synthesized from these segments.

Benefits:

- simple Apple Music-style insertion logic
- easy persistence and restore
- easy testing of edge cases
- clear distinction between natural queue and user-inserted queue

On each queue mutation:

1. update internal queue state
2. rebuild or patch Media3 queue order
3. publish new queue state to UI
4. persist session snapshot asynchronously

## Android Platform Requirements

- Android 13+: request `READ_MEDIA_AUDIO`
- Android 12 and below: request `READ_EXTERNAL_STORAGE`
- Use `MediaStore` content URIs
- Run playback in a foreground `MediaSessionService`
- Handle audio focus correctly
- Pause or duck on interruptions
- Handle `AUDIO_BECOMING_NOISY`
- Support Bluetooth and headset media controls

## Performance Requirements

- No blocking media scan or artwork decode on the main thread
- Use Room-backed lists for UI rendering
- Avoid querying `MediaStore` directly from composables
- Use lazy lists and paging patterns for large libraries
- Decode and cache artwork thumbnails at target sizes
- Minimize queue rebuild churn on frequent queue edits
- Persist only lightweight playback/session state

## Failure Handling

- Missing file should gracefully skip and advance playback
- Corrupt/unreadable file should surface a lightweight error and continue
- Removed media should disappear on next scan and be pruned from playlists/queue
- If restored queue items no longer exist, restore the remaining valid session

## Testing Requirements

Priority automated coverage:

- queue insertion ordering
- multiple consecutive `Play Next` operations
- multiple consecutive `Play Later` operations
- mixed `Play Next` + `Play Later`
- reordering queue after manual insertions
- restoring queue from persisted session state
- starting new context playback clears old context/manual queue correctly
- playlist playback in saved order
- folder playback in displayed order
- shuffle within playlist/folder preserves canonical order for later restore

Device validation:

- background playback
- lock screen controls
- Bluetooth headset controls
- incoming call interruption
- headphone unplug behavior
- large library responsiveness
- process death and session restore

## Phased Implementation

### Phase 1: Playback Core

- Media3 player and media session service
- now playing state
- basic queue model
- play/pause/seek/skip/repeat/shuffle
- notification and lock screen controls

### Phase 2: Library

- MediaStore scan
- Room cache
- songs/albums/artists/folders screens
- search index

### Phase 3: Queue and Playlist Behavior

- `Play Next`
- `Play Later`
- queue screen
- playlist CRUD
- playlist reorder
- folder-order playback

### Phase 4: Robustness

- restore session state
- edge-case handling
- performance tuning
- test coverage

## Open Decisions

These do not block v1, but should be decided before implementation starts:

- exact default sort rule for folder contents
- whether playlists can contain duplicate songs
- whether Android Auto support is desired later
- whether album art should prefer embedded art or MediaStore artwork when both exist

## Recommendation

Build v1 around one strict principle:

Playback order must always be explainable to the user.

That means:

- visible list order is meaningful
- folder and playlist order are first-class
- shuffle is explicit
- manual queue edits override the natural queue in a predictable way

If this principle is preserved, the app will feel simple and correct even with a minimal feature set.
