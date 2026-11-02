# Stabilization source review

Reviewed 2026-10-26 against local `main` at `299e568`, with raw patches from `69e558b..299e568`, relevant current production code, ownership/scope tests, `AUDIT_REPORT.md`, `PROGRESS.md`, retained logs and existing JVM XML. This review is limited to AUD-01..AUD-08 stabilization and the named remaining issues; it is not a new repository audit.

Requested reviewer configuration: GPT-6.1 Sol / high. The tool session does not expose authoritative runtime model/effort metadata, so the actual configuration cannot be independently verified. No claim of a verified model override is made.

## Confirmed and corrected: D8 crossed generation boundaries

At the reviewed commit, `StudioViewModel.renderScope` captures a scene and awaits `renderPreviewPixels` in the ViewModel-wide scope. Reset retires the story but leaves that render alive. A renderer failure then unconditionally writes its mapped error into the studio's current state. Owner-qualified image keys protect registrations; they do not protect this error write.

Trigger: enable scopes, hold an old render, reset and import another story, then let the old render fail. Impact: an error belonging to a discarded story appears in the replacement story. The UI exposes scope selection, discard/reset and subsequent import, so the schedule is reachable without a custom caller. It does not require a physical EGL failure: deleting the retired scene's registrations before a queued old render uses them is another source-level route to an error.

There is also a success route: `sceneRevision` starts at zero for every new `StudioState`. A new scope-enabled story can therefore have the same revision as the old captured scene, allowing the old readback through `scopeRendered`. Impact: incorrect waveform/histogram pixels appear until the new readback finishes. Revision equality alone is insufficient across generations.

Release decision: fix before declaring the requested ownership/UI contract complete. The independently reviewed minimal fix is now applied locally: capture the current generation identity with the scene; accept success only while that generation remains current, retaining the reducer's revision/scope guard. Accept errors only for that same generation and still-current revision with scopes enabled; log abandoned failures and continue to rethrow cancellation. Renderer lifetime, cancellation and `ScopeKey` are unchanged.

`StudioScopeOwnershipTest` gates old renderer success/failure across replacement, deliberately repeats the revision and enables the replacement scope, verifies that the replacement obtains its own render, and covers superseded-scene/OFF failures plus an active failure that must remain visible. The coordinator ran these tests before the production fix: five tests, four expected assertion failures (`.claude/kit/logs/gradle.st9Ds2pz.log`). Those failures demonstrated retired pixels entering the replacement, a retired error entering the replacement, a superseded scene's error appearing, and an error appearing after scopes were disabled. The active-scene failure test passed. An earlier compilation attempt failed because the new fixture omitted the required clusters argument; that was corrected before the red run and is not defect evidence. Post-fix final gate passed (exit0, `.claude/kit/logs/gradle.n1Pk1Xua.log`): scope5/5 and all studio254/254. Detailed final evidence is in HANDOVER.md.

## Ownership and cleanup plan assessment

`PhotoOwner` separates stable candidate identity from process-unique registration identity, and the store carries it on every preparer call. Namespacing the renderer keys, while preserving candidate IDs, directly addresses both same-ViewModel replacement and simultaneously live studios. The explicit retry exception in the ownership contract is sound: another owner's release may retry a previously failed unregister, but only under that failed registration's own key. A successful re-registration clears that key's pending retry.

`StoryGeneration` gives session, store and jobs one lifetime. Retirement marks it retired and cancels its child scope before cleanup joins work and releases only that store's keys. The host identity check and retirement count protect replacement imports from old pipeline/retirement recovery. `PreparedPhotoStore` preserves ordinary preparation failure/cancellation and attaches ordinary cleanup failures as suppressed; it logs failures attached to cancellation. These changes are well targeted. At the inherited baseline, D8 was the remaining ViewModel-wide asynchronous state writer outside this generation ownership boundary; this continuation corrected and verified it.

## Named residuals

| Item | Trigger and impact | Reachability and release decision |
|---|---|---|
| L1 | Replacement preparation can take the FIFO preparer mutex before retired cleanup and briefly hold both photo sets. Peak memory increases. | User-reachable discard/reimport schedule. No retained measurement establishes an OOM or broken memory limit; resource risk, not a reproduced crash. Any proposed ordering change must cover two live studios and preserve owner isolation. |
| L8 | A second `CancelWork` while a restore waits for abandoned work retires the generation. | ViewModel API reachable. Current Preparing UI uses discard confirmation/Reset rather than this second cancellation path. Not a current UI release blocker; document/test if changing the API behavior. |
| D6 | Store removes old-budget cache entries before reprepare, and failed acquisition releases those IDs. The restore then prepares the stable selection again. A persistent source failure can prevent restoration of previously cached pixels. | Mode change is user reachable; ordinary recoverable hero-budget failure can still recover at the previous budget. This is a recovery/resource behavior issue, not evidence of a stale newer-owner registration being removed. Avoid widening the ownership fix without a dedicated failure schedule. |
| RTL `50+` | `ValueReadout` uses paragraph direction inherited from RTL for a signed Latin numeric readout, allowing `+50` to display as `50+`. | Reachable RTL controls. Presentation defect; the corrected track geometry is independently sound. A fix should constrain readout text direction and retain RTL layout/labels. |

## Evidence and limits

This reviewer did not invoke Gradle, devices, emulators, commits, pushes or Sidequest; the coordinator ran the explicitly identified new regression batch. Before that batch, retained JVM XML had 944 tests, zero failures/errors/skips: studio 249, ingestion 145, curator 198, color 166, export 120, composer 66. Its timestamps were 2026-10-21, so that baseline is historical evidence rather than fresh validation of today's work.

The retained `ownership-nofix-2.out` records a combined mutation with 4/4 preparer ownership failures, 4/6 story ownership failures and two ViewModel failures. This supports regression sensitivity, but the combined mutation does not isolate every changed invariant. `full-gate-final.out` records exit 0 and points to its Gradle log. Existing suite/log claims should remain attributed to those earlier runs.

Useful test gaps remain: immediate-main scheduling, a renderer/GL dispatcher distinct from IO, retirement carrying an original error followed by cleanup failure, transparent Ultra HDR base encoding and full Frosted transparent export. A missing test is not itself a verified defect. Production uses `Dispatchers.Main.immediate`; the new ownership tests use queued main/default/IO test dispatchers. Source inspection did not establish a new production-reachable immediate-main bug.

AUD-03's saturated SDR-base behavior remains a documented product decision, and physical GPU verification remains missing. Existing SwiftShader results do not establish physical-device precision/driver behavior.
