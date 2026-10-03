#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
device="${ANDROID_SERIAL:-emulator-5554}"
case "$device" in emulator-*) ;; *) echo 'Use um emulador de teste, sem uma conta Spotify conectada.' >&2; exit 1;; esac
if [[ ! -d "${ANDROID_HOME:-}/platforms" && -d /opt/homebrew/share/android-commandlinetools/platforms ]]; then
  export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
fi
export ANDROID_SDK_ROOT="${ANDROID_HOME:?Defina ANDROID_HOME}"
./gradlew assembleDebug assembleDebugAndroidTest --console=plain
mkdir -p artifacts
PUBLIC_URL=http://127.0.0.1:8787 SPOTIFY_CLIENT_ID=11111111111111111111111111111111 node server/relay.mjs > artifacts/test-relay.log 2>&1 &
relay_pid=$!
trap 'kill "$relay_pid" 2>/dev/null || true; adb -s "$device" reverse --remove tcp:8787 >/dev/null 2>&1 || true' EXIT
sleep 0.5
if ! kill -0 "$relay_pid" 2>/dev/null; then cat artifacts/test-relay.log; exit 1; fi
adb -s "$device" reverse tcp:8787 tcp:8787
adb -s "$device" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$device" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$device" shell am instrument -w com.impulsefy.test/com.impulsefy.SmokeInstrumentation | tee artifacts/android-smoke.txt
rg -q '^PASS:' artifacts/android-smoke.txt
adb -s "$device" exec-out run-as com.impulsefy cat files/qr-smoke.png > artifacts/qr-smoke.png
