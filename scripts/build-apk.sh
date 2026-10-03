#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -d "${ANDROID_HOME:-}/platforms" && -d /opt/homebrew/share/android-commandlinetools/platforms ]]; then
  export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
fi
if [[ ! -d "${ANDROID_HOME:-}/platforms" ]]; then
  echo 'Defina ANDROID_HOME para seu Android SDK.' >&2
  exit 1
fi
./scripts/build-native.sh
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew assembleRelease lintRelease -PlocalSigning=true --console=plain "$@"
mkdir -p artifacts
cp app/build/outputs/apk/release/app-release.apk artifacts/impulsefy.apk
shasum -a 256 artifacts/impulsefy.apk > artifacts/impulsefy.apk.sha256
du -h artifacts/impulsefy.apk
