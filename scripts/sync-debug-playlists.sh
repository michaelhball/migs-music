#!/usr/bin/env bash
#
# sync-debug-playlists.sh — put the Mac app's ticked playlists onto the DEBUG build.
#
# The Mac menu-bar app only ever targets the release package (com.migsmusic). Debug builds
# install as com.migsmusic.debug, so they'd otherwise stay empty. This replays what the app
# does on "Sync" — manifest, bundled sync script, AUTO_IMPORT broadcast — aimed at the debug
# package. Audio lives in the shared /sdcard/Music, so only .m3u files actually move.
#
# Usage:
#   scripts/sync-debug-playlists.sh                  # the playlists ticked in the Mac app
#   scripts/sync-debug-playlists.sh "Name" ["Name2"] # explicit playlists
#
# Env: MIGS_PACKAGE (default com.migsmusic.debug), MIGS_MAC_REPO (default ~/projects/migs-music-mac).
set -euo pipefail

PKG="${MIGS_PACKAGE:-com.migsmusic.debug}"
MAC_REPO="${MIGS_MAC_REPO:-$HOME/projects/migs-music-mac}"
SYNC_SCRIPT="$MAC_REPO/sync-playlist-to-phone.sh"
SYNC_DIR="/sdcard/Android/media/$PKG/sync"
BUNDLED_TRACKS="/Applications/MigsMusicMac.app/Contents/Resources/migs-tracks"

[[ -x "$SYNC_SCRIPT" ]] || { echo "✗ $SYNC_SCRIPT not found/executable. Clone migs-music-mac next to this repo." >&2; exit 1; }
if ! adb devices | awk 'NR>1 && $2=="device" {found=1} END {exit found?0:1}'; then
    echo "✗ No authorised phone on adb." >&2
    exit 2
fi
adb shell pm path "$PKG" >/dev/null 2>&1 || { echo "✗ $PKG is not installed (./gradlew :app:installDebug)." >&2; exit 1; }

# Playlists: explicit args, else whatever is ticked in the Mac app.
if [[ $# -gt 0 ]]; then
    NAMES=("$@")
else
    NAMES=()
    while IFS= read -r line; do NAMES+=("$line"); done < <(
        defaults export com.migsmusic.mac - 2>/dev/null | python3 -c '
import plistlib, sys
for n in plistlib.loads(sys.stdin.buffer.read()).get("selectedPlaylists", []):
    print(n.replace("\n", " ").replace("\r", " "))'
    )
    [[ ${#NAMES[@]} -gt 0 ]] || { echo "✗ No playlists ticked in the Mac app and none given." >&2; exit 1; }
fi

# Manifest: same phoneSafeName rule as the Mac app (FAT-illegal chars → _), one name per
# line. Deliberately NO deleteOrphans opt — a debug sync must never delete audio files the
# release install's playlists still reference.
MANIFEST=$(mktemp)
trap 'rm -f "$MANIFEST"' EXIT
printf '%s\n' "${NAMES[@]}" | python3 -c '
import sys
bad = set("<>:\"/\\|?*")
for raw in sys.stdin.read().split("\n"):
    n = "".join("_" if c in bad else c for c in raw)
    if n and not n.startswith("#"):
        print(n)' > "$MANIFEST"

echo "→ Syncing ${#NAMES[@]} playlist(s) to $PKG"
adb shell mkdir -p "$SYNC_DIR"
adb push "$MANIFEST" "$SYNC_DIR/.migs-sync-manifest" >/dev/null

# The Mac script pushes audio + .m3u files; MIGS_PACKAGE points its sync dir at ours.
# Reuse the app bundle's migs-tracks helper if the repo checkout hasn't built one.
export MIGS_PACKAGE="$PKG"
[[ -x "$BUNDLED_TRACKS" ]] && export MIGS_TRACKS="${MIGS_TRACKS:-$BUNDLED_TRACKS}"
"$SYNC_SCRIPT" --no-broadcast "${NAMES[@]}"

adb shell "am broadcast -a com.migsmusic.AUTO_IMPORT -p $PKG -f 0x20" >/dev/null
echo "✓ Broadcast AUTO_IMPORT to $PKG — open 'migs music dev' to see the playlists."
