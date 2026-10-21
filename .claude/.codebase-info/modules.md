# Modules
*Last Updated: 2026-10-21*

Package prefix `com.akane.akastudio`. `api/` packages are the frozen cross-module seams; other packages are internal-by-convention. Paths relative to `<module>/src/main/kotlin/com/akane/akastudio/`.

## :app
Host application. `AkaStudioApp.kt` (owns the one `StudioContainer`, purges stale ingestion dirs, forwards `onTrimMemory`), `MainActivity.kt`. Depends on `:feature:studio`, `:core:ingestion`. Keeps `.tflite`/`.task` uncompressed. `build.gradle.kts` registers `check<Variant>NoInternetPermission` (see tech-landscape.md).

## :core:ingestion
Photos/zip → `PhotoCandidate`s with metadata and decoders.
- `core/ingestion/api/`: `IngestionSession` (+`IngestionRequest`), `ArchiveExtractor`/`ZipLimits`, `ImageReading.kt` (`MetadataParser`, `AnalysisDecoder`, `SourceOpener`), `IngestionException`.
- `model/`: `PhotoCandidate`, `PhotoSource`, `CaptureMetadata`, `ColorSpaceTag`, `IngestionEvent`, `IngestionError`, `SkipReason`.
- `zip/StreamingZipExtractor` (zip-bomb limits: every inflated byte, including those of entries that are skipped or drained, is charged to the per-entry and cumulative caps through one `accountInflated` path; only the charset replay is exempt), `metadata/ExifAndGainMapParser`, `decode/{SampledAnalysisDecoder,FullResolutionDecoder}`, `picker/MediaPickerIngestor`, `pipeline/DefaultIngestionSession` (per-archive staging: an archive's files are published only after it ends cleanly; duplicate identities are session-scoped, and skipped extracted files are deleted at once). Zip space checks use the volume's usable space, read once per archive; ENOSPC maps to `InsufficientStorage`, `pipeline/SkipLogger` (logs why a picked item was skipped).
- Factory: `IngestionFactory.kt`. Deps: exifinterface, activity, coroutines; no project modules.

## :core:vision-curator
Analysis and the Q/Ψ decision.
- `core/curator/api/CuratorApi.kt`: `CurationEngine` (`curate`, `decide`), `CurationProgress`, `CurationResult` (carries its own `captureTimes`, so a mode switch re-decides with that run's timestamps and the process-wide engine holds no per-batch state), `CurationOption`, `PhotoAnalysis`, `CuratorNote`, `StoryMode`, `ShotScale`, `RejectReason`, `SaliencyMap`.
- `metrics/` (sharpness, exposure, composition, technical gate; `BAD_EXPOSURE` only when `Exposure.Result.unusable`, while the exposure score still ranks), `saliency/` (spectral residual, FFT), `hash/PerceptualHash`, `ml/` (model adapters + fallbacks, `CuratorModels`, `ModelManifest`, NIMA), `decision/` (`DecisionEngine`, `Clustering`, `Synergy`, `PaletteHarmony`, `TransportSolver`, `DecisionConfig`), `engine/DefaultCurationEngine` (if any photo lacks a NIMA score, the whole run uses the no-NIMA aesthetic formula), `PhotoAnalyzer`, `AnalysisSeams`.
- Entry: `ml/CuratorModels.kt` → `CuratorModelsFactory.create(context)`. Deps: `:core:ingestion`, `:core:color-engine` (api), MediaPipe 1.1.1, LiteRT 2.3.0.

## :core:color-engine
Colour science, grading and the GL renderer.
- `core/color/api/`: `StoryRenderer` (incl. fire-and-forget `trimMemory()` / `releaseSurface(surface)`, default no-ops), `RenderScene`/`SceneLayer`/`TextOverlay` (`haloArgb`, `maxWidthPx`)/`SceneBackground`, `ColorTypes.kt` (`LabStats`, `MasterLook`, grade params, …).
- `math/ColorMath` (OKLab), `analysis/` (palette k-means, CCT as the nearest Planckian-locus point over 40–600 mired, skin mask), `harmonize/Harmonizer` (caps only the mean shift), `look/` (8 master looks, LUT baking, `LookSelector`, Hald CLUT), `grade/` (CPU reference pipeline), `gl/` (EGL, FBOs, shaders, `GlThread`), `render/` (`GlStoryRenderer`, `SceneCompositor`, `GradeSlotSpace` (separate preview/scope/export grade slots), `ReadbackDecoder` + `WorkingReadback` (offscreen readback in 256-row strips), `ImageRegistry` (lazy re-upload after context loss), `GraderSelection`, `StoryRendererFactory`), `render/grade/` (`ShaderLayerGrader`, `GradeSlotOutputs`, `GradePlan`, parity probe/fixtures). The FBO pool and grade slots are trimmed after export and on `trimMemory()`. GLSL in `src/main/assets/shaders/`.
- Entry: `render/StoryRendererFactory.kt` → `createStoryRenderer(context)`. Deps: coroutines only.

## :core:collage-composer
Layout, smart crop, horizon level → `RenderScene`.
- `core/composer/api/ComposerApi.kt`: `CollageComposer` (`layout`, `compose`, `hitTest`), `StoryTemplate`, `CanvasSize`, `LayoutStyle`, `BackgroundStyle`, `CompositionRequest`, `ComposerPhoto`, `CellOverride`, `CellPlacement`.
- `compose/DefaultCollageComposer` (EXIF caption on slot 0's cell with a halo), `compose/SolvedCropCache` (64-entry LRU of solved smart crops), `crop/` (`SmartCropSolver`, `SaliencyIntegral`), `horizon/` (edge map + Hough), `layout/TemplateGeometry`. Deps: `:core:color-engine`, `:core:vision-curator`, `:core:ingestion`.

## :core:export-pipeline
Render, process, encode, save.
- `core/export/api/ExportApi.kt`: `StoryExporter`, `ExportRequest`, `ExportFormat`, `ExportResolution`, `ExportPlan`, `ExportProgress`/`ExportStage`, `ExportResult`.
- `pipeline/ExportProcessor`, `process/` (Lanczos-3, sharpen, blue-noise dither, half-float rows, gain-map math; `GainMapMath.ratioMax` decides the Ultra HDR → Social fallback before the base is built; the SDR base is bounded per channel and the gain is derived from the sharpened base actually encoded; `Dither.toSrgb16Row`/`toStraightSrgb8Row` unpremultiply the premultiplied-linear `HalfFloatImage` for alpha-preserving PNG16/WebP output while opaque outputs composite over black), `encode/` (PNG, JPEG, WebP container, ICC, EXIF; `JpegSegments` rewrites MPF entry 0's size after the EXIF splice), `store/` (`MediaStoreSink`, `PlatformEncoders`, `StoryExporterFactory`). Deps: `:core:color-engine`, `:core:ingestion`, exifinterface.

## :feature:studio
Compose UI, MVI state and DI.
- `domain/StudioDependencies.kt` (`StudioDependencies` incl. `documentDisplayName`, `PhotoPreparer`, `HarmonizationPlanner`, `StudioDispatchers`), `StudioError`, `ExportCanvasPolicy`, `PreparedPhotoStore` (owner-tagged acquire/retain/release of prepared photos), `PhotoOwner` (coroutine-context element identifying the story that registered a photo).
- `mvi/`: `StudioState`/`StudioPhase`, `StudioIntent`, `StudioEffect`, `StudioReducer` (pure intents plus the named phase transitions), `StudioViewModel` (state, effects, the current generation), `StoryGeneration` (one story's scope, resources and work sequencing), `SceneBuilder`.
- `di/`: `StudioContainer`, `DefaultPhotoPreparer`, `AndroidPreparerBackend`, `LazyAnalysisModels`, `StudioContainerErrorMapper`, `CanvasHitTest`.
- `ui/`: `StudioRoute` (collects effects on `Main.immediate`), `StudioScreen` (holds the picker launchers), `dropzone/` (`ImportPickers`: photo/zip launchers, open-picker gating), `canvas/` (image-space overlays pinned to LTR; `CanvasActions` accessibility actions; swap/edit only while Ready) (+`scope/` waveform; `ScopeLabelLayout` clamps label placement and lays out axis labels pure-functionally, dropping any that would overlap; `scopePanelWidth` floors the overlay at `ScopeMinWidth` 160 dp), `controls/` (tabs, export sheet, `CollapsibleControlPanel` with a ~240 dp peek, `CompareHint` one-time tooltip persisted via `CompareHintPrefs` SharedPreferences), `note/` (curator note text), `theme/` (Darkroom tokens). Strings in `res/values` and `values-tr`.
- Deps: all five core modules (api), Compose BOM, lifecycle, activity-compose.
