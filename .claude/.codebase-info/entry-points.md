# Entry Points
*Last Updated: 2026-10-21*

Paths relative to `<module>/src/main/kotlin/com/akane/akastudio/`.

## App launch
`app/src/main/AndroidManifest.xml` → `AkaStudioApp.kt` (`onCreate` builds `StudioContainer(this)`, starts a thread running `purgeStaleIngestionDirs`; `onTrimMemory` → `StudioContainer.onTrimMemory`, which trims the renderer from `TRIM_MEMORY_UI_HIDDEN` up) → `MainActivity.kt` (edge-to-edge, `DarkroomTheme { StudioRoute(container) }`).

## UI
`feature/studio/ui/StudioRoute.kt` — `StudioRoute(container: StudioDependencies)`: builds `StudioViewModel` via `studioViewModelFactory`, collects `state`, handles `StudioEffect.ShareExport` (chooser) and `ShowMessage` (snackbar), renders `StudioScreen`. Intents flow `StudioScreen` → `viewModel.onIntent(StudioIntent)`. `StudioScreen` remembers `ImportPickers` (`ui/dropzone/ImportPickers.kt`), so the photo/zip launchers stay composed in every phase. They send `PickerOpened`/`PickerClosed` around each launch, and a zip result is sent synchronously.

`feature/studio/mvi/StudioViewModel.kt` — `onIntent`: pure intents go through `StudioReducer.reduce`; async intents launch work: `runIngestion` → `startCuration`/`curate` → `prepareAndShow` → (`reprepare`/`forceMode`/`swapCandidate`) → `export`/`runExport`; `runScopePipeline` feeds the waveform. Auto-curation is held while any picker is open (counted), and a zip's display name is resolved on io through `StudioDependencies.documentDisplayName`. `onCleared` unregisters photos.

## Wiring
`feature/studio/di/StudioContainer.kt` implements `StudioDependencies` with lazily built parts:
- `newIngestionSession()` → `createIngestionSession(app)`; `analysisDecoder` → `createAnalysisDecoder(app)`
- `curation` → `DefaultCurationEngine(pixels, models = LazyAnalysisModels { CuratorModelsFactory.create(app) })`
- `composer` → `DefaultCollageComposer()`
- `renderer` → `createStoryRenderer(app)`; `exporter` → `createStoryExporter(app, renderer)`
- `preparer` → `DefaultPhotoPreparer(AndroidPreparerBackend(FullResolutionDecoder, renderer, analysisDecoder))`
- `harmonizer` → `Harmonizer.forCollage`; `errors` → `StudioContainerErrorMapper`
- `documentDisplayName(uri)` → content-provider display-name query; `onTrimMemory(level)` → `renderer.trimMemory()` (only if the renderer was built)

## Factories
| Factory | File |
|---|---|
| `createIngestionSession`, `createAnalysisDecoder`, `purgeStaleIngestionDirs` (`IngestionFactory.kt`) | `core/ingestion/IngestionFactory.kt` |
| `CuratorModelsFactory.create(context)` | `core/curator/ml/CuratorModels.kt` |
| `createStoryRenderer(context)` | `core/color/render/StoryRendererFactory.kt` |
| `createStoryExporter(context, renderer)` | `core/export/store/StoryExporterFactory.kt` |

## Debug-only
`core/color/render/grade/GradingParityProbe.run(assets)`: no production caller; see patterns.md.
