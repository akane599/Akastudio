---
name: device-qa
description: Run AkaStudio instrumented tests and device checks on the shared emulator-5554 or the user's SM-S938B phone, with the device lock, the XML verdict on TCP serials, focus-gated key events, and peak-RSS / thermal evidence. Use whenever a change touches device-only APIs (MediaStore, real-file EXIF, GL/EGL, MediaPipe/LiteRT, codecs, Compose window/BACK) or when device evidence is asked for.
---

# Device QA for AkaStudio

The hard rules live in `.claude/rules/device-testing.md` and are enforced for raw commands by `.claude/hooks/guard-bash.py`. This skill covers the procedures.

## Devices

| Serial | Device | Lock (on build-server) | Notes |
|---|---|---|---|
| `emulator-5554` | redroid Android 15, x86_64, SwiftShader GL | `~/.cache/akastudio-emulator-5554.lock` | shared with com.akane.inspiration; software GL only |
| `100.70.96.59:5555` | SM-S938B, API 37 (serial `R5CYA0WKL6Y`) | `~/.cache/akastudio-akadroid.lock` | **authorized per session only**; the user uses it live |

1. Run `adb devices -l` first. Don't call device QA impossible before you've checked.
2. Use the phone only if the user has authorized it in this session. Physical-GPU and HDR evidence needs the phone. Emulator results say nothing about the GPU.

## Scripts (in `scripts/`)

Every script takes the device lock itself, holds it for the whole run, and exports `ANDROID_SERIAL`. **Don't wrap them in `flock`.** A nested flock on the same file waits for itself until the timeout.

| Script | Purpose | Exit codes |
|---|---|---|
| `run-suite.sh <serial> <module> [--class FQCN[#method]] [--stop-mobilecli]` | `:<module>:connectedDebugAndroidTest` with `--max-workers` taken from `resource-policy.py`. The verdict comes from the **fresh** `TEST-*.xml` only. On a TCP serial the Gradle exit code is ignored (AGP 9.4.1 bug). | 0 pass · 1 fail · 2 no results/usage · 3 lock busy · 4 device missing |
| `focus.sh <serial>` | Prints `mCurrentFocus`. Exits 0 only when AkaStudio is in front. | 0 / 1 |
| `peak-rss.sh <serial> reset\|read [pkg]` | `reset` writes `5` to `/proc/<pid>/clear_refs` through `run-as`, which resets VmHWM. `read` prints VmHWM and VmRSS. Debuggable builds only. | |
| `thermal.sh <serial>` | Thermal status plus the live "Current temperatures from HAL" block. The cached block is stale and is skipped. | |
| `xml-verdict.py <results-dir> [marker]` | Summarizes JUnit XML. Used by `run-suite.sh`. | 0 / 1 / 2 |

`DEVICE_LOCK_WAIT=<seconds>` changes the lock wait (default 900 s). If the lock is still busy after that, report it. Don't bypass it.

## Procedures

**Instrumented tests.** Use `bash .claude/skills/device-qa/scripts/run-suite.sh emulator-5554 core:export-pipeline`. Add `--class com.akane…FooDeviceTest` to narrow the run. On the phone, add `--stop-mobilecli` when the tests use UiAutomation, because mobile-mcp's agent holds the single UiAutomation connection.

**Raw instrumentation (fallback, or a second opinion on the phone).** The test APK must already be installed. Run it under the lock:
```
ANDROID_SERIAL=100.70.96.59:5555 flock $HOME/.cache/akastudio-akadroid.lock \
  adb shell am instrument -r -w -e class <FQCN> <namespace>.test/androidx.test.runner.AndroidJUnitRunner
```
Read `INSTRUMENTATION_STATUS_CODE` for each test and the final `OK (n tests)` or `FAILURES!!!`. Each module's namespace is in its `build.gradle.kts`. For example, `com.akane.akastudio.core.export` has the instrumentation package `com.akane.akastudio.core.export.test`.

**Launching the app and reading logs.**
```
ANDROID_SERIAL=emulator-5554 flock $HOME/.cache/akastudio-emulator-5554.lock sh -c \
  './gradlew :app:installDebug -q && adb shell am start -n com.akane.akastudio/.MainActivity'
ANDROID_SERIAL=emulator-5554 flock $HOME/.cache/akastudio-emulator-5554.lock sh -c \
  'adb logcat -d --pid=$(adb shell pidof -s com.akane.akastudio) | tail -80'
```

**Key events and taps on the phone.** Run them only after `focus.sh` returns 0, for example `focus.sh 100.70.96.59:5555 && <input command>`. Send nothing while a personal app is in front. Gallery apps are fine for display checks. Social and messaging apps need the user's explicit permission each time, and nothing is ever sent, posted or uploaded.

**Memory per phase.** Run `peak-rss.sh <serial> reset` just before the phase and `peak-rss.sh <serial> read` right after it. Report VmHWM as the exact peak. The `dumpsys meminfo` sampling interval misses short spikes, so it is context, not the peak. For in-process phases, `PhysicalMemoryProbeDeviceTest` does the same reset from `/proc/self`.

**Thermal.** Run `thermal.sh <serial>` before and after the workload. Quote the HAL values and the thermal status. Never quote "Cached temperatures".

**HDR headroom (phone).** Read `displayBrightnessNits / sdrWhitePointNits` from `adb shell dumpsys SurfaceFlinger`, under the lock. It is platform-reported, never a measured nit value.

## Reporting

- Name the serial, the commit and the exact command for each result. Quote the XML summary line (`tests=N failed=M`).
- A device test that exposes a product bug stays failing and gets reported. Never `@Ignore` or delete it.
- Raw evidence goes under `docs/stabilization/evidence/<run>/`, which is gitignored. Summaries go in `docs/stabilization/` or PROGRESS.md's "Audit status" table.
