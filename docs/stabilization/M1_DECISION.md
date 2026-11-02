# M1: Ultra HDR base and reconstruction decision

**Current decision — accepted for scoped M1:** choose C maximum-linear-RGB shoulder (knee`.6`) for both Social and Ultra SDR base, keep explicit Ultra whenever preflight has usable gain at the existing`.01-stop` floor, and use scalar1×1 gain consistently for preflight/final export. Generic gain-math defaults and frozen4×4 numeric controls remain unchanged. Actual C4/RGB4/C2 fail the night cell RGB p99 guard; C1 passes all10 natural cases under unchanged limits. [Completed decision/evidence](M1_COMPLETION.md), [real files](evidence/akadroid-m1-completion/m1-comparisons-20261026.zip), [independent acceptance](evidence/akadroid-m1-completion/independent-final-review.md). Final available physical headroom2.2848 did not reach nightC1's2.5428; no subjective/full-gain panel claim is made. Three RGB-prototype files remain a delivery remainder, not selected-M1 qualification. The investigation below is historical and its open-choice wording is superseded.

Decision status: **product choice remains open**. Numerical reconstruction, SDR appearance, and export eligibility are separate contracts. This package does not approve a tone curve or format boundary for release.

Physical-phone follow-up (2026-10-26): [PHYSICAL_QA.md](PHYSICAL_QA.md) adds SM-S938B/Adreno830 platform file results and [real matched output files](evidence/akadroid-session/akadroid-comparisons.zip). Existing numerical bounds were not changed. Physical HDR window/capability and maximum headroom5 are recorded separately from currently available headroom:1 while the scrcpy virtual display was active,3.02–3.81 after that display closed. The latter remains below the ~4.0 full-gain requirement of two fixtures. The user could not perform a direct visual comparison while using the same phone for SSH; no visual approval is recorded. Option A remains the stabilization baseline; RGB gain is not prototyped. SDR appearance, format eligibility and numerical error limits still require separate decisions.

Baseline: `299e568d5e6ed660aba535fb7afd7a96b834943d` (2026-10-26 investigation). `AUDIT_REPORT.md` and `PROGRESS.md` supplied historical claims; findings below come from current source and freshly compiled numerical probes. The subagent cannot independently inspect its runtime model/effort metadata and does not attest to it.

## What is verified

1. The current base's upper channel bound fixes the large colored-highlight mismatch. In the existing red4/white4 fixture, red reconstructs to **3.83828**, versus **1.74480** with the luminance-only counterfactual. The same red patch has the **same SDR red appearance** under both algorithms: both encode `(1,0,0)`. Therefore the fix does not darken every saturated highlight.
2. Some mixed-color highlights do darken in the SDR rendition. For the historical M1 `(1.3,.5,.2)` example, the current half-float base is `(1,.384766,.153809)` with luminance **.498889**. The clipped Social comparator's pre-8-bit luminance is **.584636**; current is about **14.7% lower**. This is a visible choice, not a consequence mandated by the Android format itself.
3. Ordinary in-range SDR retains its base and has no gain. In a standalone `(0.4,.3,.2)` image the base is unchanged (apart from input half rounding); `ratioMax=1`, so an unboosted Ultra HDR request falls back to Social.
4. The current one-channel gain is a **cell-luminance approximation**, not exact RGB recovery. Equal nonzero epsilons bias saturated channels even when nothing clips. The existing tests already permit this: pure red's red channel is about 4.04% low and its nominally zero channels reconstruct as `.0436658`. Whole-pixel luminance is correct before quantization in that flat cell.
5. L6 is reachable from production sharpening and is separable from M1. The sharpener produces negative RGB; gain measurement includes it, while the encoder removes it. A focused correction can consistently define gain's target as the representable, nonnegative sharpened HDR color. The M1 tone-curve/format decision does not need to be bundled with that correction.
6. The JVM and Android AZURE fixtures are not numerically interchangeable. The exact JVM fixture at contrast 1 stays below the threshold. The Android fixture includes Canvas rasterization, JPEG95 encoding, linear-F16 decode and expansion to the export canvas. Its historical .36-stop value is not established by the JVM result. The paired Android comparison now measures both settings on the same decoded input; its dynamic expectations check consistency, not an approved fixed numeric boundary.

## Reproduce the numerical evidence

Run from the repository root:

```sh
python3 docs/stabilization/m1/run_analysis.py
python3 docs/stabilization/m1/run_analysis.py --baseline
```

This invokes the already cached Kotlin 2.4.21 compiler directly on pure Kotlin production source. Default mode uses the working source including L6 correction; `--baseline` extracts committed `299e568` source to a temporary directory. It does not invoke Gradle, install tools, use Android, or reuse potentially stale build classes. Temporary class/source files are removed. [Current source hashes](m1/source-sha256.txt), [baseline source hashes](m1/baseline-source-sha256.txt), the [probe](m1/M1Probe.kt), [current TSV output](m1/results.tsv), and [baseline TSV output](m1/baseline-results.tsv) are retained. The [SDR swatches](m1/sdr-swatches.svg) compare pre-JPEG SDR colors; they cannot show HDR luminance or prove display quality.

`pixel` rows use the production half conversion, current gain map, blue-noise dither and 8-bit sRGB decode. Reconstruction applies the documented Android full-gain formula to the sampled gain cell. These rows are **not decoded JPEG/device measurements**. `counterfactual_luma_only` keeps the current source/settings and removes only the upper channel factor, deriving the unquantized cell gain against that base; it is a controlled counterfactual, not a checkout/build of an earlier commit. `option_RGB_gain` gives ideal unquantized per-channel ratios against a deliberately clipped base; it is not an implemented encoder.

## Source contract

| Seam | Current source and behavior |
|---|---|
| Working image | `core/color-engine/.../api/RenderScene.kt:44`: premultiplied linear sRGB half-float; RGB can exceed 1. |
| Tone mapping | `core/export-pipeline/.../process/GainMapMath.kt:148`: peak is at least 1; `f=min((1+Y/peak²)/(1+Y),1/max(1,R,G,B))`. Base is `RGB*f`. |
| Gain measurement | `GainMapMath.kt:171`: averages HDR and base luminances in 4×4 cells, then uses `(meanHDR+1/64)/(meanBase+1/64)`. Code gamma is 1. Gain is one byte per cell. |
| Nonfinite inputs | `process/HalfRows.kt:40`: NaN→0; infinities saturate to ±65504. At baseline finite negative channels remain negative. |
| Base quantization | `process/Dither.kt:27,53`: each channel clamps to `[0,1]` before sRGB encoding and blue-noise quantization. Thus both upper clipping and lower clipping affect what the gain actually describes. |
| Android encoder | `store/PlatformEncoders.kt:53`: sRGB `ARGB_8888` base, alpha forced opaque, `ALPHA_8` gain bitmap, same metadata for all channels, platform JPEG quality 95. The repository's device test pins observed 4:2:0 base sampling; Social uses its own 4:4:4 encoder. |
| Order | `pipeline/ExportProcessor.kt:239`: decide headroom on render, resample if needed, sharpen HDR, derive gain/base, encode. Gain is not measured before sharpening and then applied to a subsequently sharpened base. |
| Eligibility | `ExportProcessor.kt:271`: unboosted threshold `2^(1/3)=1.259921...`; decision uses the **unsharpened render**. Encoded gain comes from the later sharpened image, so it can differ near the threshold. |
| Fallback | `ExportProcessor.kt:240`: Social encodes the original render using Social processing, not the rejected tone-mapped base. |

The Android Gainmap API accepts either a one-channel or three-channel bitmap (`ARGB_8888`, alpha ignored). At full application, it defines `D_c=(B_c+epsilonSdr_c)*q_c-epsilonHdr_c`. Intermediate display headroom applies fractional log gain. This establishes the API representation, not a tested three-channel `Bitmap.compress` integration in this repository. See the [Android Gainmap API](https://developer.android.com/reference/android/graphics/Gainmap) and [Ultra HDR display requirements](https://developer.android.com/media/grow/ultra-hdr/display).

For a nonnegative flat pixel, ideal base `B_c=f*H_c`, common epsilon `e`, and common ratio `q=(Y+e)/(fY+e)`, the error before storage losses is:

```text
D_c - H_c = e * (1-f) * (Y-H_c) / (fY+e)
```

That error is zero in weighted luminance and for neutral RGB; it is generally nonzero per channel. Spatially varying `f` inside a cell, downsampling/filtering, half rounding, gain-code quantization, base quantization, and two lossy JPEG layers add further error. Current JVM reconstruction uses nearest cell sampling; physical rendering may interpolate/filter the gain texture.

## Same-input comparisons

Linear RGB below is relative to SDR white, with no claim about absolute nits.

| Input/settings | Current encoded-base reconstruction | Luminance-only counterfactual reconstruction | SDR appearance implication |
|---|---|---|---|
| Existing AUD-03 red `(4,0,0)` beside white4, no boost | `(3.83828,.043666,.043666)` | `(1.74480,.011458,.011458)` | Both bases encode red `(1,0,0)`; current corrects HDR without changing this flat SDR swatch. |
| Existing white4 patch | `(4,4,4)` | `(4,4,4)` | Same white base. |
| Existing cyan/white after 2-stop boost; cyan becomes `(0,3.222656,3.222656)` | `(.034053,3.213462,3.213462)` | `(.031491,3.046884,3.046884)` | Both bases encode cyan `(0,1,1)`. |
| Historical M1 mixed pixel, half input `(1.299805,.5,.199951)` | `(1.295241,.503306,.201924)` | `(1,.502886,.198069)` | Current SDR base suppresses G/B to preserve RGB ratios while keeping red within white. Counterfactual loses red highlight recovery. |
| Normal SDR `(0.399902,.300049,.199951)` | `(.401978,.300544,.198069)` | Same | Both use unit gain; differences from input are ordinary 8-bit quantization. |

For the M1 mixed pixel, current gain is **1.290699** or **.368153 stop**; the luminance-only counterfactual measures unit gain because luminance stays below 1. The changed eligibility is therefore tied to the chosen base construction, not direct detection of source HDR provenance. An SDR-origin grade may create sufficient channel headroom, while source metadata alone does not define the intended rendition.

The mixed-pixel current base's 8-bit luminance at sampled `(0,0)` is approximately `.500016`, versus `.586565` for Social. The ideal half-base luminance `.498889` should not be compared as though it were itself a decoded JPEG measurement.

### Exact current JVM photo and AZURE settings

The source fixture is `ExportProcessorTest.kt:529`, including its sky, five saturated patches and gray ramp. Grade defaults, grain seed 0, and AZURE look intensity 1 are preserved. The native image is 270×480. `expanded4` repeats the **graded source pixels** 4×4 to 1080×1920, matching the Android synthetic renderer's sampling geometry while deliberately excluding Canvas/JPEG/decode differences.

| Input | Native `ratioMax` / stops | Expanded4 `ratioMax` / stops | Unboosted decision |
|---|---|---|---|
| Ungraded normal SDR | 1 / 0 | Not needed | Social |
| AZURE contrast 0 | 1 / 0 | 1.016258 / .023267 | Social |
| AZURE contrast .5 | 1.102153 / .140325 | 1.110052 / .150627 | Social |
| AZURE contrast 1 | 1.225370 / .293217 | 1.227826 / .296106 | Social |

After sharpening, expanded4 contrast .5 has `ratioMax=1.131784` (.178599 stop); contrast 1 has `1.258121` (.331270 stop), just below a third stop. These later values do not control the current fallback branch. Gain measurement's spatial cell size and pre/post-sharpen stage must be recorded alongside boundary claims.

The JVM test `sdrPhotoGradedWithDefaultLooks_staysBelowTheUltraHdrThreshold` includes contrast 1 but does not run JPEG decode or exercise actual export. The original Android `sdrOnlyStoryWithUltraHdrRequestExportsSocialJpeg` now fixes contrast .5. Passing those tests together does not pin the Android contrast1 boundary.

### Exact Android decoded input

The added `azureContrastSettingsUseTheMeasuredHeadroomBoundary` uses the original `sdrPhotoJpeg()` once, decodes it once as the app does, and grades that exact input at both .5 and 1. It records pre-sharpen ratio/stops, resulting format, decoded base sampling/center pixel, gain presence, encoded ratio and dimensions. Assertions follow the measured eligibility contract; they do not pin fixed numeric ranges. Device execution was performed by the main agent against the final frozen source on API35 redroid x86_64/ANGLE SwiftShader. The20-test export suite passed; the [retained per-test log](evidence/api35/logcat-com.akane.akastudio.core.export.device.SdrOnlyUltraHdrDeviceTest-azureContrastSettingsUseTheMeasuredHeadroomBoundary.txt) records:

| Same decoded JPEG, grade | Pre-sharpen ratio / stops | Output / base | Encoded gain metadata |
| --- | --- | --- | --- |
| AZURE contrast .5 | 1.1451452 / .1955305 | Social JPEG,4:4:4, no gain; center ARGB `ff04bcd4` | Not applicable |
| AZURE contrast1 | 1.2827575 / .3592485 | Ultra HDR,4:2:0, gain present; center ARGB `ff10c5e0` | ratioMax `[1.3054727,1.3054727,1.3054727]`;270×480 gain |

Thus the historical Android ~.36-stop contrast1 behavior is freshly reproduced, while the exact JVM fixture remains below the boundary. The post-sharpen encoded ratio differs from the pre-sharpen eligibility ratio by design. This is software-renderer/file evidence, not HDR display approval. API36 targeted compatibility results are recorded in REMOTE_QA.md.

## L6: lower clipping in the same seam

At baseline, `LumaSharpen.kt:63` reconstructs RGB from adjusted OKLab lightness without a gamut bound. On the existing 16×8 red4/white4 image, default sharpening produces edge RGB `(3.832031,-.022919,-.012405)`. `GainMapMath` keeps negative channels in both luminance measurement and base, then `Dither` changes them to zero.

| Case | Baseline measured HDR cell Y | Baseline reconstructed Y after lower-clamping the base | Nonnegative HDR target Y |
|---|---|---|---|
| Real sharpener red/white worst signed-target cell | .83497906 | .83988446 | .84001905 |
| Deliberate uniform `(0.2,-.05,2)` half input | .15115835 | .18384722 | .18690965 |

These measurements retain current gain-code quantization and half base, but isolate **lower clipping before 8-bit/JPEG loss**. The real edge differs by .587488% from its signed measured target. The deliberate input differs by 21.625581%; its decoded 8-bit reconstruction is approximately `( .195631,.012948,1.841629 )`, still about 1.65% below the nonnegative target in luminance. This is not a claim that a typical photo has 21% error.

A numerical probe that clamps working HDR RGB nonnegative **before** peak/base/gain computation makes the uniform deliberate case exact before 8-bit storage; its decoded 8-bit luminance becomes `.18687928` for target `.18690965` (about .0163% low). The real edge's worst cell error against the **nonnegative** target is about .019753% (remaining half/gain rounding). Its baseline signed-target error and corrected nonnegative-target error use different targets and must not be presented as the same metric. Negative linear sRGB values represent out-of-gamut coordinates, not negative physical light. A choice of gamut mapping is unavoidable at this encoder boundary. Clamping is the smallest policy matching the encoder's existing behavior; a perceptual gamut mapping would be a separate visual product change.

For the same nonnegative target before 8-bit storage, the real edge's worst relative error changes only **.021183% → .019753%**, while the deliberate signed case changes **1.638453% → 0%**. The real edge demonstrates reachability and violation of the base's `[0,1]` contract; it does not demonstrate a large visual luminance correction. In `cell_clamp_error` TSV rows, the final column is worst error against the nonnegative target; earlier columns describe the worst signed-target cell.

Focused correction independently reviewed and applied after main's red run: use `max(0, finite(channel))` consistently in both peak luminance and tone mapping; derive both base and gain from that representable target. The positive-channel upper bound, HDR-first sharpening, boost, and threshold constant remain. This **can change `ratioMax` and therefore fallback on signed input**, because it changes the measured representable target; no claim of universal format invariance is made. The numerical fixture set above keeps its previous outcomes. Clamping the base alone would leave gain measured against different data. Two focused JVM regressions cover a deliberate signed input and the reachable sharpened edge, base `[0,1]`, `ratioMax`/`compute` parity, and decoded-base cell luminance against the nonnegative target. Main's red run observed both new tests fail: negative base `-9.584427E-4` at the real edge and `.016429497` relative error against the sanitized synthetic target. Main agent owns green and broader validation.

## Product options to decide

| Option | SDR appearance / format | HDR numerical behavior | Cost and unresolved tradeoff |
|---|---|---|---|
| A. Keep current upper-bounded scalar base | Mixed saturated highlights can be darker than clipped Social; graded SDR may cross the threshold | Corrects the large clipping mismatch; keeps existing cell/epsilon/codec error budget | Smallest change. Needs explicit acceptance of the base's look and channel-derived eligibility. L6 correction is separable. |
| B. Choose a Social-like SDR base, encode RGB gain against it | Can retain the clipped bright SDR appearance | Three ratios can reconstruct all nonnegative RGB channels of flat cells before storage loss, including pure red without epsilon contamination | Android's API accepts RGB gain bitmaps; this repository's compressed RGB-gain path is unimplemented and unverified. Requires result/encoder changes, larger gain data, per-channel ranges, actual platform encoding/decoding validation, spatial tests, and display-transition review. It is a mathematical/API-supported candidate, not a proven integration or approved fix. |
| C. Choose a different SDR tone/gamut mapping and target rendition, then derive gain against that exact base | Can intentionally balance highlight lightness, hue and saturation | Scalar gain only works accurately when the two renditions have compatible RGB ratios/offsets; RGB gain permits more freedom | Requires a defined visual target and controlled image review. Zero epsilon or a different scalar factor can remove some flat-pixel bias but needs black/low-light/downsample tests; it is not a free guarantee of exact reconstruction. |
| D. Keep the current valid gain/base, but make eligibility an explicit separate policy | E.g. prefer Social for small channel-only overshoots or an explicit SDR export preference | Accepted Ultra HDR still uses the valid base/gain relation | Can reduce marginal 4:2:0 conversions; also omits real colored HDR unless policy preserves it. Needs boundary requirements based on scene/output intent; raising one number blindly is not enough. |
| E. Restore luminance-only base without recomputing gain after its encoder clamp | Bright clipped SDR look returns | Reintroduces the proven red4→1.7448 error | Known regression. It is not the only alternative to option A. |

Example for B: with `e=1/64`, a clipped base `(1,.5,.199951)` for the M1 input can use ideal gains `(1.295192,1,1)` and reconstruct the input exactly before quantization. Red4 can use `(3.953846,1,1)` against `(1,0,0)`. This preserves the chosen SDR base while separating per-channel highlight expansion. A 4×4 downsampled gain still cannot recover arbitrary subcell color structure exactly, and JPEG modifies both layers.

## Recommendation for the product decision

Retain option A as the numerically valid stabilization baseline while comparing the supplied SDR examples; do not revert the known AUD-03 mismatch. For a release decision, I recommend accepting A only if the darker mixed-highlight SDR rendition and the measured .5/1 format switch are acceptable to the product. If preserving the Social-like bright SDR appearance is a requirement, prototype B (RGB gain against an intentionally chosen SDR base) before release and validate its actual compressed output; do not assume that representation support proves codec/display quality. Keep eligibility policy D explicit rather than interpreting source SDR metadata as a complete visual target. These are recommendations and conditional next steps, not user approval of any option.

## Physical HDR limits and evidence needed for visual approval

The linear numbers above are relative to SDR white. They do not determine absolute display luminance. Actual HDR rendering requires a capable display, correctly configured HDR window/activity, and available headroom. The Android API attenuates gain when available display headroom is below the encoded full-HDR ratio. Ambient light, brightness, power/thermal state, app/viewer behavior and display tone/gamut mapping can change perception. An emulator's successful encode/decode and manual reconstruction verify file/math behavior, not physical HDR appearance. SDR screenshots can compare the base only.

To make the product choice, review the same image/settings in an SDR-only viewer and a known physical HDR viewer at recorded display conditions. Include the existing red/white and boosted cyan/white fixtures, the mixed M1 highlight, natural skin/sky/gray gradients, normal SDR, and the original JPEG-decoded AZURE fixture at both contrast settings. Preserve each input byte stream and settings. Record the base JPEG, gain metadata, output format, and full-gain reconstruction errors separately from visual preference. No option can restore source information already removed before this encoder; a gain map preserves a selected HDR rendition, not scene radiance by itself.

## Remaining verification and decision questions

- Exact API35 Android contrast1/.5 metrics are now recorded above, retaining both settings and the original fixture. Numeric boundary pinning remains a coverage choice after the eligibility policy/tolerances are product-approved; the paired test presently checks pipeline consistency.
- Main final Gradle gate passed:951 valid JVM records,56 screenshots, lint0 errors/4 warnings, debug/release builds. New gain regression class12/12. Final device coverage and cached-vs-fresh distinctions are in HANDOVER.md.
- Inspect decoded base and gain outputs on real image edges, not just patch centers; existing device reconstruction checks avoid boundaries and do not test display interpolation.
- Explicitly choose the desired SDR highlight appearance, eligibility policy, and acceptable RGB/cell reconstruction error. Approving one does not implicitly approve the others.
- Obtain physical HDR display evidence for visual approval. Main-agent emulator/device numerics must remain labeled by their actual environment.

No M1 product option is marked approved by these numerical tests.
