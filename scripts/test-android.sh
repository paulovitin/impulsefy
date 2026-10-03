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
adb -s "$device" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$device" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$device" shell am instrument -w com.impulsefy.test/com.impulsefy.SmokeInstrumentation | tee artifacts/android-smoke.txt
rg -q '^PASS:' artifacts/android-smoke.txt
adb -s "$device" exec-out run-as com.impulsefy cat files/qr-smoke.png > artifacts/qr-smoke.png
