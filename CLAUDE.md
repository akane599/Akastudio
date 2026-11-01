# CLAUDE.md — AkaStudio

On-device photo curator: turns a pile of photos or a .zip into an editorial 9:16 Story (hero shot or 2–4 collage) with OKLab cross-image color harmonization, 16-bit grading and lossless/anti-compression export, for people sharing trips and events.
Android, developed on Ubuntu from the CLI. 

<!-- STACK:BEGIN -->
- Origin: new · Integration branch: main
- Language: kotlin · UI: Compose · screenshot tests: compose-preview
- JDK target 17 · Gradle 9.8.2 · AGP 9.4.1 · Kotlin 2.4.21 (built-in) · compileSdk 37 · minSdk 31
- Modules: :app, :core:ingestion, :core:vision-curator, :core:color-engine, :core:collage-composer, :core:export-pipeline, :feature:studio · Architecture: Clean Architecture + MVI · DI/DB/Net: none (StudioContainer, constructor injection) / none / none
- App id: com.akane.akastudio · Launcher: .MainActivity
- Baseline: full JVM gate (960 tests, screenshots) + lintDebug 0 errors + debug/release APK/AAB green, and device suites 118/118 on emulator-5554 (API 35), for the 2026-10-26 stabilization source committed at f927c0f; details in `docs/stabilization/HANDOVER.md` and `M1_COMPLETION.md`
<!-- STACK:END -->

## How work flows here
- **Native Claude Code orchestration.** Sidequest and Model Gateway are gone (2026-11-01); the main Opus/high session plans, implements or delegates to subagents per `.claude/rules/orchestration.md` and the global `orchestration` skill, then integrates and verifies. All routes are Claude models; there is no cross-provider review.
- **Big or multi-step changes:** plan mode → Opus/xhigh plan review for architecture/contract/export-format changes → parallel workers in worktrees or on disjoint files → integrate → gate → independent fresh-context review before commit.
- **UI overhaul:** `/ui-overhaul <scope>`.
- **Bugs:** `/bug-hunt <scope>` for a targeted hunt (Android checklist, fix from a reproducing test); `diagnosing-bugs` for a known symptom. Whole-repo: `/full-repo-audit`, then `/audit-remediate` for the fixes you pick.
- **End of a session or story:** `/wrap-up` turns corrections and decisions into rules, CLAUDE.md lines and the decision log (you approve each). Personal preferences go to auto memory ("remember that …").
- **Conventions** live in `.claude/rules/` (loaded into every session automatically), so they're not repeated here. **Project map:** `.claude/.codebase-info/` (hand-maintained; refresh the affected pages when structure changes). **Stabilization handover:** `docs/stabilization/HANDOVER.md`.
- **Side issues** mentioned mid-task go to PROGRESS.md's "Open issues", not into the current change.
- **Compaction:** your last instructions are saved before compaction and re-injected after; re-read them before acting.
- **Codex** works from `AGENTS.md`; keep it consistent with these rules when conventions change.

## Commands
- Build: `./gradlew :app:assembleDebug --console=plain -q` · Unit: `:app:testDebugUnitTest` · Lint: `:app:lintDebug`
- Screenshots: `:app:updateDebugScreenshotTest` (write refs) · `:app:validateDebugScreenshotTest` (diff)
- Full gate: `bash .claude/kit/gradle-check.sh :app:assembleDebug :app:testDebugUnitTest :app:validateDebugScreenshotTest :feature:studio:testDebugUnitTest :feature:studio:validateDebugScreenshotTest :core:ingestion:testDebugUnitTest :core:color-engine:testDebugUnitTest :core:vision-curator:testDebugUnitTest :core:collage-composer:testDebugUnitTest :core:export-pipeline:testDebugUnitTest lintDebug --max-workers=2`
- Device: `./gradlew :app:installDebug -q && adb shell am start -n com.akane.akastudio/.MainActivity` · logs: `adb logcat -d --pid=$(adb shell pidof -s com.akane.akastudio) | tail -80`
- Emulator: run `adb devices` before deciding device QA is impossible. No /dev/kvm here means no local AVD, but a shared redroid Android 15 emulator (`emulator-5554`, x86_64, SwiftShader GL) is usually attached. It also hosts another session's app (com.akane.inspiration): install/uninstall only com.akane.akastudio, one owner on the device at a time (flock lock, see `.claude/rules/device-testing.md`).

## Parallel execution

Dispatch all ready, independent work concurrently as soon as its inputs are available. This applies to implementation, audits, reviews, research, tests, tool calls and remediation. Do not impose an arbitrary agent count, RAM-based subagent cap, fixed wave size or wait-for-all barrier. Refill available native execution capacity immediately when a worker finishes. Preserve actual runtime permissions/capacity; disclose unavailable parallelism rather than bypassing it. Serialize only real dependencies or shared-resource collisions (same files, Git index, report writer, device, build outputs). Use distinct owned files/worktrees to remove avoidable collisions. Gradle verification alone queues for the RAM-derived machine-wide command slots; waiting verification never blocks independent work or reserves a slot for a task's lifetime. CPU determines workers per Gradle command, not agent dispatch capacity. Do not spawn redundant work just to fill slots. Cloud model inference does not justify a local RAM-based agent cap; local commands still consume resources.
