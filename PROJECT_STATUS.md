# PROJECT_STATUS — Sticker Studio

**Purpose:** Android app (Kotlin + Compose) to make static/animated WhatsApp sticker packs.
**Stack:** Kotlin 2.0.21, Compose BOM 2024.10.01, Room 2.6.1 (KSP), DataStore, Coil, ML Kit selfie segmentation, AGP 8.7.3, Gradle 8.9 wrapper, minSdk 26 / target 35.
**Identity (never change):** applicationId `com.stickerstudio.app`; same signing key forever; versionCode must increase each release.

## Verified (real evidence)
- Pure-Kotlin modules (MiniJson, Sha256, limits, WebP parser/muxer, project JSON, pack validator, version utils, update checker) compile with kotlinc 2.0.21.
- **53 JUnit tests pass** (`JUnitCore`, last run this session: `OK (53 tests)`).
- All `R.string.*` keys used in code exist in both `values/strings.xml` and `values-ar/strings.xml` (script check, 222 keys, none missing/unused).
- All XML resources/manifest are well-formed; both workflow files are valid YAML.
- Gradle wrapper files generated (8.9).

## Update 2026-10-24
- CI **Build & test passed** on GitHub for commit 306973e (unit tests + debug APK build). JVM crop tests: 63 pass.
- New: unified picker (image/GIF/video), live looping preview in the animated editor, aspect/free crop, new theme/home/create/editor design.
- Still not verified on a device: crop gestures, live preview smoothness, new screens' look.

## NOT verified
- **Any compilation of Android-dependent code** (no Android SDK, Maven/Google blocked here): data, UI, renderer, encoder, converter, provider, updater network/verifier.
- No APK built; no instrumented tests run (`androidTest` is written only); no run on a device/emulator.
- WhatsApp import on a real phone; animated conversion size results; ML Kit background removal; in-place update on a device.
- Workflows never executed.

## Known risks
- First CI build will likely surface compile errors in unverified code. Paste the Actions log to fix.
- Launcher icon is a simple original vector; Room schema JSON appears after first build (commit `app/schemas`).
- No Room migrations yet (DB v1); future schema changes need a `Migration`.

## Next exact step
1. Create a GitHub repo, set `UPDATE_REPO_OWNER` in `gradle.properties`, push. 2. Run **Build & test** Action; fix errors. 3. Follow `docs/RELEASING.md` (keystore, secrets, tag `v1.0.0`). 4. Install the APK, test WhatsApp export and an update to a higher versionCode.
