# AkaStudio physical QA and M1 decision — 2026-10-26

**Disposition:** the two red physical tests are resolved, and M1 is accepted as **C shoulder + shared Social/Ultra SDR rendition + full-resolution scalar gain (C1)**. Independent review accepts the scoped changes. Five natural inputs × two settings passed the unchanged numerical guards with real physical-GPU renders and real encoded JPEGs. Three additional RGB-prototype comparison files remain pending the next phone connection; the failed RGB4 variant is not selected.

This supersedes the open tile/drawer/M1 disposition in [PHYSICAL_QA.md](PHYSICAL_QA.md), [HANDOVER.md](HANDOVER.md) and [M1_DECISION.md](M1_DECISION.md). Historical failures and artifacts remain preserved. No commit, push, PR or publication was performed.

## Decision and evidence

1. **SDR appearance:** choose C, a smooth shoulder on maximum linear RGB with knee `.6`, rather than A's broad Reinhard compression or clipped Social highlights. In-range frames remain unchanged; in an HDR frame, pixels whose maximum channel is ≤`.6` remain unchanged. Above the knee, the shoulder starts with slope1, stays monotone, maps the frame peak to1 and scales RGB uniformly. This preserves pre-codec channel ratios. A saturated peak can also compress high SDR colors above the knee; a dedicated regression records that tradeoff.
2. **Format:** explicit Social stays JPEG4:4:4. Explicit Ultra retains usable preflight gain at the existing `.01-stop` gain-math floor; unit gain falls back to Social. The old1/3-stop suppression is removed. Preflight and final gain use the same full-resolution grid. Preflight remains before resampling/sharpening: a later sharpen overshoot alone does not change a unit preflight into Ultra. Gain-bearing output does not establish HDR-capture provenance.
3. **Numerical budget:** preserve the existing tile/flat-patch/L6 bounds and the independently accepted, predeclared natural-image guards below. C4, RGB4 and C2 fail the night RGB tail. C1 passes all10 representative cases without raising a tolerance. This is a percentile-based suite acceptance, not an arbitrary-photo or per-pixel maximum guarantee.

C1 keeps the scalar result/encoder representation and existing factories. `ExportProcessor.GAIN_DOWNSCALE=1` is passed to final `compute` and both preflight `ratioMax` calls. The generic math defaults remain4, as do frozen A and historical4×4 numerical controls. No RGB production API or general framework was added.

Social and Ultra use the same C SDR rendition after resizing and HDR-first sharpening. `sdrBase()` shares the exact mapping without gain-cell/code allocation. The controlled UHD comparisons start from bit-identical renders; all final C1 Social JPEGs and decoded SDR bases are byte-identical to their C4 controls. Their JPEG chroma policies still differ. At HD, Social retains its existing2× render/Lanczos plan, so the synthetic HD cross-format pairs are not asserted to share a bit-identical starting frame. PNG/WebP/preview policies were not changed.

## Why C1 wins

The five licensed SDR-origin photographs cover skin, bright clouds/sky, warm colors/shadows, twilight gradients and saturated night signs. Original files, exact crops/grades, settings, source/license links and hashes are in the [delivery index](evidence/natural-session/delivery-index.json) and [source manifest](../../core/export-pipeline/src/androidTest/assets/natural-comparisons/sources.json). Inputs are never labeled HDR captures. Both typical and stress outputs use2160×3840, production GL, AZURE contrast`.5` or contrast1/exposure+1, HDR Boost off and production sharpening.

Matched final A/C SDR bases show C retaining the portrait's lighter skin/midtones and the scene's color character without A's broad darkening. Clouds retain texture with a softer shoulder; warm shadows and night colors avoid an unrelated global exposure shift. Earlier matched-stage clipped Social controls expose lost highlight detail. Those older-stage files have a different render hash from the final stage and are labeled historical, not used as exact final-frame quantitative comparators. Final frozen-A JPEGs are actual platform encodes against the exact final render, with the old gain/base math, not a recreation from screenshots.

The appearance choice does not establish the gain representation. Actual encoded alternatives on identical portrait/night targets and C bases gave:

| Gain representation | Night cell max-RGB p99 | Night cell Y p99 | Frozen guard |
|---|---:|---:|---|
| Scalar4×4 |.094972|8.057%|FAIL RGB|
| RGB4×4 |.093914|8.241%|FAIL RGB|
| Scalar2×2 |.053507|3.134%|FAIL RGB|
| Scalar1×1, selected |.035097|1.336%|PASS|

Actual RGB4 JPEGs retained three-channel metadata and identical primary SDR pixels, but their secondary JPEG is itself YCbCr4:2:0. RGB4 improves portrait RGB tails, while barely improving the failed night tail and worsening its luminance tail. C2 substantially reduces spatial loss but still misses `.05`; it was not rounded into a pass. C1 materially improves night point RGB MAE `.004826→.002001` and point RGB p99 `.119562→.038188`; point maximum remains `.479836`. See the [actual density comparison, files and allocation costs](evidence/akadroid-m1-completion/rgb-gain/density-comparison.md).

C1's primary JPEG remains4:2:0; its scalar secondary is full2160×3840 grayscale. Selected production portrait/night Ultra files are1,923,733 /1,543,522 bytes, versus1,140,658 /1,238,885 for C4, about+68.7% /+24.6%. At UHD, logical incremental allocations versus C4 are about37.1MiB for compute log/code arrays,7.4MiB native ALPHA gain plane and7.4MiB heap staging. Their lifetimes differ; these are not an RSS or simultaneous-peak measurement. The real phone completed the cases; universal memory/thermal safety and isolated export latency are not established by the diagnostic harness times. The fidelity benefit justifies this bounded cost for M1.

## Numerical acceptance

The [fixed guard policy](evidence/natural-session/numerical-guard-policy.json) was recorded before the final night data and accepted independently. Each fixture/settings case is evaluated separately, over selected true4×4 output-cell means, using all16 pixels. Evaluation cells do not change with gain density. Sample phases balance all16 original-cell positions.

- Cell Y error `abs(mean reconstructedY−mean targetY)/max(mean targetY,.05)`: p95≤5%, p99≤10%.
- Per-cell maximum across RGB of the absolute channel-mean error: p99≤`.05` linear, meaning5% of SDR white, not5% of each target channel.
- Existing synthetic RGB, signed/partial-cell L6 and tile bounds stay unchanged. Edge subsets and maxima remain visible.

All10 C1 cases pass. Worst all-case values are RGB p99`.035097`, Y p95`1.224%`, Y p99`1.743%`. Stress results:

| Input | Cell max-RGB p99 | Cell Y p95 | Cell Y p99 | Maximum cell RGB |
|---|---:|---:|---:|---:|
|astronaut|0.032249|0.586%|0.953%|0.054797|
|coffee|0.024458|0.718%|1.152%|0.043438|
|rocket|0.016170|1.224%|1.743%|0.068194|
|shinjuku|0.035097|0.609%|1.336%|0.217932|
|clouds|0.027607|0.774%|1.174%|0.051378|

Night edge-cell RGB p99 remains`.092861`; its maximum cell RGB error is`.217932` and maximum floor-normalized cell Y error`8.687%`. Percentile success does not erase those outliers. Near-black/edge/interior, signed bias, maxima and point distributions are retained in the settings and [independent all-ten replay](evidence/natural-session/d1-all10-independent-cell-guards.json). Reconstruction combines decoded JPEG base and decoded gain analytically at full gain, using bilinear normalized-code interpolation. Nearest sampling is a sensitivity control. Neither is claimed to reproduce physical-panel interpolation exactly.

The independent host replay checks original input/settings, render, target-point/cell/edge and decoded-base hashes before reuse; device/host scalar4 comparisons agree within`2.66e-7`. Raw gain planes/strides, normalized PNG code adapters and lossless SDR PNG roundtrips are preserved. An initial diagnostic failed because Android cannot PNG-compress ALPHA_8 directly; the test adapter now replicates exact gain codes into opaque grayscale PNG and asserts each code/roundtrip. That failure was not a production JPEG defect and remains recorded.

## Tile red test

Raw tiled assembly, strip/default and FLOAT readback remain bitwise correct. The error belongs to scene rerasterization. A single neutral high-frequency photo layer reproduces it without crop, rotation, grading, Frosted background or rounded coverage. Controlled transform/filter/grading/background comparisons total65 scene/tile combinations.

The source used tile-dependent interpolated UVs. Small changes are amplified by LINEAR filtering; this does not prove an internal driver operation or driver nonconformance. The layer fragment now derives UV from the global canvas pixel center with existing uniforms. SDF/rounded coverage and grading shaders remain unchanged.

| Measurement | Before | After |
|---|---:|---:|
| Original maximum linear channel difference |.002319336|.001953125|
| Minimal neutral regression |.0034179688|.00048828125|
| Existing allowed maximum |.002|.002|
| Selected CPU-reference maximum linear error |.00341796875|.00341796875|
| Quantized sRGB maximum |2 codes|1 code|

This fixes tile invariance, without claiming universally better sampling accuracy. CPU RMS rose slightly (`.000199205→.000218287` linear; `.08510→.08752` code). Scaled/fractional/cropped/rotated/rounded/letterbox/split controls pass. Physical color33/33, all65 matrix comparisons and fresh shader checks4/4 pass; seven shaders compile. Final integration retained the preceding fresh166 color JVM records.

This phone's measured target limits are16384×16384. All supported1080×1920 and2160×3840 exports, including Social HD's2160×3840 render, use one tile here. The fallback triggers when either canvas dimension exceeds the measured texture/renderbuffer/viewport limit. Forced limits exercise it. Limit100 yields equalized72×96 tiles; the old worst pixel is local`(14,47)`, not`(58,47)`.

The real C4 encoded forced-tile control gives a byte-identical Ultra JPEG; Social differs at16/8,294,400 pixels by at most1 code (mean`1.61e-6` code). This is an actual production-codec control, separate from the earlier clamp/sRGB PNG/JPEG proxy. It is not labeled as a fresh C1 forced-tile export; the final gain-density run reused the unchanged GL controls. Full tile evidence: [completion directory](evidence/akadroid-m1-completion/), `tile-matrix*`, `tile-sampler*`, `tile-candidate-focused*`, `color-candidate-full*`, `tile-cpu-final*`, and natural final02 tile sidecars.

## Drawer red test and actual BACK

The same-package physical-window snapshot held a stale preview/top-bar/control tree: no hidden title, composite contentDescription or alternative title bounds. Refreshing UiAutomation's accessibility cache exposed the exact visible `Story mode` node. The fix is confined to the native observer, API34-guarded, with failure-only scoped tree diagnostics. No production Compose/ViewModel change or relaxed/removed title assertion was needed.

Actual system BACK first closes the drawer, retains story/selection/grade/template and opens no discard dialog. Second BACK opens discard. Focused case then full native lifecycle4/4 passed on physical display0 in11.446s. This establishes the current semantics/title and BACK behavior; exhaustive accessibility-event delivery is not established. [Observer diff](evidence/akadroid-m1-completion/drawer-observer.patch), [raw4-case result](evidence/akadroid-m1-completion/lifecycle-cache-refresh.txt).

## Physical display and capture

Existing direct VPS ADB was used without reconnect/setup: explicit`100.70.96.59:5555`, owner host`build-server`, SM-S938B/pa3q, hardware serial`R5CYA0WKL6Y`, Android17/API37, Adreno830. Every interaction used the owner-host shared lock`/home/akane/.cache/akastudio-akadroid.lock` and explicit serial/device selection. Mobile MCP read the actual AkaStudio hierarchy and performed navigation on this phone. No virtual display, scrcpy/mirroring or continuous recording was started.

Physical display is logical0 / SurfaceFlinger`4630947191136750483`, HDR types2/3/4, wide gamut, capability maximum ratio5. Final45-second viewer verified focus, hardware acceleration, HDR color mode2, source gain metadata and physical attachment for all8 portrait/night Social/A/C1/base files. It passed1/1 in45.469s; mobile MCP read the hierarchy, tapped NEXT and saved a screenshot.

**Current headroom differs from maximum.** The earlier fixed-artifact control reached current5 and showed no sampled capture-related drop. The final natural viewer rose from1.041 to **2.2847688** and settled there while maximum stayed5. Fifty100ms samples around the final capture window stayed2.2847688; no sampled drop was observed, with shorter transients unresolved. C1 portrait needs2.0030484 and fits that current headroom; C1 night needs2.542831 and does not. No full-gain physical-night appearance or emitted-brightness claim follows. [Final telemetry/capture summary](evidence/akadroid-m1-completion/final-D1-hdr-summary.json), [samples](evidence/akadroid-m1-completion/final-D1-hdr-samples.txt), [screen capture](evidence/akadroid-m1-completion/final-D1-viewer-capture.png); the saved final screen shows A, while telemetry confirms both C1 files were displayed.

**Night C1 headroom follow-up (2026-11-01):** the same night C1 Ultra HDR file (`shinjuku-stress_ultra-hdr.jpg`, SHA `79b3a2e5…b901`) was shown on physical display 0 of the same SM-S938B with the unchanged installed app (`1aec8035…afd1`) through `PhysicalHdrViewerDeviceTest` (45 s hold, 100 ms samples, OK 1/1; gain map present, window colour mode 2, focused). Current HDR/SDR ratio rose from 1.011 to **5.0** within about 1.5 s and stayed there; 429 of 444 samples were at or above the 2.542831 that night C1 needs. The night file's full gain is therefore within the panel's available headroom in this session. This is display-reported headroom, not emitted or calibrated luminance, and why the 2026-10-26 session settled at 2.2848 remains unisolated. [Summary](evidence/akadroid-m1-completion/night-c1-20261101/summary.json), [samples](evidence/akadroid-m1-completion/night-c1-20261101/hdr-samples.txt).

Captures and original files support SDR/base inspection, not direct physical HDR luminance, calibration or emitted nits. The450-nit declaration is not a measurement. The cause of the two sessions' differing available headroom was not isolated. Maximum5 was never substituted for the final usable2.2848.

Mobile MCP's session-owned daemon retained Android's single UiAutomation service after hierarchy inspection. Its supported stop command released that owned connection between native tests; shared ADB/Gradle/other applications were not stopped. The final owned daemon and device lock were released before offline reporting. Other-app permission was available but unnecessary.

## Source, artifacts and checks

HEAD remains`299e568d5e6ed660aba535fb7afd7a96b834943d`. Existing edits, commits, all seven registered worktrees and untracked files were preserved. The inherited ownership/L6 work was not reapplied or discarded. The [scoped working-source patch](evidence/akadroid-m1-completion/final-working-source.patch) and [patch provenance](evidence/akadroid-m1-completion/final-working-source-patch.json) retain the reviewable HEAD-to-working snapshot, including inherited changes in touched paths. No branch switch, pull/reset/clean/stash, dependency/toolchain/SDK/JDK change, screenshot-reference update, uninstall or data clear was performed.

Tested production snapshot: **`827d96dbe6204c426fa469d2b5d29c26f737ef0c69cf331131b52cef0f458fb3`**, [explicit287-file manifest](evidence/akadroid-m1-completion/final-source-manifest.json). Delivered source snapshot: **`1c8166af71110815b253c96299e06e787c6e1a15b3a550bcb9a66144c9598ebd`**, [manifest](evidence/akadroid-m1-completion/delivered-source-manifest.json). The only difference is two KDoc lines correcting a categorical memory-peak claim, with unchanged line count and behavior. All five rebuilt artifacts are byte-identical to the tested artifacts; [verification](evidence/akadroid-m1-completion/post-doc-artifact-verification.json), [build log](../../.claude/kit/logs/gradle.u8SAfO1B.log). Earlier candidate2b4fe30… included retained screenshot reference inputs in its explicit file list; that historical identity remains valid, while the final manifest separates production and test/reference lists.

- Installed debug APK **`1aec8035398dff4b604bc6c26bd68cc13cda7f25102a33f09dc059621676afd1`**; install`-r` succeeded with data retained.
- Export test APK **`4315a4b5580bf739b469fb585ea35afc77cb8181411db209501ed8027ef8ad83`**; app test APK **`b10eca3d2659a14b603a41bde8dfe3f243de8542d727515c85066816ed2ad0ae`**.
- Final integration passed: [Gradle log](../../.claude/kit/logs/gradle.BvsnOfHf.log),960 valid JVM records,0 failures/errors/skips;385 fresh (export131+studio254),575 retained valid. [Per-suite timestamps and lint](evidence/akadroid-m1-completion/final-integration-results.json). Lint0 errors/4 warnings; debug APK, unsigned release APK/AAB and both test APKs built. Prior56 screenshot results remain valid; no UI/reference input changed.
- New boosted/unboosted sparse-highlight regressions failed under C4 (wrong Social fallback), then passed under C1. They prove actual selected preflight/final-grid consistency; generic4 and the fixed1.5% historical cell control remain. [Density diff/red/green evidence](evidence/akadroid-m1-completion/m1-density.patch).
- Final C1 physical focused regressions **2/2** in30.996s: saturated decoded highlights plus numerical-floor/former1/3-stop continuity. Red4 reconstructs3.808611 (4.785% low), white4 to4.0171795 (.429% high), within unchanged10%/5% limits. [Raw result](evidence/akadroid-m1-completion/export-C1-focused-device.txt). Earlier full9/9 export and three synthetic C4 sets remain explicitly C4 evidence; they are not relabeled fresh C1.
- Final installed C1 app workflow **1/1** in10.184s: generated MediaStore import/curation/models/GPU/edit, background/resume, recreate/retain, real1080×1920 Social export, discard/reimport. [Raw result](evidence/akadroid-m1-completion/final-D1-app-workflow.txt), [actual file](evidence/akadroid-m1-completion/final-D1-workflow-social.jpg), [identity](evidence/akadroid-m1-completion/final-D1-workflow-artifact.json).
- Final production natural capture **1/1**,417.777s, all10 actual Ultra outputs and10/10 numerical passes. Source/APK bindings and reuse assertions are in [the natural record](evidence/natural-session/README.md). The original red adapter capture remains recorded.
- [Independent final review](evidence/akadroid-m1-completion/independent-final-review.md) accepts the scoped shader/observer/C1 changes with no unresolved correctness finding. It distinguishes inspected raw evidence from reports and does not grant whole-repository release approval.

## Deliverables and remaining work

[Download the curated real comparison package](evidence/akadroid-m1-completion/m1-comparisons-20261026.zip),156,735,125 bytes. [Package SHA/file identity](evidence/akadroid-m1-completion/m1-package-identity.json) records SHA256 `d2a121716815787ad72371f57a8c5be360a1a06175009ef99cdc0acdc4c5f066`; all164 original entries and17 shared-base mappings were verified. It contains five original licensed inputs, all10 selected Social/Ultra/decoded-base triples, matched frozen-A JPEG/base files, scalar4 controls, two-photo RGB4/C2/C1 prototype artifacts, synthetic A/C4 examples, settings/metric/hash manifests and attribution. Full raw targets/gain planes/logs remain in the linked repository evidence. Cloud derivatives retain CC BY-SA2.0; Shinjuku credit is Fboas/CC BY3.0, NASA/SpaceX public-domain and coffee CC0 credits are included.

All five selected stress triples, sources and license notice are SHA-verified on the phone at **`/sdcard/Pictures/AkaStudioQA/M1_C1_20261026_0314/`**; [device delivery manifest](evidence/natural-session/d1-device-delivery-manifest.json). Original synthetic examples and earlier public deliverables remain. Only exact session-owned private QA directories/generated media rows were cleaned after local archive verification; app/personal content was preserved. Test packages remain installed; no uncertain package ownership was resolved by uninstalling.

**RGB4 delivery completed (2026-11-01):** coffee/rocket/clouds RGB4 and scalar4-control exports were captured on the same SM-S938B/API37 with the retained test APK (SHA `4315a4b5…ad83`), run `rgb_remaining_20261101_195829`: instrumentation 1/1 OK; every Ultra JPEG carries MPF + `hdrgm`, a 3-component 4:2:0 SDR primary, and a 3-channel (RGB4) or 1-channel (scalar) gain image. All six fixture/variant pairs pass the unchanged guard (p99 cell RGB 0.0225–0.0306, p99 Y 1.58–2.14%), so typical scenes do not discriminate; RGB4 stays rejected on the night case and C1 remains selected. The [updated package](evidence/akadroid-m1-completion/m1-comparisons-20261101.zip) (164,103,983 bytes, SHA256 `074d08162d2fb3b53cc7ef7f0c4832737b77a42dd42abfc5f578e3efe957a549`, [identity](evidence/akadroid-m1-completion/m1-package-identity-20261101.json)) adds the 15 files plus their cell guards and run identity, with every manifest hash re-verified; the 2026-10-26 package above is unchanged.

No confirmed release blocker remains from the two red tests or the scoped M1 decision. Remaining limits are explicit: no subjective/calibrated physical-panel HDR measurement; final night available headroom below full-gain requirement; no universal photo/edge maximum or broad memory/thermal/device-fleet guarantee; gallery/share-app policies and signed release runtime were not established. Historical API37 Compose harness infrastructure failures remain separate from the passing native behavioral tests. This was not a new broad repository audit.

## Agents and routing

Root coordinated the device, integration, visual/product choice, final capture, packaging and report. Five native workers handled tile diagnosis/fix, drawer/M1 implementation, natural artifacts, RGB/density probes and independent review. Each worker was explicitly spawned with **GPT-6.1 Sol/high**. Effective runtime routing/effort cannot be independently introspected from these interfaces; the main runtime model/effort is likewise not exposed or switchable here. These are requested routes, not runtime attestations. No xhigh escalation or Sidequest workflow was used. Device actions were serialized under the common owner-host lock; host review/implementation/artifact work ran in parallel and Gradle followed the live2-command/4-worker policy.

| Native agent | Actual assigned work | Requested route | Runtime introspection |
|---|---|---|---|
| `/root/tile_precision` | Tile diagnosis, source correction and regressions | GPT-6.1 Sol/high | Unavailable |
| `/root/drawer_back` | Drawer observer and C/C1 production implementation | GPT-6.1 Sol/high | Unavailable |
| `/root/natural_comparisons` | Licensed natural inputs, real outputs and device delivery | GPT-6.1 Sol/high | Unavailable |
| `/root/rgb_probe` | Actual RGB/density prototypes and independent numeric replay | GPT-6.1 Sol/high | Unavailable |
| `/root/independent_review` | Separate plan/final-diff, evidence, report and package review | GPT-6.1 Sol/high | Unavailable |

The final ntfy progress/completion requests were accepted by the user-supplied notification server. No authorization credential was written into repository artifacts. Server acceptance is not a claim that a particular client displayed the push.
