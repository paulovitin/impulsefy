# Impulsefy development history

Last updated: 2026-10-04. Current app version: **0.1.0** (`versionCode 1`).

Build a native Spotify client for Haval/GWM Android infotainment systems, with
local audio playback, phone-based QR pairing, and a touch-friendly interface.
The current app connects directly to Spotify for sign-in, library access, and
playback. Users do not need an Impulsefy server, their own Client ID, or a
companion app. Spotify Premium is required for playback.

Completed items below record implementation and the checks actually performed.
A completed implementation does not imply that every feature has been validated
with a real account on the vehicle; remaining checks are listed at the end.

## 1. Native Android foundation

- [x] Create the Java app with native Views, `compileSdk 36`, `minSdk 23`, and
      `targetSdk 28`, using Inter and the Impulse Home visual direction.
- [x] Integrate librespot 0.8 through Rust/JNI, with PCM output through
      AudioTrack, a playback service, and Android MediaSession controls.
- [x] Implement library, liked songs, search, playlists, and player navigation.
- [x] Add Gradle and Rust build scripts for ARM64 and ARMv7, R8 optimization,
      local development signing, and APK installation through ADB.
- [x] Fix audio focus across track transitions and unavailable preloads,
      restore volume after interruptions, and recreate failed AudioTrack output.
- [x] Verify the initial app on an Android 9/API 28 ARM64 emulator, including
      navigation, JNI, silent PCM output, encrypted storage, build, and lint.

Historical commits: `7cb6508`, `25cdd70`, and `afc6aa3`.

## 2. Initial sign-in and playback integration — superseded

- [x] Implement OAuth PKCE with a QR code on the car, phone-browser consent,
      encrypted Android credentials, and an HTTPS relay for the callback.
- [x] Deploy the relay on tonton at local port 8787, configure the Spotify
      Client ID and HTTPS callback, and fix consent-origin validation.
- [x] Verify relay expiration, denied consent, single-use callback delivery,
      replay protection, and rate limits; seven relay tests passed at that stage.
- [x] Diagnose the separation between library OAuth access and audio access:
      the library loaded while Spotify login5 rejected playback authorization.
- [x] Add Spotify Connect discovery and a separate playback credential, accept
      only the signed-in account, and persist the credential in Android Keystore.
- [x] Install the updated APK on the car. The user selected Impulsefy in Spotify
      on the same network and confirmed successful local music playback.
- [x] Explore temporary browser-based sign-in and separate authorization flows
      to remove the requirement for each user to create a Spotify developer app.
- [x] Replace those flows with direct Spotify device pairing. Remote-browser and
      manual-return authorization prototypes were removed from production.

These steps explain the path to the current implementation; they are not setup
requirements for the current app. Historical commits: `6179e57`, `0ee5af7`,
`3dec80f`, and `4dddbfd`.

## 3. Direct Spotify pairing and session-based catalog

- [x] Reuse the existing QR screen for `spotify.com/pair`, with one confirmation
      in the user's Android or iPhone browser.
- [x] Request device authorization, poll, and refresh tokens directly between
      Android and Spotify. Keep the private device code in memory on the car.
- [x] Encrypt persisted tokens and playback credentials with AES-GCM and an
      Android Keystore key; clear both credentials on sign-out.
- [x] Handle pending authorization, denied consent, expiration, `slow_down`,
      cancellation, refresh, and attempts to save credentials after sign-out.
- [x] Load playlists, liked songs, and search through the librespot session,
      using the same authorization as playback instead of the shared Web API quota.
- [x] Fix the librespot client-token identity for Android device pairing while
      preserving the mobile identity for legacy Spotify Connect credentials.
      Document the [local compatibility patch](../native/vendor/librespot-core/IMPULSEFY.md).
- [x] Confirm live library, liked-song, and search reads, including second pages.
- [x] Build and install the direct-pairing APK on the car. The user authorized its
      QR code, the library loaded, and the user confirmed audible playback.
- [x] Verify track search and playback from the results on the car.
- [x] Preserve the signed-in session across APK updates and reconnect after the
      client-token fix. Keep compatibility with previously saved authorizations.
- [x] Copy the release APK to `artifacts/impulsefy.apk` and `../impulsefy.apk`.

Historical commit: `e2af504`. Real token files used during development probes
were removed. Long-running renewal checks remain listed under pending validation.

## 4. Pencil design and player controls

- [x] Use the Pencil document as a visual reference for the existing app screens.
      Implement login `QhCG9`, home `GBKC3`, library `iT2sp`, playlist `uDuUc`,
      liked songs `NaD3u`, search `MXo2S`, search results `XNazV`, and account
      modal `XKxuV`.
- [x] Replace the app logo with the `anNet` reference and use sharp vector artwork
      suitable for the car display and launcher.
- [x] Implement the shared player on the left, top navigation, cover grids,
      playlist rows, and account modal.
- [x] Remove forced immersive behavior so Android system bars and the vehicle's
      sidebar remain available.
- [x] Implement shuffle, repeat, like/unlike, media volume, local queue, resume,
      library and search filters, recent searches, genre shortcuts, and voice
      search where supported by Android.
- [x] Limit recent playback history to 60 tracks and saved-like state to 1,000
      items. Preserve pagination and image caching; clear history on sign-out.
- [x] Generate the required collection protobuf module locally for native
      catalog operations.
- [x] Pass Android navigation checks, eight Rust tests, and Clippy; build ARM64
      and ARMv7 libraries and the optimized APK with release lint.
- [x] Install the redesign with `adb install -r`, preserving the account. Observe
      the home screen, playlist library, search, and playback on the vehicle.

The first visual pass was recorded in `b2f4d6b`; the subsequent screen refactor
and new controls are included in the current working tree. Authenticated checks
for all new controls are not yet complete.

## 5. Native vehicle volume

- [x] Diagnose the Haval volume behavior: `AudioManager.isVolumeFixed()` is true
      and Android music volume stays at 15/15 while the amplifier uses a separate
      media volume group.
- [x] Add optional `ts.framework` integration through
      `ts.car.audio.AudioExtManager`. The tested vehicle uses media group 3,
      with a range of 0–39.
- [x] Verify that the app slider changes native volume from 10 to 9, and that a
      change back to 10 on the vehicle's panel is reflected in the app slider.
      Other audio groups remain unchanged.
- [x] Avoid additional AudioTrack attenuation when native vehicle volume is
      available. Keep software volume as the fallback on other Android devices.
- [x] Persist the fallback volume and handle user volume, pause/mute, and audio
      focus ducking independently.
- [x] Pass emulator regressions for missing vendor APIs, saved volume, focus gain
      while loading, ducking, pause, and resume. Build and install the fix on the
      vehicle, with playback confirmed.

## 6. Fictional preview and repository screenshots

- [x] Populate preview mode with the fictional Maya Costa profile and 12 songs,
      artists, albums, and playlists, with 12 original vector covers.
- [x] Populate the existing account and queue modals with demo data and keep
      preview search history independent from real account preferences.
- [x] Keep preview playback simulated without audio or Spotify account access;
      add regression coverage against exposing or overwriting real search history.
- [x] Automate emulator-only screenshot capture, with a mock pairing transport
      confined to `androidTest` and an illustrative login QR code.
- [x] Capture and visually review nine screens at 1920×720 and 160 dpi: login,
      home, library, playlist, liked songs, search, search results, account, and queue.
- [x] Publish the local [screenshot gallery](../docs/screenshots/README.md) and
      reference it from the main README.
- [x] Remove 57 older app screenshots, keeping the latest nine fictional captures.

The preview update was built and checked on the emulator. The last APK installed
on the vehicle is the release with the design refactor and native volume fix.

## 7. Repository cleanup and documentation

- [x] Adopt the supplied GNU AGPL v3 license in [LICENSE](../LICENSE).
- [x] Ignore build output, APKs, dependencies, local environments, signing keys,
      logs, and editor files while keeping source, design assets, and screenshots.
- [x] Move the Pencil file to [design/impulsefy.pen](../design/impulsefy.pen), place
      its generated images in `design/assets/`, and update relative image paths.
- [x] Make [README.md](../README.md) the English default and add
      [README.pt-BR.md](../README.pt-BR.md), with links between the two versions;
      remove Impulse Home references from both READMEs.
- [x] Remove the obsolete `server/` directory, its tests and deployment files,
      the unused `relayUrl`/`RELAY_URL` build configuration, and Node/relay setup
      instructions from both READMEs. The current app needs no Impulsefy backend.
- [x] Rewrite this task history in English, distinguishing completed milestones
      from remaining real-device validation.
- [x] Remove local build output, caches, compiled JNI libraries, APKs, diagnostic
      artifacts, and the standalone icon export, leaving the files intended for
      the remote repository alongside the local Git metadata.
- [x] Center the README logo and introduction; document installation,
      development, contribution steps, project licensing, and third-party credits
      in both languages. Add `AGENTS.md` with architecture, implementation
      constraints, device guidance, and validation commands for AI contributors.

## Verification record

APK download deployment (2026-10-04): replaced the obsolete relay container on
tonton with a Python standard-library download service. Source and the signed
ARM64 APK live in `~/local-projects/impulsefy-downloads`; Docker Compose manages
the service with automatic restart on the existing `127.0.0.1:8787` port.
`https://impulsefy.paulovitor.app/`, `/impulsefy.apk`, and `/install.apk` serve
the APK as an attachment. Verified local routing, health, HEAD requests, and
rejection of unrelated paths; the public HTTPS download returned 200 and matched
the local signed APK's SHA-256 checksum. This service is only for distribution;
the Android app continues to connect directly to Spotify.

Signed Haval release test (2026-10-04): confirmed the vehicle uses `arm64-v8a`
through read-only ADB queries. Restricted release packaging and the default native
build to ARM64; x86-64 remains available for emulator tests. Built version 0.1.0
(code 1) with R8 and release lint (zero errors, 31 warnings), then ran the workflow's signing step locally
with the original private key stored in GitHub Actions secrets. Verified the
certificate fingerprint, v1/v2/v3 signatures, checksum, ZIP alignment, release
metadata, and ARM64-only native library. The signed APK is
`artifacts/impulsefy.apk`; validation details are in
`artifacts/signed-release-verification.json`. Actionlint, the seven release-gate
cases, shell syntax, and diff checks passed. No installation, commit, push, or
publication was performed.

CI/release preparation: added local GitHub Actions workflows for Rust and Android
checks, followed by signed releases after successful CI for a merged PR on `main`.
Created a private RSA release key outside the repository and configured its four
Actions secrets in `paulovitin/impulsefy`. No commit, push, or release was made.
Actionlint, Rust formatting, seven mocked release-gate cases, Gradle version
configuration, and private-key signing verification passed locally. The workflows
have not run on GitHub yet.

These are the latest recorded results for each area, not a claim that all checks
were rerun for documentation-only changes. Local logs under `artifacts/` were
removed during repository cleanup; the original filenames below identify the
checks recorded before that cleanup. The final screenshot gallery is retained.

| Area | Recorded result | Original log or artifact location |
| --- | --- | --- |
| Native catalog and playback | Eight Rust tests passed; Clippy passed with one existing vendored librespot lint-expectation warning | `artifacts/refactor-native-tests.log`, `artifacts/refactor-clippy.log` |
| Native-volume release | ARM64/ARMv7 build, R8, and release lint passed | `artifacts/native-volume-build.log` |
| Vehicle volume | Media group changed 10 → 9 → 10 and app/panel stayed synchronized | `artifacts/car-native-volume-after-touch.txt`, `artifacts/car-native-volume-synced.txt` |
| Android regression | Navigation, authentication, Keystore, JNI, AudioTrack recovery, audio focus, volume, and preview-history isolation passed | `artifacts/preview-android-tests.log` |
| Preview lint | `lintDebug` passed: zero errors, 31 warnings | `artifacts/preview-lint.log` |
| Screenshots | Nine emulator captures generated and reviewed; no real account data | `artifacts/screenshots.txt`, `docs/screenshots/` |
| Relay removal | App and Android-test Java compilation passed without the relay build configuration; README/task links and equivalent build commands checked | `artifacts/server-removal-compile.log` |

## Pending validation and current limits

- [ ] Observe real token renewal after expiration and reconnection using the
      persisted playback credential as the recovery path, without relying on an
      already active session. Mocked refresh and ordinary saved-login reconnects
      have passed; they do not establish this long-running behavior.
- [ ] Validate every redesigned screen and new control with a real account on
      the car, including shuffle, repeat, queue, resume, and voice search.
- [ ] Validate authenticated artist/album searches, library filters, saved-track
      lookups, and like/unlike operations. Some native catalog operations depend
      on Spotify Pathfinder behavior and still need live verification.

Offline downloads are not implemented. The queue represents the selection loaded
in Impulsefy. Spotify endpoint and public-client compatibility remain external
dependencies of this unofficial integration. The signed Haval APK is available at
https://impulsefy.paulovitor.app/; the former login relay has been replaced by a
download-only service.
