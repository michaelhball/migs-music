#!/usr/bin/env bash
#
# release-to-phone.sh — get release <version> onto the plugged-in phone.
#
#   scripts/release-to-phone.sh <version> [--replace] [--no-install]
#
# Idempotent on the version:
#   - GitHub Release v<version> already exists  → download its APK and install it.
#   - tag exists but the Release doesn't yet    → wait for the Release workflow, then install.
#   - neither                                   → bump versionCode/versionName on latest main,
#                                                 commit, tag, push, wait for the workflow,
#                                                 then install.
#
# Release builds are signed ONLY by the workflow (.github/workflows/release.yml) from repo
# secrets — nothing here builds or signs a release locally, so this works from any computer
# with `gh` logged in and adb available. Updates install in place (same key, higher
# versionCode); the phone's data is kept.
#
#   --replace     the phone's install is signed with another key (e.g. a pre-CI debug-signed
#                 com.migsmusic) — uninstall it first. THIS WIPES THAT APP'S DATA.
#   --no-install  stop after downloading the APK into build/releases/.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=""
REPLACE=false
INSTALL=true
for arg in "$@"; do
    case "$arg" in
        --replace) REPLACE=true ;;
        --no-install) INSTALL=false ;;
        --*) echo "✗ Unknown flag: $arg" >&2; exit 1 ;;
        *) VERSION="$arg" ;;
    esac
done
if [[ -z "$VERSION" || ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Usage: $0 <version like 0.2.0> [--replace] [--no-install]" >&2
    exit 1
fi
TAG="v$VERSION"
PKG=com.migsmusic
BUILD_GRADLE="app/build.gradle.kts"
OUT_DIR="build/releases"
APK="migs-music-$VERSION.apk"

command -v gh >/dev/null || { echo "✗ gh not installed (brew install gh)" >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "✗ gh is not logged in — run: gh auth login" >&2; exit 1; }

# Gradle needs a JDK. Take JAVA_HOME if it works, otherwise the first candidate whose
# java actually starts (a half-deleted JDK still has bin/java but fails on launch).
jdk_works() { [[ -n "$1" && -x "$1/bin/java" ]] && "$1/bin/java" -version >/dev/null 2>&1; }
find_jdk() {
    if jdk_works "${JAVA_HOME:-}"; then return; fi
    JAVA_HOME=""
    for candidate in \
        "$(/usr/libexec/java_home 2>/dev/null || true)" \
        /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
        /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
        "$HOME"/jdk17/*/Contents/Home \
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
        if jdk_works "$candidate"; then JAVA_HOME="$candidate"; break; fi
    done
    export JAVA_HOME
    [[ -n "$JAVA_HOME" ]] || { echo "✗ No working JDK found. brew install openjdk@17, or set JAVA_HOME." >&2; exit 1; }
}

wait_for_workflow() {
    echo "→ Waiting for the Release workflow run for $TAG..."
    local run_id=""
    for _ in $(seq 1 30); do
        run_id=$(gh run list --workflow=release.yml --branch "$TAG" --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)
        [[ -n "$run_id" && "$run_id" != "null" ]] && break
        sleep 5
    done
    [[ -n "$run_id" && "$run_id" != "null" ]] || { echo "✗ No workflow run appeared for $TAG. Check the Actions tab." >&2; exit 1; }
    echo "  run $run_id — $(gh run view "$run_id" --json url --jq .url)"
    if ! gh run watch "$run_id" --exit-status --interval 10 >/dev/null; then
        echo "✗ Release workflow failed. Logs:" >&2
        gh run view "$run_id" --log-failed 2>/dev/null | tail -40 >&2 || true
        exit 1
    fi
}

cut_release() {
    if [[ "$(git branch --show-current)" != "main" ]]; then
        echo "✗ Releases are cut from main." >&2
        exit 1
    fi
    if [[ -n "$(git status --porcelain)" ]]; then
        echo "✗ Working tree not clean. Commit or stash first." >&2
        exit 1
    fi
    find_jdk
    echo "→ Pulling latest main..."
    git pull -q --ff-only origin main

    echo "→ Bumping version to $VERSION..."
    local current_code new_code
    current_code=$(grep -oE 'versionCode = [0-9]+' "$BUILD_GRADLE" | grep -oE '[0-9]+')
    new_code=$((current_code + 1))
    sed -i '' \
        -e "s/versionCode = [0-9]*/versionCode = $new_code/" \
        -e "s/versionName = \"[^\"]*\"/versionName = \"$VERSION\"/" \
        "$BUILD_GRADLE"
    echo "  versionCode: $current_code → $new_code, versionName → $VERSION"

    echo "→ Lint + unit tests (CI runs them again, but fail fast here)..."
    ./gradlew -q :app:ktlintCheck :app:testDebugUnitTest

    git add "$BUILD_GRADLE"
    git commit -q -m "Bump version to $VERSION"
    git tag "$TAG"
    echo "→ Pushing main + $TAG (this triggers the Release workflow)..."
    git push -q origin main
    git push -q origin "$TAG"
}

if gh release view "$TAG" --json assets --jq '.assets[].name' 2>/dev/null | grep -qx "$APK"; then
    echo "→ $TAG is already released on GitHub — installing that build."
elif git ls-remote --exit-code --tags origin "$TAG" >/dev/null 2>&1; then
    echo "→ $TAG is tagged but not released yet — waiting for its build."
    wait_for_workflow
else
    cut_release
    wait_for_workflow
fi

echo "→ Downloading $APK from the GitHub Release..."
mkdir -p "$OUT_DIR"
gh release download "$TAG" --pattern "$APK" --dir "$OUT_DIR" --clobber
APK_PATH="$OUT_DIR/$APK"
[[ -f "$APK_PATH" ]] || { echo "✗ $APK_PATH not downloaded." >&2; exit 1; }
echo "✓ $APK_PATH ($(du -h "$APK_PATH" | cut -f1))"

$INSTALL || exit 0

echo "→ Installing on the phone..."
if ! adb devices | awk 'NR>1 && $2=="device" {found=1} END {exit found?0:1}'; then
    echo "✗ No authorised phone on adb. Plug in, set USB to File transfer, accept the debugging prompt." >&2
    echo "  APK is ready at $APK_PATH — rerun with the phone attached or install it by hand." >&2
    exit 2
fi
BEFORE=$(adb shell dumpsys package "$PKG" 2>/dev/null | grep -oE 'versionName=[^ ]+' | head -1 || true)
if $REPLACE && adb shell pm path "$PKG" >/dev/null 2>&1; then
    echo "  --replace: uninstalling the existing $PKG (app data is wiped)..."
    adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
    adb uninstall "$PKG" >/dev/null
fi
INSTALL_OUT=$(adb install -r "$APK_PATH" 2>&1) || true
if echo "$INSTALL_OUT" | grep -q "Success"; then
    echo "✓ Installed${BEFORE:+ (was $BEFORE, data kept)}."
elif echo "$INSTALL_OUT" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE"; then
    echo "✗ The phone's copy is signed with a different key (probably a debug build)." >&2
    echo "  Rerun with --replace to uninstall it first. That wipes playlists/loves/settings;" >&2
    echo "  re-sync from the Mac app afterwards." >&2
    exit 3
elif echo "$INSTALL_OUT" | grep -q "INSTALL_FAILED_VERSION_DOWNGRADE"; then
    echo "✗ The phone already has a newer version than $VERSION. Pick a higher version." >&2
    exit 3
else
    echo "✗ Install failed:" >&2
    echo "$INSTALL_OUT" >&2
    exit 3
fi

INSTALLED=$(adb shell dumpsys package "$PKG" | grep -oE 'versionName=[^ ]+' | head -1)
echo "  phone reports $INSTALLED"
[[ "$INSTALLED" == "versionName=$VERSION" ]] || { echo "✗ Version mismatch after install." >&2; exit 3; }
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
echo "✓ $TAG is on the phone and launched."
