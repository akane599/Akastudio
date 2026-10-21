# Architecture
*Last Updated: 2026-10-21*

## Current state
Single `:app` module from the android-kit scaffold:
- `app/src/main/kotlin/com/akane/akastudio/MainActivity.kt`: the only Activity (edge-to-edge, sets `AppTheme { HomeRoute() }`).
- `ui/home/HomeViewModel.kt`: `StateFlow<HomeUiState>` + `@Immutable` state with `ImmutableList`, the template for MVI state.
- `ui/theme/*`: placeholder M3 tokens (Color, Type, Shape, Spacing, Theme). Dynamic color is off; the darkroom brand (#0B0C0E) arrives via `/ui-overhaul`.
- No DI, DB or network.

## Intended design (from the product spec; not yet built)
Clean Architecture + MVI, multi-module:

| Module | Responsibility |
|---|---|
| `:core:ingestion` | PhotoPicker (`PickMultipleVisualMedia`) + streaming `.zip` (ZipInputStream; Zip Slip/bomb guards; skips `__MACOSX/`, `.DS_Store`, `Thumbs.db`, dotfiles); EXIF/ICC/Ultra HDR gain-map parsing → `PhotoCandidate`; 512px analysis decode via `inSampleSize` |
| `:core:vision-curator` | Technical gate (Laplacian variance with a saliency boost, histogram clipping/entropy, MediaPipe EAR blink check) → burst dedup (dHash/pHash + embedding cosine > 0.82) → aesthetic score (LiteRT NIMA + composition) → Hero vs. 2–4 collage synergy decision Ψ(S_m) with a curator note |
| `:core:color-engine` | sRGB ↔ linear ↔ OKLab; clamped Reinhard transfer to a chromatic anchor; OKLCh skin-tone shield; 8 master looks; halation + grain shaders (AGSL / GLES 3.2, RGBA_F16) |
| `:core:collage-composer` | Saliency/face-safe crop, Hough horizon leveling (±5°), 9:16 templates (hero full-bleed/gallery matte; 2/3/4-up layouts) |
| `:core:export-pipeline` | Lanczos-3 downscale, luma USM, blue-noise dither 16→8-bit, sRGB ICC embed, PNG / lossless WebP / Ultra HDR JPEG-R |
| `:feature:studio` | Compose darkroom UI: ingest telemetry, 9:16 live canvas, A/B, waveform, 4-tab controls, export sheet |

Flow: `IngestionPipeline` → `PhotoCandidate[]` (analysis buffers) → curator decision (1–4 picks) → full-res F16 load of picks only → harmonize + grade → compose → export.

Memory rule: only the final 1–4 photos are decoded at full resolution; everything else stays at 512px analysis size.

Delivery is staged (1 ingestion → 2 curator → 3 color → 4 composer + export → 5 studio UI); see `PROGRESS.md` and the Sidequest board.
