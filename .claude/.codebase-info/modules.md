# Modules
*Last Updated: 2026-10-21*

Package prefix `com.akane.akastudio`. `api/` packages are the frozen cross-module seams; other packages are internal-by-convention. Paths relative to `<module>/src/main/kotlin/com/akane/akastudio/`.

## :app
Host application. `AkaStudioApp.kt` (owns the one `StudioContainer`, purges stale ingestion dirs), `MainActivity.kt`. Depends on `:feature:studio`, `:core:ingestion`. Keeps `.tflite`/`.task` uncompressed.

## :core:ingestion
Photos/zip → `PhotoCandidate`s with metadata and decoders.
- `core/ingestion/api/`: `IngestionSession` (+`IngestionRequest`), `ArchiveExtractor`/`ZipLimits`, `ImageReading.kt` (`MetadataParser`, `AnalysisDecoder`, `SourceOpener`), `IngestionException`.
- `model/`: `PhotoCandidate`, `PhotoSource`, `CaptureMetadata`, `ColorSpaceTag`, `IngestionEvent`, `IngestionError`, `SkipReason`.
- `zip/StreamingZipExtractor` (zip-bomb limits), `metadata/ExifAndGainMapParser`, `decode/{SampledAnalysisDecoder,FullResolutionDecoder}`, `picker/MediaPickerIngestor`, `pipeline/DefaultIngestionSession` (per-archive staging: an archive's files are published only after it ends cleanly), `pipeline/SkipLogger` (logs why a picked item was skipped).
- Factory: `IngestionFactory.kt`. Deps: exifinterface, activity, coroutines; no project modules.

## :core:vision-curator
Analysis and the Q/Ψ decision.
- `core/curator/api/CuratorApi.kt`: `CurationEngine` (`curate`, `decide`), `CurationProgress`, `CurationResult`, `CurationOption`, `PhotoAnalysis`, `CuratorNote`, `StoryMode`, `ShotScale`, `RejectReason`, `SaliencyMap`.
- `metrics/` (sharpness, exposure, composition, technical gate), `saliency/` (spectral residual, FFT), `hash/PerceptualHash`, `ml/` (model adapters + fallbacks, `CuratorModels`, `ModelManifest`, NIMA), `decision/` (`DecisionEngine`, `Clustering`, `Synergy`, `PaletteHarmony`, `TransportSolver`, `DecisionConfig`), `engine/DefaultCurationEngine`, `PhotoAnalyzer`, `AnalysisSeams`.
- Entry: `ml/CuratorModels.kt` → `CuratorModelsFactory.create(context)`. Deps: `:core:ingestion`, `:core:color-engine` (api), MediaPipe 1.1.1, LiteRT 2.3.0.

## :core:color-engine
Colour science, grading and the GL renderer.
- `core/color/api/`: `StoryRenderer`, `RenderScene`/`SceneLayer`/`TextOverlay`/`SceneBackground`, `ColorTypes.kt` (`LabStats`, `MasterLook`, grade params, …).
- `math/ColorMath` (OKLab), `analysis/` (palette k-means, CCT, skin mask), `harmonize/Harmonizer`, `look/` (8 master looks, LUT baking, `LookSelector`, Hald CLUT), `grade/` (CPU reference pipeline), `gl/` (EGL, FBOs, shaders, `GlThread`), `render/` (`GlStoryRenderer`, `SceneCompositor`, `GraderSelection`, `StoryRendererFactory`), `render/grade/` (`ShaderLayerGrader`, `GradePlan`, parity probe/fixtures). GLSL in `src/main/assets/shaders/`.
- Entry: `render/StoryRendererFactory.kt` → `createStoryRenderer(context)`. Deps: coroutines only.

## :core:collage-composer
Layout, smart crop, horizon level → `RenderScene`.
- `core/composer/api/ComposerApi.kt`: `CollageComposer` (`layout`, `compose`, `hitTest`), `StoryTemplate`, `CanvasSize`, `LayoutStyle`, `BackgroundStyle`, `CompositionRequest`, `ComposerPhoto`, `CellOverride`, `CellPlacement`.
- `compose/DefaultCollageComposer`, `crop/` (`SmartCropSolver`, `SaliencyIntegral`), `horizon/` (edge map + Hough), `layout/TemplateGeometry`. Deps: `:core:color-engine`, `:core:vision-curator`, `:core:ingestion`.

## :core:export-pipeline
Render, process, encode, save.
- `core/export/api/ExportApi.kt`: `StoryExporter`, `ExportRequest`, `ExportFormat`, `ExportResolution`, `ExportPlan`, `ExportProgress`/`ExportStage`, `ExportResult`.
- `pipeline/ExportProcessor`, `process/` (Lanczos-3, sharpen, blue-noise dither, half-float rows, gain-map math), `encode/` (PNG, JPEG, WebP container, ICC, EXIF), `store/` (`MediaStoreSink`, `PlatformEncoders`, `StoryExporterFactory`). Deps: `:core:color-engine`, `:core:ingestion`, exifinterface.

## :feature:studio
Compose UI, MVI state and DI.
- `domain/StudioDependencies.kt` (`StudioDependencies`, `PhotoPreparer`, `HarmonizationPlanner`, `StudioDispatchers`), `StudioError`, `ExportCanvasPolicy`, `PreparedPhotoStore`.
- `mvi/`: `StudioState`/`StudioPhase`, `StudioIntent`, `StudioEffect`, `StudioReducer`, `StudioViewModel`, `SceneBuilder`.
- `di/`: `StudioContainer`, `DefaultPhotoPreparer`, `AndroidPreparerBackend`, `LazyAnalysisModels`, `StudioContainerErrorMapper`, `CanvasHitTest`.
- `ui/`: `StudioRoute`, `StudioScreen`, `dropzone/`, `canvas/` (+`scope/` waveform; `ScopeLabelLayout` clamps label placement and lays out axis labels pure-functionally, dropping any that would overlap; `scopePanelWidth` floors the overlay at `ScopeMinWidth` 160 dp), `controls/` (tabs, export sheet, `CollapsibleControlPanel` with a ~240 dp peek, `CompareHint` one-time tooltip persisted via `CompareHintPrefs` SharedPreferences), `note/` (curator note text), `theme/` (Darkroom tokens). Strings in `res/values` and `values-tr`.
- Deps: all five core modules (api), Compose BOM, lifecycle, activity-compose.
