<!-- android-kit -->
# CLAUDE.md — AkaStudio

On-device photo curator: turns a pile of photos or a .zip into an editorial 9:16 Story (hero shot or 2–4 collage) with OKLab cross-image color harmonization, 16-bit grading and lossless/anti-compression export, for people sharing trips and events.
Android, developed on Ubuntu from the CLI. 

<!-- STACK:BEGIN (filled by /bootstrap) -->
- Origin: new · Integration branch: main
- Language: kotlin · UI: Compose · screenshot tests: compose-preview
- JDK target 17 · Gradle 9.8.0 · AGP 9.4.1 · Kotlin 2.4.20 (built-in) · compileSdk 37 · minSdk 31
- Modules: :app, :core:ingestion, :core:vision-curator, :core:color-engine, :core:collage-composer, :core:export-pipeline, :feature:studio · Architecture: Clean Architecture + MVI · DI/DB/Net: none (StudioContainer, constructor injection) / none / none
- App id: com.akane.akastudio · Launcher: .MainActivity
- Baseline: full gate green at ba0f14a (all module unit tests + app/studio screenshot tests); lint 0 errors (PROGRESS.md)
<!-- STACK:END -->

## How work flows here
- **The main session orchestrates; it doesn't implement.** Work becomes Sidequest tickets, executors work in isolated worktrees on their returned native Claude route, and the main session integrates verified results. Bounded inline-safe maintenance follows Sidequest's current rules.
- **Routing** (Sidequest profile `android-kit-claude-max`): Opus/high orchestrates, diagnoses bugs, investigates spikes, handles hard coding and reviews plans. Opus/high implements code/UI, designs tests and reviews code; validate high-impact or disputed findings with an explicit Opus/high review override. Sonnet/medium handles bounded research, mapping and mechanical edits. Haiku/low runs exact checks. Visual judgement uses Opus/medium, high for complex flows. Escalation uses Opus/xhigh with a fresh hypothesis, then Fable/high only as a last resort. Use native Claude routes and inspect effective board/ticket overrides.
- **Big or multi-step changes:** plan mode → pin the reviewed plan as a Sidequest story → dispatch independent work within routing and ownership limits.
- **UI overhaul:** `/ui-overhaul <scope>`.
- **Bugs:** use `/bug-hunt` for existing-code investigations while Sidequest is active or within a story. Required ticket checks and Sidequest review gates remain. For a separate full repository audit, enter review mode with Sidequest off, then use `/full-repo-audit` followed by `/audit-remediate` for requested fixes; they coordinate native workers independently.
- **End of a session or story:** `/wrap-up` turns corrections and decisions into rules, CLAUDE.md lines and the decision log (you approve each). Personal preferences go to auto memory ("remember that …").
- **Conventions** live in `.claude/live-rules/rules/` and are injected into every session and executor, so they're not repeated here. **Project map:** `.claude/.codebase-info/` (codebase-mapper).
- **Board:** "show the Sidequest board"; side issues mentioned mid-task get filed, not worked.
- **Plugin health / updates:** `/kit-doctor`, `/kit-refresh --update-toolshed` for project-scoped Toolshed updates. Never run the broad updater or enable Model Gateway for this project.
- **Compaction:** your last instructions are saved before compaction and re-injected after; re-read them before acting.

## Commands
- Build: `./gradlew :app:assembleDebug --console=plain -q` · Unit: `:app:testDebugUnitTest` · Lint: `:app:lintDebug`
- Screenshots: `:app:updateDebugScreenshotTest` (write refs) · `:app:validateDebugScreenshotTest` (diff)
- Full gate: `bash .claude/kit/gradle-check.sh :app:assembleDebug :app:testDebugUnitTest :app:validateDebugScreenshotTest :feature:studio:testDebugUnitTest :feature:studio:validateDebugScreenshotTest :core:ingestion:testDebugUnitTest :core:color-engine:testDebugUnitTest :core:vision-curator:testDebugUnitTest :core:collage-composer:testDebugUnitTest :core:export-pipeline:testDebugUnitTest --max-workers=2`
- Device: `./gradlew :app:installDebug -q && adb shell am start -n com.akane.akastudio/.MainActivity` · logs: `adb logcat -d --pid=$(adb shell pidof -s com.akane.akastudio) | tail -80`
- Emulator: `(no /dev/kvm on this machine: use JVM checks or a physical/remote device) emulator -avd <AVD> -no-window -no-audio &` then `adb wait-for-device shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'`

## Parallel execution

Dispatch all ready, independent work concurrently as soon as its inputs are available. This applies to implementation, audits, reviews, research, tests, tool calls and remediation. Do not impose a kit-specific agent count, RAM-based subagent cap, fixed wave size or wait-for-all barrier. Refill available native execution capacity immediately when a worker finishes. Preserve actual runtime permissions/capacity; disclose unavailable parallelism rather than bypassing it. Serialize only real dependencies or shared-resource collisions (same files, Git index, board mutation, report writer, device, build outputs). Use distinct owned files/worktrees to remove avoidable collisions. Gradle verification alone queues for the RAM-derived machine-wide command slots; waiting verification never blocks independent work or reserves a slot for a ticket's lifetime. CPU determines workers per Gradle command, not agent dispatch capacity. Do not spawn redundant work just to fill slots. Cloud model inference does not justify a local RAM-based agent cap; local commands still consume resources.
