#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="$ROOT_DIR/build/device-smoke"

mkdir -p "$ARTIFACT_DIR"

cd "$ROOT_DIR"

echo "Checking for connected Android device..."
adb devices -l

if ! adb devices | awk 'NR>1 && $2=="device" {found=1} END {exit found?0:1}'; then
  echo "ERROR: no authorized Android device. Plug in & approve USB debug." >&2
  exit 2
fi

echo "Waking screen and dismissing keyguard..."
adb shell input keyevent KEYCODE_WAKEUP >/dev/null || true
adb shell wm dismiss-keyguard >/dev/null || true
sleep 1
if adb shell dumpsys window | grep -q "mDreamingLockscreen=true"; then
  echo "ERROR: device is locked (keyguard up). Tap the phone to unlock and re-run." >&2
  exit 2
fi

echo "Building app and Android test APK..."
./gradlew assembleDebug assembleAndroidTest testDebugUnitTest

echo "Installing app APKs..."
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

echo "Muting media stream for automated playback checks..."
adb shell cmd media_session volume --stream 3 --set 0 >/dev/null || true

echo "Force-stopping and clearing app data for a clean test slate..."
adb shell am force-stop com.migsmusic >/dev/null || true
adb shell pm clear com.migsmusic >/dev/null || true
# Re-grant audio permission since pm clear revokes runtime grants.
adb shell pm grant com.migsmusic android.permission.READ_MEDIA_AUDIO >/dev/null || true
adb shell pm grant com.migsmusic android.permission.POST_NOTIFICATIONS >/dev/null || true

echo "Clearing logs and running instrumentation tests (full suite)..."
adb logcat -c
INSTR_OUT="$ARTIFACT_DIR/instrumentation.txt"
adb shell am instrument -w -r \
  com.migsmusic.test/androidx.test.runner.AndroidJUnitRunner \
  | tee "$INSTR_OUT"

# `am instrument -w -r` writes status lines starting with INSTRUMENTATION_*; the final OK/FAILURES line
# appears in the human-readable trailer. Parse defensively.
if grep -q "OK (" "$INSTR_OUT"; then
  STATUS_LINE=$(grep "OK (" "$INSTR_OUT" | tail -1)
  echo "RESULT: $STATUS_LINE"
elif grep -q "FAILURES" "$INSTR_OUT" || grep -q "Failed" "$INSTR_OUT"; then
  echo "RESULT: instrumentation reported failures"
  echo "Tail of $INSTR_OUT:"
  tail -80 "$INSTR_OUT"
  echo "Capturing logcat for diagnosis..."
  adb logcat -d -t 4000 > "$ARTIFACT_DIR/logcat-failure.txt"
  exit 1
else
  echo "RESULT: instrumentation produced no OK/FAIL marker. Treating as failure."
  tail -80 "$INSTR_OUT"
  exit 1
fi

echo "Launching app for visual capture..."
adb shell am start -n com.migsmusic/.MainActivity >/dev/null
sleep 3

echo "Capturing screenshot and recent logs..."
adb exec-out screencap -p > "$ARTIFACT_DIR/launch.png"
adb logcat -d -t 1000 > "$ARTIFACT_DIR/logcat.txt"

echo "Scanning logcat for new regressions..."
PROBLEM_RE='FATAL EXCEPTION|AndroidRuntime: FATAL|ANR in com\.migsmusic|wrong thread|Player is accessed|StrictMode policy violation|must be called on the main thread'
if grep -E -q "$PROBLEM_RE" "$ARTIFACT_DIR/logcat.txt"; then
  echo "WARN: logcat contains regression signatures:"
  grep -nE "$PROBLEM_RE" "$ARTIFACT_DIR/logcat.txt" | head -40
  exit 3
fi

echo "Artifacts written to $ARTIFACT_DIR"
echo "All tests passed cleanly."
