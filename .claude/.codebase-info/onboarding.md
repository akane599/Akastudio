# Onboarding
*Last Updated: 2026-10-21*

## Commands (from repo root)
- Preferred wrapper: `bash .claude/kit/gradle-check.sh <tasks>` (keeps Gradle's exit code, full log in `.claude/kit/logs/`, tail on failure). Get `--max-workers` from `python3 .claude/kit/resource-policy.py`.
- Build: `./gradlew :app:assembleDebug --console=plain -q`
- Per-module unit tests (narrowest first): `:core:ingestion:testDebugUnitTest`, `:core:vision-curator:testDebugUnitTest`, `:core:color-engine:testDebugUnitTest`, `:core:collage-composer:testDebugUnitTest`, `:core:export-pipeline:testDebugUnitTest`, `:feature:studio:testDebugUnitTest`, `:app:testDebugUnitTest`. One class: append `--tests '<Class>'`.
- Screenshots (Compose preview testing): `:feature:studio:validateDebugScreenshotTest` / `:app:validateDebugScreenshotTest`; refs are written with `update…` by UI tickets only.
- Lint: `:app:lintDebug`.
- Device: `./gradlew :app:installDebug -q && adb shell am start -n com.akane.akastudio/.MainActivity`.

## Environment
- `ANDROID_HOME=/home/akane/android-sdk`; do not create `local.properties`.
- No `/dev/kvm`: no local emulator. Use JVM tests, screenshot tests, or a physical/remote device. GPU grading cannot run on the JVM; it is checked on a device with `GradingParityProbe` (see patterns.md).
- Work flows through Sidequest tickets (`CLAUDE.md`); decisions are logged in `PROGRESS.md`.

## Where to start
- Follow a photo through the app: [entry-points.md](entry-points.md), then [architecture.md](architecture.md).
- Adding behaviour to a core module: edit the pure logic package, keep the module's `api/` surface frozen unless the ticket says otherwise ([patterns.md](patterns.md)).
- UI: `feature/studio/.../ui/` composables take state and lambdas; add intents in `mvi/StudioIntent.kt` and handle them in `StudioReducer`/`StudioViewModel`.
