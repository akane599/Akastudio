# API37 window/lifecycle test infrastructure

Date: 2026-10-26. This is a bounded instrumentation fallback for the current pinned test dependencies; production code and dependency/toolchain versions are unchanged.

## Original failures remain evidence

On remote `akane-linux` / `emulator-5558` (API37), the original app smoke test passes. The original three `StudioBackDeviceTest` cases and two `StudioConfigurationDeviceTest` cases fail before their behavior assertions: Espresso idle synchronization initializes `InputManagerEventInjectionStrategy`, which looks up the hidden `android.hardware.input.InputManager.getInstance()` and receives `NoSuchMethodException`. The trace establishes lookup incompatibility, not whether the OS removed the method or denied reflective access.

Those five original failures remain failures. No original test is removed, disabled, conditionally skipped, or rewritten; no screenshot reference is changed. Native fallback results must be reported separately from them.

## Added native coverage

`app/src/androidTest/kotlin/com/akane/akastudio/studio/StudioPlatformLifecycleDeviceTest.kt` launches the real `MainActivity` with `ActivityScenario`. It obtains the activity's own `StudioViewModel` through the existing factory, imports the existing eight `QaPhotos` MediaStore fixtures through `ImportPhotos` on main, and waits for the real pipeline off main. It uses no Compose test rule, Espresso call, replacement UI, or production test hook.

| Test | Evidence required |
| --- | --- |
| `systemBackInEmptyFinishesTheActivity` | Real accessible dropzone, then system BACK destroys the activity. |
| `systemBackInReadyKeepsThenDiscardsThroughTheDialog` | BACK opens the actual discard dialog; accessibility clicks on Keep retain the story, then Discard returns to accessible Empty/dropzone while the activity stays resumed. |
| `systemBackClosesTheModeDrawerBeforeAskingToDiscard` | Accessibility click opens the real mode chip/drawer; first BACK closes the drawer and retains the story without the discard dialog; second BACK shows the discard dialog. |
| `orientationChangesRecreateTheActivityAndRetainTheReadyStory` | Requested portrait and landscape orientations each recreate the activity; each retains the same ViewModel and curation identity, photo IDs/mode, grade and template, reaches RESUMED, and exposes the mode chip. The first requested orientation differs from the starting orientation. |

BACK uses public `UiAutomation.executeShellCommand("input keyevent KEYCODE_BACK")`, drains/closes the returned descriptor, and waits through public instrumentation idle synchronization. It exercises system input rather than directly invoking `OnBackPressedDispatcher`. Dialog and drawer actions use public `AccessibilityNodeInfo.ACTION_CLICK` on the visible app node or its clickable ancestor. Failed/missing accessibility nodes fail the test; there is no silent ViewModel action fallback. QA media is removed in teardown using the existing fixture cleanup.

## Execution and limits

Main session assembles the test APK; the remote device owner runs it under the owner-host device lock with an explicit target. This class can run on API35/36/37; it has no API-specific skip.

Instrumentation filter (four cases):

```text
com.akane.akastudio.studio.StudioPlatformLifecycleDeviceTest
```

Single-case filter format:

```text
com.akane.akastudio.studio.StudioPlatformLifecycleDeviceTest#systemBackInEmptyFinishesTheActivity
```

Follow-up test APK assembly passed (exit0; `.claude/kit/logs/gradle.1tx6g8Dj.log`). Remote API37 executed this class: **4/4 passed**,16.027seconds. Exact source/APK hashes and raw results are linked from REMOTE_QA.md and `evidence/test-followup-artifacts.json`. These native tests do not establish the original large-font test's drawn text or non-overlap geometry assertions, screenshot equivalence, predictive BACK gesture animation, process-death restoration, or physical-device/GPU correctness or preview RGB quality. They verify actual key BACK/window behavior and configuration recreation without claiming the five original Compose cases passed.
