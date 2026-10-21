# PROGRESS.md — AkaStudio

_Work items, stories and blockers live on the Sidequest board. This file keeps what outlives tickets._

## Baseline
- 2026-10-21 @ scaffold commit: build ok, 1 unit test, 0 failing.

## Decision log (never delete; one line each)
- 2026-10-21: scaffolded with android-kit snapshot: Gradle 9.8.0, AGP 9.4.1, Kotlin 2.4.20 (built-in), Compose BOM 2026.09.00, coroutines 1.11.0, screenshot plugin 0.0.1-alpha16, JDK target 17. (bootstrap)
- 2026-10-21: compileSdk 37 instead of the spec's 36: it's the snapshot's current level, and compiling against 37 covers the requested Android 17 forward-compatibility. Revisit if a dependency requires 36. (bootstrap)
- 2026-10-21: dynamic color off, placeholder palette; brand (darkroom #0B0C0E) comes from /ui-overhaul. (bootstrap)
- 2026-10-21: no DI framework, no KSP, no Robolectric. Wiring uses constructor injection plus a process-wide StudioContainer (feature/studio di/). Android calls sit in thin adapters, and the logic is pure Kotlin with JVM tests. (US-1..US-5)
- 2026-10-21: an ingestion zip failure deletes only the in-flight partial file. Completed files belong to the session work dir, which `close()` removes. (SQ-2, US-1)
- 2026-10-21: curator formulas: Q = 0.45T + 0.55A; Ψ = 0.40·H_color + 0.35·H_rhythm + 0.25·H_context; Score_Collage = (0.5·meanQ + 0.5Ψ)(1 − 0.03(m−2)). Thresholds: burst cosine 0.82, τ = 0.92, Q₂ ≥ 0.55, collage member Q ≥ 0.62. (US-2)
- 2026-10-21: collage size uses option (B): try m = 4, 3, 2 and take the first best subset with score ≥ τ·Q₁, otherwise HERO. Argmax-over-m can never pick 3 or 4 under these constants. (SQ-14, US-2)
- 2026-10-21: ML stack pinned to MediaPipe tasks-vision 1.1.1 and LiteRT 2.3.0. Assets: face_landmarker.task, mobilenet_v3_small embedder (1024-d), efficientnet_lite0 classifier, and NIMA aesthetic fp16 (sha + HF revision in core/vision-curator assets MODELS.md). (SQ-13/SQ-35)
- 2026-10-21: NIMA runs confined to one "nima-litert" thread (it compiles and runs on the same thread), with GPU→CPU fallback at runtime. Tests compare thread-name prefixes, because coroutine debug mode appends " @coroutine#N". (SQ-31/SQ-35)
- 2026-10-21: a per-image MediaPipe embedding failure falls back to a vector in a different feature space. The engine treats it as "embedding unavailable" (a zero vector, cosine 0), so clustering for that photo rests on pHash/dHash plus time. A face-detection failure counts as no faces. (SQ-15)
- 2026-10-21: the app APK keeps `.tflite`/`.task` uncompressed (`androidResources.noCompress`), because the library-level noCompress setting doesn't reach the app APK and MediaPipe/LiteRT need to mmap the models. (app/build.gradle.kts)
- 2026-10-21: grading runs on GLES 3 with RGBA16F FBOs instead of AGSL: AGSL can't do multi-pass offscreen 16-bit rendering or a CPU-parity readback. (SQ-10, US-3)
- 2026-10-21: look LUTs are sampled tetrahedrally with texelFetch. Trilinear reached max 2.83/255 against the CPU reference (the contract allows 2/255); tetrahedral reached 0.378/255 on Mesa. (SQ-11, US-3)
- 2026-10-21: SceneLayer.rotationDegrees is positive clockwise (Android Canvas convention). HorizonDetector returns +tilt for a horizon rising to the right, and the renderer must use the same sign. (SQ-16, US-4)
- 2026-10-21: export downscaling is CPU Lanczos-3, using our own PNG 8/16, 4:4:4 JPEG, sRGB ICC and lossless WebP writers, because platform encoders can't guarantee 4:4:4, the ICC tag or 16-bit output. A zero-headroom Ultra HDR request exports a plain Social JPEG, and the UI shows the actual format. (SQ-19/SQ-33, US-4)
- 2026-10-21: Turkish UI keeps product and format names in English (Ultra HDR, Social Story Master, HDR Story Boost, PNG, WebP) but translates the Hero mode as "Tek kare", because format names are what users see in other apps and in the gallery. (SQ-40)
- 2026-10-21: pending telemetry steps use the Tertiary token (#8E8A82, ≥4.5:1) instead of a dimmed grey, because they're content rather than disabled controls, so the WCAG AA contrast minimum applies. (SQ-27/SQ-39)
- 2026-10-21: the renderer, the curator models and the StudioContainer are process-wide and never closed. GlStoryRenderer keeps registered Bitmaps for context-loss re-upload, so the preparer recycles one only after unregisterImage. (SQ-26)

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
