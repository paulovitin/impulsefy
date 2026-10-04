#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
device="${ANDROID_SERIAL:-emulator-5554}"
case "$device" in emulator-*) ;; *) echo 'Use um emulador sem uma conta Spotify conectada.' >&2; exit 1;; esac
if [[ ! -d "${ANDROID_HOME:-}/platforms" && -d /opt/homebrew/share/android-commandlinetools/platforms ]]; then
  export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
fi
export ANDROID_SDK_ROOT="${ANDROID_HOME:?Defina ANDROID_HOME}"
./gradlew assembleDebug assembleDebugAndroidTest -Pscreenshots=true --console=plain
adb -s "$device" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$device" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p artifacts docs/screenshots
adb -s "$device" shell am instrument -w com.impulsefy.test/com.impulsefy.ScreenshotInstrumentation | tee artifacts/screenshots.txt
rg -q '^PASS:' artifacts/screenshots.txt
for name in 01-login 02-home 03-library 04-playlist 05-liked-songs 06-search 07-search-results 08-account 09-queue; do
  adb -s "$device" exec-out run-as com.impulsefy cat "files/screenshots/$name.png" > "docs/screenshots/$name.png"
done
