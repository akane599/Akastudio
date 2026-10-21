# PROGRESS.md — AkaStudio

_Work items, stories and blockers live on the Sidequest board. This file keeps what outlives tickets._

## Baseline
- 2026-10-21 @ scaffold commit: build ok, 1 unit test, 0 failing.

## Decision log (never delete; one line each)
- 2026-10-21: scaffolded with android-kit snapshot: Gradle 9.8.0, AGP 9.4.1, Kotlin 2.4.20 (built-in), Compose BOM 2026.09.00, coroutines 1.11.0, screenshot plugin 0.0.1-alpha16, JDK target 17. (bootstrap)
- 2026-10-21: compileSdk 37 instead of the spec's 36: it's the snapshot's current level, and compiling against 37 covers the requested Android 17 forward-compatibility. Revisit if a dependency requires 36. (bootstrap)
- 2026-10-21: dynamic color off, placeholder palette; brand (darkroom #0B0C0E) comes from /ui-overhaul. (bootstrap)

## Audit status
| Area | Last run | Result | How |
|---|---|---|---|
| Sidequest bug hunt | — | — | `/bug-hunt` |
| Repository audit | — | — | `/full-repo-audit` |
| Audit fixes | — | — | `/audit-remediate` |
| UI / design | — | — | `/ui-overhaul` phase review |
| Lint | — | — | `./gradlew :app:lintDebug -q` |
| Device QA | — | — | Mobile MCP (when configured) or android-emulator-qa fallback skill |

## Environment notes
- No /dev/kvm on this machine: no local emulator; use JVM/screenshot checks or a physical/remote device.
