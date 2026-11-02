# Akadroid physical-device QA — 2026-10-26

**Latest continuation — supersedes the historical disposition below:** [M1_COMPLETION.md](M1_COMPLETION.md) resolves tile precision and drawer/BACK without tolerance relaxation. Physical color33/33 and native lifecycle4/4 pass; final C1 export regressions2/2, installed workflow1/1 and HDR viewer1/1 pass. M1 selects C shoulder/shared SDR rendition/full-resolution scalar gain; all10 natural production cases pass fixed guards. Final installed debug APK is `1aec8035398dff4b604bc6c26bd68cc13cda7f25102a33f09dc059621676afd1`. Direct VPS target `100.70.96.59:5555` used physical display0 with no virtual display/mirroring. Final current headroom2.2847688 is separate from maximum5; final night needs2.542831, so full physical-night gain is not established. Owned lock/mobile daemon released; personal data and public comparison files retained. Three rejected-RGB prototype fixture exports await the next connection; [resume record](M1_RESUME.md). The rest of this file is the preserved earlier phase.

This session has physical GPU/file evidence and physical-display headroom telemetry. It is **not an all-green gate or M1 approval**. Option A remains the stabilization baseline; no RGB gain prototype or production change was made. The user authorized this personal phone for this session only. Future sessions must rediscover availability and obtain their own authorization.

## Source, artifacts and route

Read HANDOVER.md and M1_DECISION.md before device work. HEAD remains `299e568d5e6ed660aba535fb7afd7a96b834943d`. All 538 files in the prior final manifest matched, and every indexed APK/AAB matched its SHA256 before work ([initial identity](evidence/akadroid-session/initial-identity.json)). The installed production debug APK is:

`296c168ff481e26dc0364dd9e232bf45c836323a798a3919143aa58a078c6dbe`

Its production source identity remains `81698a5b518e83fd8013fe13e128d6c1c24d287a6999f6b4942ba225f399bb6b`. The phone initially had an older APK (`16b11011c6cfbbb83ad3a7ed123e1459d24f30cefe7d2b45fcd9ee7157348958`). `install -r` succeeded with the existing signature; no uninstall/data clear was used to update AkaStudio. Current additive/diagnostic test sources and APKs have their own [source manifest](evidence/akadroid-session/physical-source-manifest.json) and [artifact index](evidence/akadroid-session/physical-artifacts.json). Production inputs still match the initial manifest. No commit/push/PR/publication, dependency/toolchain change, screenshot reference update or tolerance change occurred.

Final 541-file test-inclusive source snapshot: `86d7028d9f7d0c0f9fead9fb5c0d6d323764c85b0c42ffb8e981c2cef41bdeab` (canonical manifest hashing is defined in that JSON). The artifact index separates the original suite APKs, first workflow/lifecycle APK, all-root observer APK and final automatic-viewer APK; repetitions are not attributed to an APK built later.

Sidequest stayed OFF. Main runtime model/effort cannot be switched or independently inspected through the exposed session controls; the main session does not attest to a verified GPT-6.1 Sol/high route. The independently assigned native worker used the explicit `gpt-6.1-sol` / `high` selection for public HDR API research, the additive viewer implementation and review of the other test changes. It did no device operations. Root reviewed its viewer; the worker independently reviewed root's capture/workflow/display-targeting and diagnostic additions, including the automatic viewer sweep. No xhigh escalation was requested. Applied skills: karpathy-guidelines, orchestration, diagnosing-bugs.

## Connection and screen selection

| Item | Verified value |
| --- | --- |
| Device | Samsung SM-S938B / pa3q / ARM phone |
| Hardware serial | `R5CYA0WKL6Y` (`getprop ro.serialno`) |
| Android | 17 / API37 |
| User-supplied ADB endpoint | `100.70.96.59:36855`; no port guessed |
| Execution/ADB owner host | `akane-linux` / `100.108.50.34` |
| Device lock | `/home/akane/.cache/akastudio-akadroid.lock`, held on akane-linux for all device operations |
| Host ADB | `/home/akane/Android/Sdk/platform-tools/adb`, explicit `-s 100.70.96.59:36855` |
| scrcpy | 3.3.4; installed help read on both hosts |
| Created virtual display | **logical ID10**, 1080×1920 /420dpi, discovered from scrcpy server output |
| Physical display | **logical ID0**; SurfaceFlinger physical ID `4630947191136750483` |

Build-server initially saw only the shared emulator; its phone connection attempt failed. The existing akane-linux pairing connected successfully. No new pairing/security change was needed. A remote-host flock covered the entire session; a local lock was not represented as coordinating that phone. Only one phone test ran at a time. SSH file reads/builds/review proceeded independently.

Creation used the supported options `--new-display=1080x1920/420 --no-audio --no-window --no-clipboard-autosync --record=…`. The creating scrcpy PID121540 stayed alive throughout virtual-display tests. AkaStudio was launched with `am start --display 10`; instrumentation launch options requested ID10 and asserted actual `Activity.display.displayId`. Native accessibility roots were restricted to AkaStudio on ID10, and BACK used `input -d 10 keyevent KEYCODE_BACK`. A separate scrcpy capture used `--display-id=10`; its 1080×1920 file shows AkaStudio ([SDR control capture](evidence/akadroid-session/virtual-empty.png)). Mobile MCP defaults were not used.

ADB serial selects the phone. Activity/input/scrcpy logical display IDs select a screen. `screencap -d` on this phone expects a SurfaceFlinger display ID; its help was inspected, and **logical ID10 was never passed to that API**. The physical ID above is not interchangeable with logical ID0. Library GL/export tests use offscreen pbuffers or codecs and have no Activity display; they are not claimed to run on the virtual screen. Physical HDR viewer tests explicitly requested and asserted ID0.

## Focused results

[Machine-readable per-run results](evidence/akadroid-session/results.json) retain repetitions separately. ADB exit0 was not treated as JUnit success. Original failures and complete raw instrumentation output remain available.

| Check | Result / actual target |
| --- | --- |
| Existing color-engine suite | **28/29 pass**, physical Adreno offscreen; one tile-render assertion fails |
| Existing focused export/color/codec suite | **15/15 pass**, physical platform encode/decode |
| Native lifecycle/BACK suite | **3/4 pass**, actual display10; drawer title observation fails |
| Real workflow addition | **1/1 pass**, actual display10 |
| Paired real export artifacts | **1/1 pass**, offscreen SyntheticRenderer + platform codec |
| HDR viewer | Passes on actual display0; manual and automatic runs recorded separately |
| Inventory/tile diagnostic repeat | Capabilities2/2 pass; original tile assertion still fails |
| Drawer retry | Fails on ID10 after all-root observer correction, and on fallback physical ID0 |

The passing workflow imports eight generated MediaStore photos into the actual app container, runs real on-device curation/models and GPU preparation, changes the look, backgrounds/resumes and recreates the Activity while retaining ViewModel/story/grade, exports a decoded1080×1920 Social JPEG through real MediaStore, discards, and reimports. Inputs enter through the same ViewModel intent used by picker results; this is **not an automated traversal of the system photo picker UI**. The new export row is moved into its QA namespace, copied for evidence, and deleted. Only the generated photos are removed. Existing native tests establish real BACK Keep/Discard, Empty finish and requested orientation recreation on ID10. Mid-export process death, forced driver context loss, thermal endurance, release-APK runtime and exhaustive natural-image quality are not established.

All changed test APK assemblies passed through the repository wrapper with max-workers4. Logs: `gradle.jBAVtqNE.log`, `gradle.PW27zlF3.log`, `gradle.aIR0AaoV.log`, `gradle.zxGVqiT0.log`, `gradle.z6jK6RRI.log` under `.claude/kit/logs/`. Resource policy allowed two Gradle commands/four workers; builds here were serialized. Prior production/JVM/screenshot evidence remains valid for unchanged production inputs; these were not rerun or relabeled as fresh phone evidence.

## Physical GPU and precision

Renderer: **Adreno (TM)830**, Qualcomm. `OpenGL ES3.2 V@0800.64.8`, driver date06/22/26; GLSL ES3.20. EGL1.5 Android META-EGL. [Raw inventory and measurements](evidence/akadroid-session/inventory-tile-measurements.txt) retain the driver strings and EGL extensions, including float pixel formats and scRGB/P3/BT2020 PQ/HLG color spaces. Advertised extensions do not establish their visible output quality.

RGBA16F color-buffer support agrees with an independent FBO completeness and >1.0 storage probe: stored red2.5. EXT_color_buffer_half_float/float are present; working storage is RGBA16F. Max3D texture dimension2048 exceeds LUT lattice33. Real-F16 ramp/readback, stale-error handling, texture/program/LUT tests, renderer cache/trim/window-surface lifecycle tests and all four LUT sampler variants pass. CPU/GPU grading maximum across reported cases is **0.521/255**, below the unchanged2/255 contract ([measurements](evidence/akadroid-session/color-measurements.txt)). Readback type is negotiated for the bound framebuffer; an unbound/default-FBO read-type query must not be confused with the successful F16 readback probe.

**Open precision failure:** `OffscreenReadbackDeviceTest.rendererOutputIsUnchangedByStripsTilesAndTheFloatFallback` repeatedly reports max linear channel difference **0.002319336**, exceeding the existing **0.002** tolerance. The original29-test run, isolated rerun and diagnostic rerun agree. Single/full strip and FLOAT/default reads match bitwise, as do multi-tile strip/FLOAT variants and raw known-pixel tiled assembly. The failing comparison rerasterizes the scene into12 tiles. Diagnostic:290/331776 channels differ; worst `(158,47,B)`, single0.14648438 versus multi0.14416504, local tile offset(58,47). This is interior to a tile. [Original failure](evidence/akadroid-session/color-instrument.txt), [isolated repeat](evidence/akadroid-session/tile-repeat.txt), [diagnostic](evidence/akadroid-session/inventory-tile-diagnostic.txt).

Interpolation/sampling precision during rerasterization is a plausible explanation; the exact source-versus-driver operation remains unproven. The passing raw assembly/readback comparisons narrow the issue away from simple strip-copy placement. No production workaround or relaxed emulator/device threshold was introduced. The physical precision gate remains red.

**Open observer failure:** `systemBackClosesTheModeDrawerBeforeAskingToDiscard` times out finding the visible accessibility title `Story mode`. `modeDrawerOpen` becomes true, and the [main-display SDR capture](evidence/akadroid-session/drawer-main.png) actually shows the drawer/title. Searching all same-package roots on the requested display does not resolve it; ID0 also fails. [Original ID10 result](evidence/akadroid-session/lifecycle-virtual.txt), [all-root ID10 retry](evidence/akadroid-session/drawer-multiroot-virtual.txt), [ID0 fallback](evidence/akadroid-session/drawer-main.txt). This remains an accessibility-observation failure, not proof that the UI failed to draw; drawer BACK priority is not marked passed by this case. No assertion was removed, ignored or weakened.

## Ultra HDR and comparative files

Platform Ultra HDR gain metadata, MPF offsets, BitmapFactory/ImageDecoder gain presence, SDR fallback equivalence, JPEG ICC/chroma, PNG16, WebP alpha and ringing checks pass in the focused15-test selection. PNG16 decodes as RGBA_F16 with3067 distinct ramp levels. Saturated red4 reconstructs as3.8006587 (4.98% low, existing10% bound); white4 as3.9783244 (0.54% low, existing5% bound). Existing numerical bounds remain separate from approval of the visible rendition.

Same decoded AZURE source: contrast.5 → ratio1.1451452/.1955305stop → Social4:4:4/no gain; contrast1 → ratio1.2827575/.3592485stop → Ultra HDR4:2:0/gain270×480, encoded ratioMax1.3054734. Pre-sharpen eligibility and post-sharpen encoded gain are different quantities. [Physical codec measurements](evidence/akadroid-session/export-measurements.txt) preserve them.

Download [actual comparison files, settings and hashes](evidence/akadroid-session/akadroid-comparisons.zip), or inspect [their README](evidence/akadroid-session/physical_compare_01/README.md). Three matched sets: mixed colored highlights/red/white/cyan/gray, HDR gradient, and the retained original JPEG-decoded AZURE contrast1 fixture. Each set includes Social JPEG, actual Ultra HDR JPEG, and a lossless PNG of the decoded primary SDR base with gain removed and exact ARGB roundtrip verified. The shared mixed fixture includes white4; its global peak changes the base mapping, so it is not interchangeable with the standalone M1 mixed-pixel numerical probe. Files use deterministic SyntheticRenderer/CPU grading and the real export/codec implementation; hardware GPU grading is evidenced by the separate parity suite. The real app workflow's [Social output](evidence/akadroid-session/workflow-social.jpg) is supplied separately.

The phone also receives requested comparison deliverables under `/sdcard/Pictures/AkaStudioQA/akadroid_20261026_0236/`. Those outputs are retained for the user; temporary private viewer copies and generated import/test resources are cleaned separately.

## Physical HDR conditions and limits

Panel reports HDR types[2,3,4] (HDR10/HLG/HDR10+), wide gamut, highest HDR/SDR ratio5.0. Static reported luminance450/max-average450/min0.0005 is a platform capability declaration, **not measured emitted nits**. The test-only viewer uses the existing Activity on display0 with a hardware ImageView, original gain-bearing Bitmap, `Window.colorMode=HDR`(2) and automatic desired headroom0. No broad media permissions, extra exported Activity or production viewer changes were needed. It records actual attached display, window focus/acceleration, bitmap gain/config/space and headroom after drawing. `window.isWideColorGamut=false` in HDR mode is not itself an HDR failure. Public API meaning: [Display](https://developer.android.com/reference/android/view/Display), [Window](https://developer.android.com/reference/android/view/Window), [Android Ultra HDR display guidance](https://developer.android.com/media/grow/ultra-hdr/display).

Important environment observation: with scrcpy virtual display10 still active, **all nine files report available/current headroom1.0**, despite maximum5.0 and HDR window configuration. After closing only the owned virtual-display scrcpy process and rerunning the same40-second automatic sweep without screen mirroring, **all nine report available headroom3.0182803–3.805085**; settled samples are3.805085, including every Ultra HDR image. [With virtual display](evidence/akadroid-session/hdr-viewer-auto-measurements.txt), [without virtual display](evidence/akadroid-session/hdr-viewer-no-virtual-measurements.txt), [distinct nine-file summary](evidence/akadroid-session/hdr-no-virtual-summary.json). This is a controlled association with the active virtual-display setup on this phone, not a general claim about every scrcpy/device combination or proof of a specific Samsung policy.

The available3.805085 ratio exceeds AZURE's1.3054734 full-gain requirement, but is below the gradient4.0040636 and mixed4.056579 full-gain requirements. Those latter files can receive attenuated gain; maximum5.0 is not interchangeable with currently available headroom. Telemetry establishes a capable/configured physical path and its reported headroom; it does not measure per-pixel emitted luminance, calibrate the display, or establish subjective quality. No virtual/scrcpy SDR recording is used as evidence of physical HDR appearance. A manual viewer was also offered again after the virtual display closed. The user explicitly could not inspect it while using the same phone for SSH. No subjective visual approval is recorded.

M1 decisions remain separate: **(1)** preferred SDR highlight appearance, **(2)** export-format/eligibility policy, **(3)** approved numerical RGB/cell error limits. Option A remains the stabilization baseline. No RGB gain implementation or M1 approval is recorded.

## Cleanup and current disposition

Cleanup is limited to this session's resources. AkaStudio remains installed with the verified production APK and its existing data. The owned virtual-display scrcpy process is stopped and ID10 removed; bounded viewer Activities closed through ActivityScenario. All three test packages had firstInstallTime in this session and were successfully removed. Only the exact private QA directories created here were removed; all13 requested phone deliverables match their local hashes. The phone's ADB endpoint was disconnected and the owner-host lock released. Shared ADB server, existing emulator, other processes/apps and prior user edits/worktrees are preserved. [Cleanup verification](evidence/akadroid-session/cleanup-verification.json), [package removal](evidence/akadroid-session/cleanup-test-packages.txt), [final physical-display summary](evidence/akadroid-session/display-cleanup-summary.txt) and [installed production APK hash](evidence/akadroid-session/installed-app-sha256.txt) preserve the evidence. Raw failures and session test binaries/logs remain retained on akane-linux under `/home/akane/.cache/akastudio-codex/akadroid-20261026/`; the complete virtual-display recording is there for diagnosis, never HDR appearance evidence.

Physical GPU/codec evidence is now available, but the tile precision assertion, drawer accessibility observer and M1 product choice remain open. No release disposition is silently changed to green.
