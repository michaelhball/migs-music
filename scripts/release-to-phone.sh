#!/usr/bin/env bash
#
# release-to-phone.sh — cut a tagged release, let GitHub Actions build + sign it,
# then pull the APK down and install it on the plugged-in phone.
#
# Usage:
#   scripts/release-to-phone.sh <version> [--replace] [--no-install]
#
#   <version>     e.g. 0.2.0. Bumps versionCode/versionName, commits, tags v<version>.
#   --replace     if the phone's install is signed with a different key (debug build),
#                 uninstall it first. THIS WIPES THE APP'S DATA on the phone — playlists,
#                 loves, settings. Re-sync from the Mac app afterwards.
#   --no-install  stop after downloading the APK.
#   --install-only  the release already exists (tag pushed, workflow done): skip the bump,
#                 tag and wait, just download that version's APK and install it.
#
# Needs: gh (authenticated: `gh auth login`), adb, a clean tree on main. The signed build
# happens on GitHub — no local keystore is required, so this works from any computer.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=""
REPLACE=false
INSTALL=true
INSTALL_ONLY=false
for arg in "$@"; do
    case "$arg" in
        --replace) REPLACE=true ;;
        --no-install) INSTALL=false ;;
        --install-only) INSTALL_ONLY=true ;;
        --*) echo "✗ Unknown flag: $arg" >&2; exit 1 ;;
        *) VERSION="$arg" ;;
    esac
done
if [[ -z "$VERSION" || ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Usage: $0 <version like 0.2.0> [--replace] [--no-install] [--install-only]" >&2
    exit 1
fi
TAG="v$VERSION"
PKG=com.migsmusic
BUILD_GRADLE="app/build.gradle.kts"
OUT_DIR="build/releases"

command -v gh >/dev/null || { echo "✗ gh not installed (brew install gh)" >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "✗ gh is not logged in — run: gh auth login" >&2; exit 1; }
if ! $INSTALL_ONLY; then
    if [[ "$(git branch --show-current)" != "main" ]]; then
        echo "✗ Releases are cut from main." >&2
        exit 1
    fi
    if [[ -n "$(git status --porcelain)" ]]; then
        echo "✗ Working tree not clean. Commit or stash first." >&2
        exit 1
    fi
    if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || git ls-remote --exit-code --tags origin "$TAG" >/dev/null 2>&1; then
        echo "✗ Tag $TAG already exists." >&2
        exit 1
    fi

    # Gradle needs a JDK. Take JAVA_HOME if it works, otherwise the first candidate whose
    # java actually starts (a half-deleted JDK still has bin/java but fails on launch).
    jdk_works() { [[ -n "$1" && -x "$1/bin/java" ]] && "$1/bin/java" -version >/dev/null 2>&1; }
    if ! jdk_works "${JAVA_HOME:-}"; then
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
    fi
    [[ -n "$JAVA_HOME" ]] || { echo "✗ No working JDK found. brew install openjdk@17, or set JAVA_HOME." >&2; exit 1; }

    echo "→ Pulling latest main..."
    git pull -q --ff-only origin main

    echo "→ Bumping version to $VERSION..."
    CURRENT_CODE=$(grep -oE 'versionCode = [0-9]+' "$BUILD_GRADLE" | grep -oE '[0-9]+')
    NEW_CODE=$((CURRENT_CODE + 1))
    sed -i '' \
        -e "s/versionCode = [0-9]*/versionCode = $NEW_CODE/" \
        -e "s/versionName = \"[^\"]*\"/versionName = \"$VERSION\"/" \
        "$BUILD_GRADLE"
    echo "  versionCode: $CURRENT_CODE → $NEW_CODE, versionName → $VERSION"

    echo "→ Lint + unit tests (CI runs them again, but fail fast here)..."
    ./gradlew -q :app:ktlintCheck :app:testDebugUnitTest

    git add "$BUILD_GRADLE"
    git commit -q -m "Bump version to $VERSION"
    git tag "$TAG"
    echo "→ Pushing main + $TAG (this triggers the Release workflow)..."
    git push -q origin main
    git push -q origin "$TAG"

    echo "→ Waiting for the Release workflow run for $TAG..."
    RUN_ID=""
    for _ in $(seq 1 30); do
        RUN_ID=$(gh run list --workflow=release.yml --branch "$TAG" --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)
        [[ -n "$RUN_ID" && "$RUN_ID" != "null" ]] && break
        sleep 5
    done
    [[ -n "$RUN_ID" && "$RUN_ID" != "null" ]] || { echo "✗ No workflow run appeared for $TAG. Check the Actions tab." >&2; exit 1; }
    echo "  run $RUN_ID — $(gh run view "$RUN_ID" --json url --jq .url)"
    if ! gh run watch "$RUN_ID" --exit-status --interval 10 >/dev/null; then
        echo "✗ Release workflow failed. Logs:" >&2
        gh run view "$RUN_ID" --log-failed 2>/dev/null | tail -40 >&2 || true
        exit 1
    fi
else
    gh release view "$TAG" >/dev/null 2>&1 || { echo "✗ No GitHub Release for $TAG." >&2; exit 1; }
fi

APK="migs-music-$VERSION.apk"
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
if $REPLACE && adb shell pm path "$PKG" >/dev/null 2>&1; then
    echo "  --replace: uninstalling the existing $PKG (app data is wiped)..."
    adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
    adb uninstall "$PKG" >/dev/null
fi
INSTALL_OUT=$(adb install -r "$APK_PATH" 2>&1) || true
if echo "$INSTALL_OUT" | grep -q "Success"; then
    echo "✓ Installed."
elif echo "$INSTALL_OUT" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE"; then
    echo "✗ The phone's copy is signed with a different key (probably a debug build)." >&2
    echo "  Rerun with --replace to uninstall it first. That wipes playlists/loves/settings;" >&2
    echo "  re-sync from the Mac app afterwards." >&2
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
