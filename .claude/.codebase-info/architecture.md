# Architecture
*Last Updated: 2026-10-21*

Clean Architecture + MVI. Android calls sit in thin adapters; the logic is pure Kotlin, covered by JVM tests. Wiring is constructor injection plus one process-wide `StudioContainer` (no Hilt/Koin/KSP). Decision rationale lives in `PROGRESS.md`.

## Module graph (Gradle `api(project())` edges)
```
:app ──► :feature:studio ──► :core:collage-composer ──► :core:color-engine
   │          │                       ├──► :core:vision-curator ─► :core:ingestion, :core:color-engine
   │          │                       └──► :core:ingestion
   │          ├──► :core:export-pipeline ─► :core:color-engine, :core:ingestion
   │          └──► :core:vision-curator, :core:color-engine, :core:ingestion
   └──► :core:ingestion   (for purgeStaleIngestionDirs)
```
`:core:color-engine` and `:core:ingestion` depend on no other project module. All modules are `com.android.library` except `:app`. Source: each `build.gradle.kts`, `settings.gradle.kts`.

## Flow
1. **Ingest** (`:core:ingestion`): `IngestionSession.ingest(requests)` takes PhotoPicker URIs or a zip (`StreamingZipExtractor`), reads EXIF/gain-map/colour-space into `PhotoCandidate`s, emits `IngestionEvent`s with telemetry.
2. **Curate** (`:core:vision-curator`): `DefaultCurationEngine.curate` decodes sampled analysis bitmaps, runs bounded-parallel `PhotoAnalyzer` (technical gate: sharpness/exposure/faces; NIMA + composition aesthetics; embedding, pHash/dHash, palette, saliency), clusters bursts, then `DecisionEngine` picks a mode. Formulas: Q = 0.45T + 0.55A; Ψ = 0.40·H_color + 0.35·H_rhythm + 0.25·H_context; collage sizes tried m = 4,3,2 against τ·Q₁ else HERO (see PROGRESS.md). Emits `CurationProgress` ending in a `CurationResult` (chosen option, per-mode alternatives, `CuratorNote`, `suggestedLook`). `decide(result, forcedMode)` re-decides without re-analysing.
3. **Prepare** (`:feature:studio` di): `DefaultPhotoPreparer` decodes full-resolution (`FullResolutionDecoder`) up to the renderer's GL texture limit, registers bitmaps with the `StoryRenderer`, yields `ComposerPhoto`s. Harmonization plan via `Harmonizer.forCollage` (OKLab).
4. **Compose** (`:core:collage-composer`): `DefaultCollageComposer.compose(CompositionRequest)` → `RenderScene` (layers, backgrounds, overlays) using templates, saliency-driven `SmartCropSolver`, `HorizonDetector`, cell overrides.
5. **GL grade/render** (`:core:color-engine`): `GlStoryRenderer` (own GL thread, GLES 3, RGBA16F FBOs) composites layers and grades through `ShaderLayerGrader` (GLSL in `assets/shaders/`), with a CPU reference pipeline (`CpuGradingPipeline`) for parity. Preview to a window surface; scope readback for waveform.
6. **Export** (`:core:export-pipeline`): `StoryExporter.export(ExportRequest)` → `Flow<ExportProgress>` through RENDER, RESAMPLE (Lanczos-3), SHARPEN, DITHER, ENCODE, WRITE (MediaStore `Pictures/AkaStudio`). Own PNG8/16, 4:4:4 JPEG, WebP lossless writers; Ultra HDR gain map on API 34+.
7. **UI/MVI** (`:feature:studio`): `StudioViewModel` holds `StudioState` (phases Empty, Ingesting, Curating, Preparing, Ready, Exporting), applies `StudioReducer` to `StudioIntent`s, runs the pipeline steps above, emits `StudioEffect` (share, snackbar).

## Cross-cutting
- Dispatchers injected via `StudioDispatchers`; heavy objects (renderer, models) are process-wide and never closed.
- Errors: module exceptions (`IngestionException`, `NoUsablePhotosException`, …) mapped by `StudioContainerErrorMapper` to `StudioError`; UI owns message text (en + tr resources).
