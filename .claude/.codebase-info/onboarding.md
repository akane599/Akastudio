# Onboarding
*Last Updated: 2026-10-21*

## Commands (from repo root)
- Build: `./gradlew :app:assembleDebug --console=plain -q`
- Unit tests: `./gradlew :app:testDebugUnitTest --console=plain -q`
- Screenshots: `:app:updateDebugScreenshotTest` (write refs, UI tickets only) / `:app:validateDebugScreenshotTest` (diff)
- Preferred wrapper: `bash .claude/kit/gradle-check.sh <tasks>`; get `--max-workers` from `python3 .claude/kit/resource-policy.py`.

## Environment
- `ANDROID_HOME=/home/akane/android-sdk`; do not create `local.properties`.
- No `/dev/kvm` on this machine, so no local emulator. Use JVM and screenshot tests, or a physical/remote device.

## Where to start
- New feature module: add to `settings.gradle.kts`, wire deps via `gradle/libs.versions.toml`, and add any dependency only when a ticket names it.
- UI state pattern: copy `ui/home/HomeViewModel.kt` (StateFlow + `@Immutable` + `ImmutableList`).
- Work is planned as Sidequest stories; see `CLAUDE.md` "How work flows here".
