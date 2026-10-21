# Entry Points
*Last Updated: 2026-10-21*

Paths relative to `<module>/src/main/kotlin/com/akane/akastudio/`.

## App launch
`app/src/main/AndroidManifest.xml` → `AkaStudioApp.kt` (`onCreate` builds `StudioContainer(this)`, starts a thread running `purgeStaleIngestionDirs`; `onTrimMemory` → `StudioContainer.onTrimMemory`, which trims the renderer from `TRIM_MEMORY_UI_HIDDEN` up) → `MainActivity.kt` (edge-to-edge, `DarkroomTheme { StudioRoute(container) }`).

## UI
`feature/studio/ui/StudioRoute.kt` — `StudioRoute(container: StudioDependencies)`: builds `StudioViewModel` via `studioViewModelFactory`, collects `state`, handles `StudioEffect.ShareExport` (chooser) and `ShowMessage` (snackbar), renders `StudioScreen`. Intents flow `StudioScreen` → `viewModel.onIntent(StudioIntent)`. `StudioScreen` remembers `ImportPickers` (`ui/dropzone/ImportPickers.kt`), so the photo/zip launchers stay composed in every phase. They send `PickerOpened`/`PickerClosed` around each launch, and a zip result is sent synchronously.

`feature/studio/mvi/StudioViewModel.kt` — `onIntent`: pure intents go through `StudioReducer.reduce`; everything that needs dependencies is routed to the one current `StoryGeneration` (`mvi/StoryGeneration.kt`), the batch of imports that becomes one story. Invariant: there is a generation exactly when the phase is not Empty. The generation owns its `SupervisorJob` scope, `PreparedPhotoStore`, ingestion session and candidates, and sequences `import` → `requestCuration`/`curate` → `prepareAndShow` → (`forceMode`/`swapCandidate` re-preparation, `restore` after a failed one) → `export`; the phase transitions themselves are `StudioReducer` functions (`ingesting`, `curating`, `decided`, `preparing`, `ready`, `restored`, `exporting`, `exportFinished`, …). `Reset`, an import into a Ready studio, a fatal failure and `onCleared` retire the generation: the view model empties the state synchronously and runs the generation's cleanup (join work, release photos, close the session) in a `retirements` scope that outlives `viewModelScope`; a cleanup failure is logged and shown only while the studio is still the empty one that retirement left. `runScopePipeline` (waveform) stays in the view model. Auto-curation is held while any picker is open (counted), and a zip's display name is resolved on io through `StudioDependencies.documentDisplayName`.

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
