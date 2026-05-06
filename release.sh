#!/usr/bin/env bash
#
# Release pipeline for the Android app. Bumps version, runs lint+tests, builds a signed
# Android App Bundle, prints the path of the .aab ready to upload to Play Console.
#
# Usage:
#   ./release.sh 0.2.0           # bumps versionName + versionCode
#   ./release.sh 0.2.0 --tag     # also creates v0.2.0 git tag
#   ./release.sh                 # rebuild without bumping
#
# Output:
#   app/build/outputs/bundle/release/app-release.aab     (signed, Play-ready)
#
# What this does NOT do:
#   - Upload to Play Console. Once you trust the script, the Play Developer API is the
#     way: a JSON service-account key + the `androidpublisher.applications.upload`
#     scope. Free; can be wired into release.sh later.
#   - Run instrumented tests. They need a connected device; CI doesn't have one. Run
#     `scripts/device-smoke-test.sh` separately before tagging if you've changed
#     anything that could affect runtime.

set -euo pipefail

cd "$(dirname "$0")"

VERSION="${1:-}"
TAG_FLAG="${2:-}"
BUILD_GRADLE="app/build.gradle.kts"

if [[ -n "$VERSION" && "$VERSION" == --* ]]; then
    echo "✗ First arg should be a version like 0.2.0, not a flag." >&2
    exit 1
fi

if [[ -n "$VERSION" ]]; then
    echo "→ Bumping version to $VERSION..."
    # versionCode = current + 1 (Play Store requires monotonic integer).
    CURRENT_CODE=$(grep -oE 'versionCode = [0-9]+' "$BUILD_GRADLE" | grep -oE '[0-9]+')
    NEW_CODE=$((CURRENT_CODE + 1))
    sed -i '' \
        -e "s/versionCode = [0-9]*/versionCode = $NEW_CODE/" \
        -e "s/versionName = \"[^\"]*\"/versionName = \"$VERSION\"/" \
        "$BUILD_GRADLE"
    echo "  versionCode: $CURRENT_CODE → $NEW_CODE"
    echo "  versionName: → $VERSION"
else
    VERSION=$(grep -oE 'versionName = "[^"]*"' "$BUILD_GRADLE" | grep -oE '"[^"]*"' | tr -d '"')
    echo "→ Using current version: $VERSION"
fi

if [[ ! -f keystore.properties ]]; then
    echo "✗ keystore.properties is missing." >&2
    echo "  See RELEASING.md for how to create the release keystore + properties file." >&2
    exit 1
fi

echo "→ Running lint..."
./gradlew :app:ktlintCheck

echo "→ Running unit tests..."
./gradlew :app:testDebugUnitTest

echo "→ Building signed Android App Bundle..."
./gradlew :app:bundleRelease

AAB_PATH="app/build/outputs/bundle/release/app-release.aab"
if [[ ! -f "$AAB_PATH" ]]; then
    echo "✗ Expected .aab not produced at $AAB_PATH" >&2
    exit 1
fi

AAB_SIZE=$(stat -f %z "$AAB_PATH" 2>/dev/null || stat -c %s "$AAB_PATH")
AAB_SIZE_MB=$(awk "BEGIN {printf \"%.2f\", $AAB_SIZE / 1024 / 1024}")

echo ""
echo "✓ Release build ready:"
echo "    $AAB_PATH (${AAB_SIZE_MB} MiB)"
echo "    versionName: $VERSION"
echo ""

if [[ "$TAG_FLAG" == "--tag" ]]; then
    if ! git diff --quiet "$BUILD_GRADLE"; then
        git add "$BUILD_GRADLE"
        git commit -m "Bump version to $VERSION"
    fi
    git tag "v$VERSION"
    echo "✓ Tagged v$VERSION (push with: git push --tags)"
fi

cat <<EOF

Next steps:
  1. Upload to Play Console → Internal testing track first:
       https://play.google.com/console/u/0/developers/<your-id>/app/<app-id>/tracks/internal-testing
     Drag $AAB_PATH onto the upload area. Add release notes, save, review, roll out.
  2. After internal testing looks good, promote the same release to closed/open beta,
     then production. Each track has its own rollout percentage.
  3. Once you trust this script, look at the Google Play Developer API for one-shot
     uploads. JSON service-account key + the androidpublisher.applications.upload
     scope. Free; can be wired into this script later.
EOF
