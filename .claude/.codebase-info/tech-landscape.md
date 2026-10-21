# Tech Landscape
*Last Updated: 2026-10-21*

| Item | Value | Source of truth |
|---|---|---|
| Gradle | 9.8.0 | `gradle/wrapper/gradle-wrapper.properties` |
| AGP | 9.4.1 (built-in Kotlin) | `gradle/libs.versions.toml` |
| Kotlin | 2.4.20 + Compose compiler plugin | `gradle/libs.versions.toml` |
| Compose BOM | 2026.09.00 (Material 3) | `gradle/libs.versions.toml` |
| SDK | compileSdk/targetSdk 37, minSdk 31 | `app/build.gradle.kts` |
| JVM target | 17 | module `build.gradle.kts` |
| Async | Coroutines 1.11.0 | catalog |
| Immutable collections | kotlinx-collections-immutable 0.5.2 (`:app`, `:feature:studio`) | catalog |
| EXIF | androidx.exifinterface 1.4.2 (`:core:ingestion`, `:core:export-pipeline`) | catalog |
| ML | MediaPipe tasks-vision 1.1.1, LiteRT 2.3.0 (`:core:vision-curator` only) | catalog |
| Tests | JUnit 4, coroutines-test, Compose Preview Screenshot Testing 0.0.1-alpha16 | catalog |

Repositories locked to `google()` + `mavenCentral()` in `settings.gradle.kts`. No DI framework, no KSP, no Robolectric, no network, no database.

## ML models (`core/vision-curator/src/main/assets/models/`, provenance + sha256 in `MODELS.md`, mirrored in `ml/ModelManifest.kt`)
- `face_landmarker.task` (MediaPipe; blink blendshapes for eye openness)
- `mobilenet_v3_small.tflite` (MediaPipe embedder, 1024-d)
- `efficientnet_lite0.tflite` (MediaPipe ImageNet classifier → scene types via `ImageNetSceneMapping`)
- `nima_aesthetic_fp16.tflite` (LiteRT `CompiledModel`, GPU→CPU fallback)
`.tflite`/`.task` are stored uncompressed (`androidResources.noCompress` in `core/vision-curator/build.gradle.kts` and `app/build.gradle.kts`) so runtimes can mmap. Any model failure degrades to heuristic fallbacks (`CuratorModels.degraded`). Binary contents are not mapped.

## Graphics
OpenGL ES 3 via `EglCore` on a dedicated GL thread; RGBA16F FBOs, 3D LUT textures (tetrahedral sampling), GLSL assets loaded through `GradeShaderCatalog`/`ShaderCatalog`. Falls back to a reduced 8-bit shader or identity grader by `GraderSelection.kt`. AGSL was rejected (see PROGRESS.md). Ultra HDR `Gainmap` is a platform API (API 34+).
