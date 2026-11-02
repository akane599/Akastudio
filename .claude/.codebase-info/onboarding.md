# Onboarding
*Last Updated: 2026-10-21*

## Commands (from repo root)
- Preferred wrapper: `bash .claude/kit/gradle-check.sh <tasks>` (keeps Gradle's exit code, full log in `.claude/kit/logs/`, tail on failure). Get `--max-workers` from `python3 .claude/kit/resource-policy.py`.
- Build: `./gradlew :app:assembleDebug --console=plain -q`
- Per-module unit tests (narrowest first): `:core:ingestion:testDebugUnitTest`, `:core:vision-curator:testDebugUnitTest`, `:core:color-engine:testDebugUnitTest`, `:core:collage-composer:testDebugUnitTest`, `:core:export-pipeline:testDebugUnitTest`, `:feature:studio:testDebugUnitTest`, `:app:testDebugUnitTest`. One class: append `--tests '<Class>'`.
- Screenshots (Compose preview testing): `:feature:studio:validateDebugScreenshotTest` / `:app:validateDebugScreenshotTest`; refs are written with `update…` only by changes that intentionally alter UI.
- Lint: `:app:lintDebug`.
- Device tests (module `src/androidTest`): `ANDROID_SERIAL=emulator-5554 flock $HOME/.cache/akastudio-emulator-5554.lock bash .claude/kit/gradle-check.sh :<module>:connectedDebugAndroidTest --max-workers=2`.
- Device: `./gradlew :app:installDebug -q && adb shell am start -n com.akane.akastudio/.MainActivity`.

## CI (`.github/workflows/ci.yml`, GitHub Actions, JDK 21 Temurin)
- Runs on every push to `main`, every PR, and manual dispatch. Job `check` runs the full gate plus `:app:lintDebug` with `--continue` and uploads `app-debug-apk` (reports only on failure). Job `release` runs `:app:assembleRelease :app:bundleRelease` (R8) and uploads the mapping.
- Device suites (`connectedDebugAndroidTest`) are not in CI; run them locally on emulator-5554.

## Environment
- `ANDROID_HOME=/home/akane/android-sdk`; do not create `local.properties`.
- No `/dev/kvm`, so no local AVD. A shared redroid Android 15 emulator (`emulator-5554`, x86_64, SwiftShader GLES 3.2) is normally attached: run `adb devices` first. It also hosts another session's app (`com.akane.inspiration`); touch only `com.akane.akastudio` and its `.test` packages, always under the flock lock. GPU grading, ML models, MediaStore, codecs and Compose window/BACK behaviour are verified there by the androidTest suites (see patterns.md).
- Work flows as described in `CLAUDE.md`; decisions are logged in `PROGRESS.md`.

## Where to start
- Follow a photo through the app: [entry-points.md](entry-points.md), then [architecture.md](architecture.md).
- Adding behaviour to a core module: edit the pure logic package, keep the module's `api/` surface frozen unless the task says otherwise ([patterns.md](patterns.md)).
- UI: `feature/studio/.../ui/` composables take state and lambdas; add intents in `mvi/StudioIntent.kt` and handle them in `StudioReducer`/`StudioViewModel`.
