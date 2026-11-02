---
paths:
  - "core/vision-curator/**"
  - "core/ingestion/**/decode/**"
---

# Curation and decode guardrails

- Never pass a target colour space to `ImageDecoder` in the full-resolution decode. On API 37 the HEIF codec returns OETF-encoded values tagged linear, which whitened every HEIC; decode natively and convert through `toLinearF16` (`LinearDecodeDeviceTest` guards it on the phone).
- `RealTraceSelectionTest` runs only when `AKASTUDIO_SELECTION_TRACE` points at the local `selection_trace.json`. Never commit that trace, the personal acceptance photos or their file names, and never hardcode set-specific results in production code.
- Selection constants (redundancy ramps, φ_m bars, layout-fit margins) were calibrated on one real set; a change to them needs the real-trace tests and a visual acceptance run, not only the synthetic regressions.
