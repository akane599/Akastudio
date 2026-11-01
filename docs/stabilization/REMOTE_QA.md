# Remote Android QA

The remote owner host is `akane-linux`, reached as `akane` using the existing Tailscale SSH path and strict host-key verification. No SSH configuration, SDK/toolchain, dependencies, network rules, or existing checkout were changed. APKs must be built on the VPS and copied into the dedicated QA directory; no remote builds are used.

## 2026-10-25 UTC session

Remote evidence directory: `/home/akane/.cache/akastudio-remote-qa-20261025T212901Z`. Initial discovery found no attached devices or running emulator. Host inventory and selected AVD configs are retained in this directory.

Read-only resource-policy probe on the remote host reported 16,682,082,304 bytes RAM, 14,020,345,856 bytes available RAM, 16 CPUs, 141,127,307,264 bytes disk free, and usable KVM. Swap was 16,930,824,192 bytes total / 16,912,932,864 bytes free; swap is not counted as RAM. Relevant cgroup ancestors have unlimited memory and CPU quotas. Remote policy yields two Gradle command slots/four workers, but no Gradle commands run there. The actual probe after both guest launches and the ANGLE A/B run reported 4,146,425,856 bytes available RAM and 16,858,238,976 bytes free swap ([raw probe](evidence/remote-qa/resources-after-launch.json)).

| Guest | AVD | Owned PID | Owner-host lock | Identity |
|---|---|---|---|---|
| `emulator-5556` | `pixel-api36` | 115485 (baseline), 118038 (ANGLE A/B), 119590 (direct diagnostic), 120763 (ANGLE diagnostic); all exited | `~/.cache/akastudio-emulator-5556.lock` | API 36, x86_64, 4 KiB pages; Google APIs image revision 7, extension 17 |
| `emulator-5558` | `pixel-api37` | 115486; exited | `~/.cache/akastudio-emulator-5558.lock` | API 37, x86_64, 4 KiB pages; Google APIs image revision 7, extension 22 |

Emulator version is `37.2.12.0` (build `16428233`). Both guests use `-read-only -no-snapshot-load -no-snapshot-save -no-window -no-audio -no-boot-anim -accel on -gpu swiftshader -memory 2048 -cores 4`, with explicit ports 5556/5558. KVM acceleration is established independently by the successful accelerator probe and each owned process's `/dev/kvm`, `kvm-vm`, and `kvm-vcpu` descriptors. Actual guest rendering is **software GPU**: Google SwiftShader, OpenGL ES 3.0. KVM does not establish physical GPU coverage.

Every device command is executed on the owner host while holding that guest's lock and explicitly selecting `/usr/bin/adb -H localhost -P 5037 -s <serial>`. Untargeted initial discovery used `~/.cache/akastudio-adb-discovery.lock`. No pre-existing emulator or other session process is stopped. API 35 on the VPS remains a separate target.

## Verification status

Source identity supplied by the main session for the baseline: HEAD `299e568d5e6ed660aba535fb7afd7a96b834943d`, patch SHA-256 `95b6b976c39c1bcef13f234efcd11234b8832de74e2150442bfe37418fb800ec`, and source snapshot SHA-256 `81698a5b518e83fd8013fe13e128d6c1c24d287a6999f6b4942ba225f399bb6b` ([retained baseline manifest](evidence/remote-qa/source-manifest.json), field `snapshot_sha256`). Snapshot values identify the manifested source inputs, not the serialized JSON file bytes. The VPS final build gate passed before copying. All executed APKs were checked against supplied SHA-256 values both on the VPS and again on the remote host before installation.

| Executed baseline APK | SHA-256 |
|---|---|
| `app-debug.apk` | `296c168ff481e26dc0364dd9e232bf45c836323a798a3919143aa58a078c6dbe` |
| `app-debug-androidTest.apk` | `2f9e0d75bdb845195798a969a0b59d196aa5f6007372942e2e34cd5f2bcd983b` |
| `export-pipeline-debug-androidTest.apk` | `c29a873ff95b2c6b99834070d63489f642418dedccfea281aa9dcf74ce92c34d` |
| `color-engine-debug-androidTest.apk` | `8c1cf8fc9866767ef0552be97315206668bcb060a6b86fd722ab675fee57a410` |

The executed distribution is API 36 for platform HDR encode/decode, PNG/JPEG/WebP and alpha plus GL diagnostics; API 37 for launch/lifecycle/BACK/configuration. The unchanged canvas tests remain covered by the main session's full API 35 suite; the large studio test APK was intentionally omitted from remote installation. This is targeted compatibility coverage, not the entire suite on each API. No personal phone or physical GPU evidence is claimed.

| Guest/backend and selection | Outcome | Raw evidence |
|---|---|---|
| API 36 direct SwiftShader: HDR/codec/alpha five selected export classes | 12 passed, 0 skipped; 44.881 s | [instrument output](evidence/remote-qa/api36-targeted-export.log), [AkaQA measurements](evidence/remote-qa/api36-targeted-export-AkaQA.log) |
| API 36 direct SwiftShader: capabilities, stale GL error, production renderer | 8 run: 5 passed / 3 failed | [instrument output](evidence/remote-qa/api36-targeted-gl.log) |
| API 36 direct SwiftShader: each failed method in its own instrumentation process | All three failures reproduced | [storage](evidence/remote-qa/api36-gl-isolated-storage-error.log), [ramp](evidence/remote-qa/api36-gl-isolated-smooth-ramp.log), [4K](evidence/remote-qa/api36-gl-isolated-four-k.log) |
| API 36 direct SwiftShader: raw uploaded F16/FLOAT and log-byte readback | 3 passed, 0 skipped | [instrument output](evidence/remote-qa/api36-readback-probe-readback.log) |
| API 36 direct SwiftShader: strong GPU grading parity method | 1 failed; identity case passed, neutral/all looks failed | [instrument output](evidence/remote-qa/api36-grade-probe-grade.log), [per-case diagnostics](evidence/remote-qa/api36-color-diagnostics.log) |
| API 36 ANGLE + SwiftShader: same eight GL tests | 8 run: 7 passed / 1 failed (invalid-size allocation oracle) | [instrument output](evidence/remote-qa/api36-swangle-gl.log) |
| API 36 ANGLE + SwiftShader: same strong GPU grading parity method | 1 passed, 0 skipped | [instrument output](evidence/remote-qa/api36-swangle-grade.log), [per-case diagnostics](evidence/remote-qa/api36-swangle-color-diagnostics.log) |
| API 37 direct SwiftShader: launch, two configuration tests, three BACK tests | 6 run: launch passed; 5 test-harness errors before behavior | [instrument output](evidence/remote-qa/api37-targeted-app-lifecycle-back.log) |
| API 36 ANGLE + SwiftShader: corrected storage-error fixture, same eight GL tests | 8 passed, 0 skipped; 2.838 s | [instrument output](evidence/remote-qa/api36-swangle-corrected-gl.log) |
| API 36 ANGLE + SwiftShader: strong GPU grading parity, follow-up APK | 1 passed, 0 skipped; 3.49 s | [instrument output](evidence/remote-qa/api36-swangle-corrected-grade.log) |
| API 37 direct SwiftShader: native lifecycle harness, real system BACK and recreation | 4 passed, 0 skipped; 16.027 s | [instrument output](evidence/remote-qa/api37-native-platform-lifecycle.log) |
| API 36 ANGLE + SwiftShader: raw 3D texture direct-uniform vs sampler-parameter diagnostic | 2 passed, 0 skipped | [instrument output](evidence/remote-qa/api36-lut-swangle-lut-sampler.log), [all node measurements](evidence/remote-qa/api36-lut-swangle-color-diagnostics.log) |
| API 36 direct SwiftShader: identical raw 3D diagnostic APK | 2 failed; both paths returned opaque black at every node | [instrument output](evidence/remote-qa/api36-lut-direct-lut-sampler.log), [all node measurements](evidence/remote-qa/api36-lut-direct-color-diagnostics.log) |
| API 36 direct SwiftShader: corrected own-storage-error method with diagnostic APK | 1 passed, 0 skipped | [instrument output](evidence/remote-qa/api36-direct-corrected-storage-storage-error.log) |
| API 36 direct SwiftShader: four 3D texture format/filter variants | 4 failed, 0 skipped; 0.198 s | [instrument output](evidence/remote-qa/api36-lut-variants-direct-lut-variants.log), [all node measurements](evidence/remote-qa/api36-lut-variants-direct-color-diagnostics.log) |
| API 36 ANGLE + SwiftShader: identical four-variant APK | 4 passed, 0 skipped; 0.081 s | [instrument output](evidence/remote-qa/api36-lut-variants-swangle-lut-variants.log), [all node measurements](evidence/remote-qa/api36-lut-variants-swangle-color-diagnostics.log) |

Every completed instrumentation command above returned process exit code 0 and `INSTRUMENTATION_CODE: -1`, including failing JUnit runs. JUnit status and failure summaries, not adb's exit code alone, establish each outcome. Repeated diagnostic runs are not added to a unique test total.

## M1 contrast-pair measurements on API 36

| Azure contrast | Measured pre-sharpen ratio | Stops | Encoded result | JPEG chroma | Gain map |
|---|---|---|---|---|---|
| 0.5 | 1.1451452 | 0.1955305 | `SOCIAL_STORY_JPEG` | 4:4:4 | absent |
| 1.0 | 1.2827575 | 0.3592485 | `ULTRA_HDR_JPEG` | 4:2:0 | present, 270×480; decoded ratio max 1.3054727 |

These use the unchanged decoded SDR fixture and actual platform encode/decode. The actual export test log tag is `AkaQA`.

## Controlled GL backend diagnosis

After preserving the direct SwiftShader baseline and isolated failures, only the owned API 36 emulator was stopped under its owner-host lock. It was cold-started again using the same image, port, memory/CPU, read-only/no-snapshot flags and exact APK, changing only `-gpu swiftshader` to the supported `-gpu swangle` mode. The API 37 emulator remained running.

The baseline is GLES 3.0 / SwiftShader 4.0.0.1. The A/B is GLES 3.1 / ANGLE 2.1.17841 (git `5fa2e61da947`), Vulkan 1.3.0 SwiftShader Device (Subzero), SwiftShader driver 5.0.0. Both report colour-renderable RGBA16F with an independent clear/readback value of 2.5, and both select FLOAT readback (`readsHalf=false`). Baseline maximum 3D texture size is 8192, A/B is 2048; both support the 33-node look lattice.

The direct backend's smooth F16 ramp returned one level (all zero), while ANGLE returned 1009 levels across 0–1. The 4K absolute RGB probe error changed from 222.353/255 to 0.065/255. Strong grading parity changed from neutral error 255/255 (identity already passed at 0.001/255) to every look/fixture passing, maximum 0.378/255. This establishes a backend-specific rendering failure; it does not erase the baseline failure or prove a source fix. See [source diagnosis](GL_DIAGNOSIS.md).

The baseline invalid-size test (`GL_MAX_TEXTURE_SIZE + 1`, reported limit 8192) did not throw on either backend. Independent specification review found that this input is not guaranteed to fail. The reviewed follow-up fixture instead requests two immutable levels for a 1×1 texture, preserving the original exception/error-message assertions. The corrected method passes on both backends; all eight selected GL tests plus the strong grading parity method pass on ANGLE with the follow-up APK. The earlier failure remains preserved as evidence of the invalid test assumption. Direct-backend grading remains a separate unresolved rendering issue. Details and primary specification links are in [GL_DIAGNOSIS.md](GL_DIAGNOSIS.md).

The independently reviewed raw 3D texture diagnostic was built once on the VPS (build exit 0, `.claude/kit/logs/gradle.o1uxPjV2.log`) and copied under `color-engine-debug-androidTest-lut-probe.apk`, SHA-256 `6ae50474236556fe489d890e3a9c753dc6dc83e4d354302a75a38984b11691e2`, source snapshot `479b94137de5c1c5db9d5dff16283f8a0822af86e9e0b72114782763182b428a` ([artifact metadata](evidence/remote-qa/lut-probe-artifacts.json), [source manifest](evidence/remote-qa/lut-probe-source-manifest.json)). Both backends ran the same two test methods. ANGLE returned the exact eight known RGBA nodes with direct-uniform and sampler-parameter sampling. Direct SwiftShader returned `[0, 0, 0, 1]` at every node in both variants. A sampler-parameter-specific explanation is therefore unsupported.

The final independently reviewed four-case probe (VPS build exit 0, `.claude/kit/logs/gradle.baEW5vLt.log`) was retained as `color-engine-debug-androidTest-lut-variants.apk`, SHA-256 `9aca6765f0f7f2d9a6a7a3f8bb18110363d324d11849c2ef638a44d771ae47a2`, source snapshot `90553a439a3d6f67cb09943adae61497e925cb0be2800bc6a3c55a2dccf50284` ([artifact metadata](evidence/remote-qa/lut-variants-artifacts.json), [source manifest](evidence/remote-qa/lut-variants-source-manifest.json)). Direct SwiftShader failed RGB16F NEAREST, RGB16F direct LINEAR, RGB16F sampler-parameter LINEAR, and padded RGBA16F direct LINEAR. All RGB values were zero; RGBA16F alpha was 1 for the first four nodes and 0 for the last four. ANGLE returned every exact known node in all four variants with the identical APK. Filtering-only and RGB-only-format explanations are therefore unsupported too.

Disposition: retain the legacy direct SwiftShader 4 backend failure as a tested backend limitation with upload-versus-fetch ambiguity; use the verified ANGLE + SwiftShader 5 configuration for project software-GPU QA. No production workaround, shader change, tolerance change, skipped test, or physical-GPU conclusion follows from these bounded probes. The investigation stopped after this controlled matrix, as independently reviewed and directed by the main session.

## API 37 test harness boundary

All five Compose configuration/BACK failures originate in `androidx.test.espresso.base.InputManagerEventInjectionStrategy.initialize`: reflection of `android.hardware.input.InputManager.getInstance()` raises `NoSuchMethodException` in the API 37 image. `Espresso.onIdle` fails before the tested behavior. These results do not establish successful configuration or Ready-state BACK behavior, and the dependencies were not changed.

A separate platform-input check confirmed AkaStudio's own window had focus, issued `input keyevent KEYCODE_BACK` from the empty studio, and observed no remaining resumed AkaStudio activity ([raw record](evidence/remote-qa/api37-manual-empty-back.log), [pre-BACK screenshot](evidence/remote-qa/api37-empty-studio.png)). The screenshot was visually inspected and shows the empty studio and both import actions.

The independently reviewed native harness subsequently passed all four API 37 cases: Empty BACK finishes the activity; Ready BACK offers Keep/Discard and discards through the dialog; drawer BACK closes the drawer first; orientation changes recreate the activity while retaining the ViewModel and Ready story. It uses native activity/accessibility/system-input seams instead of Espresso. These are additional tests; the five original Espresso failures remain visible and were not ignored or disabled. The original large-font Compose assertion remains without passing API 37 evidence.

## Follow-up test artifacts

The VPS follow-up build exited 0 (`.claude/kit/logs/gradle.1tx6g8Dj.log`), source snapshot `96b2920a33d5773faf1e5eb34413b2ae9bc605bb095ad8c669ec2d9020fd6f2d`. The production app APK stayed byte-identical to the baseline. Only the two test APKs below were copied under distinct cache names and revalidated before installation; original test APKs were retained ([metadata](evidence/remote-qa/test-followup-artifacts.json)).

| Remote follow-up filename | SHA-256 |
|---|---|
| `app-debug-androidTest-native.apk` | `80d9f3ca870d047538828ecf7019c027df28999e3792195db6f3caa990e55a7c` |
| `color-engine-debug-androidTest-corrected.apk` | `3098729e9194625546713f6ca9ebbafb8fdb6e3e55877cd6fba5418a9a0c2ee8` |

## Evidence files

The remote directory contains `host-inventory.txt`, per-guest JSON with exact launch arguments, `api*-avd-config.txt`, `api*-emulator.log`, `api*-kvm-fds.txt`, device identities, exact test plans, full instrumentation output, filtered measurement logcat, and source/artifact metadata. A local copy of these small evidence files is retained in [evidence/remote-qa](evidence/remote-qa/). APKs remain only in the dedicated remote cache and existing VPS build outputs. Buffers were never cleared.

Cleanup completed on 2026-10-25 at 22:05 UTC. The final owned emulator PIDs 120763 and 115486 were verified against their expected AVD/port/read-only command identities and terminated gracefully under their respective owner-host locks. Both `/proc` entries are absent. The first discovery-locked postcheck briefly showed `emulator-5558` offline; the subsequent final inventory is empty ([cleanup record](evidence/remote-qa/cleanup.json)). Earlier API 36 processes exited during the controlled backend restarts. The existing adb server and AVD data were preserved, along with the dedicated APK/log cache. Final available RAM was 14,066,556,928 bytes ([resource record](evidence/remote-qa/resources-after-cleanup.json)); final retained APK sizes/hashes are recorded in [retained-artifacts-final.json](evidence/remote-qa/retained-artifacts-final.json). The unused studio APK placeholder is zero bytes and was never installed or executed.

## 2026-11-02 session

Source `508b691` (local main). APKs built on build-server and copied to `/home/akane/.cache/akastudio-remote-qa-20261102/apk/` (checksums verified with `SHA256SUMS`). Emulators `pixel-api35` on 5556 and `pixel-api37` on 5558, both `-read-only -no-snapshot-load -no-snapshot-save -no-window -no-audio -no-boot-anim -accel on -gpu swangle -memory 2048 -cores 4`, started without holding the lock and stopped with `adb emu kill` at the end. Every device step ran under `~/.cache/akastudio-emulator-<port>.lock` with explicit `adb -H localhost -P 5037 -s emulator-<port>`. Scripts `run-suites.sh` and `ui.sh` are in that directory. Gotchas: the remote login shell is zsh (run scripts with bash), and `adb shell` reads stdin, so it eats the rest of a script piped over SSH; run scripts from a file. Pushed photos need `content call --uri content://media --method scan_volume --arg external_primary` before the photo picker shows them.

| Run | Result |
| --- | --- |
| API 35, all 7 instrumentation APKs | 142/142: app 21, studio 12, ingestion 19, color 33, curator 13, composer 14, export 30 |
| API 37, all 7 instrumentation APKs | core 109/109; app 6 pass / 14 fail / 1 assumption; studio 0 / 12. All 26 failures are `NoSuchMethodException: InputManager.getInstance` from Espresso under the Compose test rule |
| API 37, release APK (throwaway signature) | launch, photo picker import (5), curation, Social and Ultra HDR export, trim memory, process death: no crash or ANR |

Evidence (gitignored): `docs/stabilization/evidence/remote-qa-20261102/`.
