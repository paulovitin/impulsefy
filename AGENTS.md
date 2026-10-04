# Working on Impulsefy

These instructions apply throughout this repository. Follow the user's current
scope and device restrictions, including any permission already granted in the
conversation. Preserve unrelated work in the working tree.

## Project and architecture

Impulsefy is a native Android Spotify client for Haval/GWM infotainment systems.
It uses Java Views, a Rust/librespot engine over JNI, AudioTrack, and MediaSession.
The app connects directly to Spotify; there is no Impulsefy backend. The former
`server/` relay has been removed. Do not restore it as a prerequisite for login.

Read `README.md` for setup and `tasks/todo.md` for completed work and outstanding
real-device validation. Build settings are in `app/build.gradle`: JDK 17,
compileSdk 36, minSdk 23, targetSdk 28, and NDK 27.2.12479018. Do not casually
raise the minimum/target SDK or replace the native UI stack.

| Area | Entry points |
| --- | --- |
| Screens, navigation, preview data | `app/src/main/java/com/impulsefy/MainActivity.java` |
| Pairing, refresh, encrypted credentials | `AuthManager.java`, `SecureStore.java` in the same package |
| Catalog routing and HTTP transport | `SpotifyApi.java` in the same package; `native/src/catalog.rs` |
| Android playback and vehicle volume | `PlayerService.java`, `NativePlayer.java`, `CarVolume.java` in the same package |
| Image loading and fictional covers | `Artwork.java`, `DemoArtwork.java` in the same package |
| Rust engine and JNI | `native/src/lib.rs` |
| Protocol generation | `native/build.rs`, `native/proto/collection2v2.proto` |
| Android checks and screenshots | `app/src/androidTest/java/com/impulsefy/` |
| Design reference | `design/impulsefy.pen`, `design/assets/` |

## Implementation constraints

- Trace the relevant callers and existing tests before changing behavior. Prefer
  a small change to the existing implementation over new layers or dependencies.
- Keep HTTP, token refresh, decoding, and blocking native calls off the UI thread.
  Preserve cancellation, session-revision checks, and sign-out race protection.
- Never log, commit, or put real tokens, cookies, passwords, active pairing codes,
  or account details in fixtures or screenshots. Keep credentials in SecureStore.
- The Pencil file is a design reference, not the runtime UI. Do not add screens
  merely because the design contains them. Follow the requested app scope.
- Keep Android system bars and the vehicle sidebar available. Do not restore
  forced immersive/fullscreen behavior.
- Keep `ts.framework` optional. Native media volume must work on compatible
  vehicles; other devices must retain software volume. Preserve independent
  handling of user volume, focus ducking, and pause/mute.
- Preview mode must remain account-free, use fictional data, avoid real playback,
  and leave real account preferences untouched. Mock authentication belongs in
  `androidTest`, not production shortcuts.
- Keep Java/JNI signatures, JSON command/state fields, Rust handlers, and
  `app/proguard-rules.pro` consistent when changing the native boundary.
- `native/vendor/librespot-core/` is required source, not a disposable cache.
  Read its `IMPULSEFY.md` before editing it; keep the compatibility patch focused
  and update that note when its behavior changes. Preserve upstream licenses.
- Edit protocol definitions in `native/proto/`; generated Rust code belongs in
  Cargo build output. Keep `native/Cargo.lock` and use `--locked` for validation.

## Build and verification

Use macOS or Linux with the prerequisites in the README. Run commands from the
repository root. Respect requests not to build or install. Generated native
libraries and build output are absent from a clean checkout.

For Rust changes:

```sh
cargo fmt --manifest-path native/Cargo.toml -- --check
cargo test --manifest-path native/Cargo.toml --locked
cargo clippy --manifest-path native/Cargo.toml --locked --all-targets -- -D warnings
```

For Android changes, build JNI libraries before Gradle. For an ARM64 test emulator:

```sh
./scripts/build-native.sh arm64-v8a
./gradlew lintDebug --console=plain
ANDROID_SERIAL=emulator-5554 ./scripts/test-android.sh
```

Use `./scripts/build-native.sh x86_64` for an x86-64 emulator. Rerun the native
build after changing Rust; `test-android.sh` builds Java but does not rebuild Rust.
The Android test script installs the debug app and instrumentation. Use a running
test emulator without a signed-in Spotify account. The script rejects physical
devices; do not bypass that guard to run fixtures on a user's car.

For a release build, `./scripts/build-apk.sh` builds ARM64 for Haval, runs R8 and
release lint, and writes the APK and checksum under `artifacts/`. It uses the
machine's development signing key. It does not install or publish the APK.
Release packaging is restricted to `arm64-v8a`; x86-64 is only for emulator
tests. Do not restore multi-ABI release packages without a scope change.

GitHub CI and release workflows live in `.github/workflows/`; see
`docs/releases.md`. Release signing secrets belong only in the signing step,
never PR checks. Preserve the successful-main-CI, merged-PR, and exact-commit
guards. Keep Android version codes monotonic; local builds and signed releases
use different certificates. Validate workflow edits with `actionlint` when available.

Run checks appropriate to the change; do not rebuild the app for documentation-
only edits. Verify links and command accuracy instead. For behavior fixes, add
focused regression coverage to the existing tests. Report exactly what ran and
what remains unverified; mock tests do not establish real Spotify compatibility.
The vendored librespot has a known lint-expectation warning recorded in
`tasks/todo.md`; do not weaken checks to hide new warnings.

## Devices and screenshots

An attached ADB device is not, by itself, authorization to install, uninstall,
clear data, sign out, change native volume, or start playback. Honor the scope
already authorized by the user; do not ask again when that authorization covers
the action. Preserve real sessions and keep vehicle testing stationary.

For screenshots, use the dedicated emulator workflow from the README:

```sh
adb -s emulator-5554 shell wm size 1920x720
adb -s emulator-5554 shell wm density 160
ANDROID_SERIAL=emulator-5554 ./scripts/capture-screenshots.sh
```

Build the emulator's JNI library first. Capture the existing nine states with
fictional data, inspect every PNG, and keep the latest set in `docs/screenshots/`.
Do not fill the repository with intermediate captures or device diagnostics.

## Documentation, assets, and licensing

- `README.md` is the English default; keep `README.pt-BR.md` equivalent. English
  documentation does not imply translating the app's Portuguese UI.
- Keep `tasks/todo.md` in English. Record completed milestones and evidence
  accurately; keep pending live validation unchecked.
- Keep the logo and Pencil assets under `design/`, with relative paths intact.
  Use SVG/vector sources for sharp logos. Do not scatter exports in the root.
- Follow GNU AGPLv3 in `LICENSE` for project code. Preserve dependency licenses,
  the Inter OFL, and image credits in `app/src/main/assets/artwork-credits.txt`.
  Do not describe AGPLv3 as prohibiting commercial use or relicense third-party
  assets under it.
- Keep `.gradle/`, build directories, `native/target/`, generated `jniLibs/`,
  `artifacts/`, APKs, signing keys, local configuration, and logs out of Git.
  Keep the Gradle wrapper JAR, Cargo lockfile, vendored source, and final gallery.
- Do not commit or publish changes unless the user's task includes that action.
  End with a concise account of changes, validation, and remaining limitations.
