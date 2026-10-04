#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ ! -d "$sdk_dir/ndk" && -d /opt/homebrew/share/android-commandlinetools/ndk ]]; then
  sdk_dir=/opt/homebrew/share/android-commandlinetools
fi
ndk_dir="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-$sdk_dir/ndk/27.2.12479018}}"
if [[ ! -f "$ndk_dir/source.properties" ]]; then
  echo "Android NDK 27.2.12479018 is required. Set ANDROID_HOME or ANDROID_NDK_HOME." >&2
  exit 1
fi
case "$(uname -s)" in
  Darwin) host_tag=darwin-x86_64 ;;
  Linux) host_tag=linux-x86_64 ;;
  *) echo 'Build on macOS or Linux with Android NDK 27.2.' >&2; exit 1 ;;
esac
toolchain="$ndk_dir/toolchains/llvm/prebuilt/$host_tag/bin"
if [[ $# -eq 0 ]]; then set -- arm64-v8a; fi
for abi in "$@"; do
  case "$abi" in
    arm64-v8a) target=aarch64-linux-android; compiler=aarch64-linux-android23-clang ;;
    x86_64) target=x86_64-linux-android; compiler=x86_64-linux-android23-clang ;;
    *) echo "Unsupported ABI: $abi (use arm64-v8a for Haval or x86_64 for emulator tests)" >&2; exit 1 ;;
  esac
  rustup target add "$target"
  cargo_target="$(echo "$target" | tr '[:lower:]-' '[:upper:]_')"
  env "CARGO_TARGET_${cargo_target}_LINKER=$toolchain/$compiler" \
      "CC_${target//-/_}=$toolchain/$compiler" \
      "AR_${target//-/_}=$toolchain/llvm-ar" \
      "CARGO_TARGET_${cargo_target}_RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384" \
      cargo build --manifest-path "$project_dir/native/Cargo.toml" --locked --release --target "$target"
  mkdir -p "$project_dir/app/src/main/jniLibs/$abi"
  cp "$project_dir/native/target/$target/release/libimpulsefy.so" "$project_dir/app/src/main/jniLibs/$abi/libimpulsefy.so"
  echo "Built $abi: app/src/main/jniLibs/$abi/libimpulsefy.so"
done
