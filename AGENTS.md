# AkaStudio

On-device Android photo curator: photos or ZIP → editorial 9:16 stories, with color harmonization, grading, collage composition and export. Kotlin, Compose, Clean Architecture + MVI; constructor injection through `StudioContainer`.

Read [the project map](.claude/.codebase-info/INDEX.md) for architecture and entry points, [PROGRESS.md](PROGRESS.md) for decisions, and [the operational handover](docs/stabilization/HANDOVER.md) for current machines, targets and verification evidence. [The migration record](docs/stabilization/CODEX_MIGRATION.md) explains the instruction boundary.

## Working agreements

- Sidequest and Model Gateway are not installed for this project. The main session may implement directly. Use GPT-6.1 Sol/high for the main session, implementers and reviewers; report the actual route and unavailable capabilities. Use xhigh only for a critical unresolved question, stating the question and concrete evidence before escalation. Instructions express policy; they do not change runtime model settings.
- Preserve existing user edits and untracked files. Do not automatically pull, reset, clean, stash, or create/switch branches. Do not stop processes or interfere with another session's applications.
- Keep changes scoped. Do not install tools, add dependencies, change toolchain/SDK/JDK versions, or alter machine settings unless the task explicitly includes it. `ANDROID_HOME` supplies the SDK; do not create/edit `local.properties`.
- Keep `.claude/`, `CLAUDE.md`, shared kit/installer and global configuration intact unless the task explicitly targets them. They contain preserved source material; their routing, hooks and command conventions are not Codex instructions.
- Never read, print or commit secrets or machine-specific sensitive files: keystores, `local.properties`, `google-services.json`, `.env*`, `.claude-plugin-config.json`.

## Code conventions

- Keep module `api/` seams and factory boundaries stable. Put pure Kotlin logic behind thin Android adapters; retain constructor injection and existing MVI patterns.
- Follow Kotlin official style: trailing commas, `val` by default, sealed state/events, no `!!` outside tests. Keep UI state immutable and use existing persistent collection types.
- Composables take state and callbacks; hoist state to the ViewModel, expose `StateFlow`, collect with lifecycle awareness. `modifier: Modifier = Modifier` is the first optional parameter.
- Use existing theme tokens, spacing and `strings.xml`; keep screen files free of literal colors and raw layout/type values. Preserve the existing Compose preview screenshot framework; do not install a replacement.
- Add relevant behavior tests for new logic and regression tests for fixes where feasible, using existing fakes and platform seams. Thread-name assertions compare prefixes because coroutine debug adds suffixes. Never delete, disable or `@Ignore` a failing test to make checks pass.
- For meaningful behavior changes, obtain an independent review before integration and resolve correctness findings with evidence. Reuse current valid evidence; report pending or unavailable verification honestly. Consult `.claude/kit/android-bug-checklist.md` for Android reviews. Claude-side conventions live in `.claude/rules/`; keep both in step.

## Verification

Run from the checkout root. First run `python3 .claude/kit/resource-policy.py`; use its current `gradle_command_limit` for machine-wide active Gradle commands and `gradle_workers_per_command` for `--max-workers=N`. RAM governs command slots; CPU governs workers per command. This is advisory coordination, not an enforced lock or an agent-count limit. Queue occupied build capacity while continuing independent work.

Use `bash .claude/kit/gradle-check.sh <tasks> --max-workers=N`: it retains full logs in `.claude/kit/logs/` and returns Gradle's real exit status. If absent, use raw `./gradlew <tasks> --console=plain -q`; never use a grep/head pipeline as the pass/fail oracle. Batch relevant tasks and start with the narrowest check.

| Purpose | Task |
| --- | --- |
| Debug build | `:app:assembleDebug` |
| Module unit tests | `:<module>:testDebugUnitTest` (optionally `--tests '<Class>'`) |
| Lint | `lintDebug` |
| UI screenshots | `:feature:studio:validateDebugScreenshotTest`, `:app:validateDebugScreenshotTest` |
| Device tests | `:<module>:connectedDebugAndroidTest` under the device lock |

Never update screenshot references automatically. Reference updates require explicit task scope and visual review; preserve unrelated references. Device-only seams (MediaStore/ContentResolver, real-file EXIF, GL/EGL, ML, codecs and Compose window/BACK) require instrumented evidence; JVM/screenshots alone do not establish their correctness.

## Shared devices

Discover available devices before declaring device QA unavailable, then select an explicit serial and execution host. Every device interaction and connected test must use the shared lock on the device's owner host, with explicit target selection (`adb -s <serial>` or `ANDROID_SERIAL=<serial>`). For the existing build-server `emulator-5554`, the lock is `~/.cache/akastudio-emulator-5554.lock` on that host; a local lock cannot coordinate a remote device. Follow the handover for the current host/target mapping.

Touch only `com.akane.akastudio` and its instrumentation packages. Do not stop other processes/apps, reboot or reconfigure shared devices, or change global settings. Test media belongs under `/sdcard/Pictures/AkaStudioQA/` or `/sdcard/Download/`. Report occupied locks and missing device evidence instead of bypassing coordination.
