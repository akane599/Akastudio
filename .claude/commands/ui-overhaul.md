---
description: Visual overhaul of the Compose UI. You write the brief and tokens (approval gate), then theme → parallel screen workers → visual + code review → integrate
argument-hint: "[scope: \"all\" | screen/package names] [optional design direction]"
---

Overhaul the Compose UI. Scope and direction: $ARGUMENTS
Load the `compose-design` skill. You decide the design direction and own the theme; workers implement screens.

## 1. Inventory and baseline
- A read-only Sonnet `Explore` subagent returns, without dumping files: the screen-level composables with paths; the theme files and whether tokens are real or defaults; counts of `Color(0x…)` and raw `.dp`/`.sp` outside `ui/theme`; existing `@PreviewTest`s.
- Baseline: `bash .claude/kit/gradle-check.sh :app:updateDebugScreenshotTest :feature:studio:updateDebugScreenshotTest`, then copy the reference PNGs to `design/before/` and restore the references with git.
- Read 3–4 baseline images and write a short, honest critique.

## 2. Brief and tokens: STOP for approval
Following compose-design §4 steps 1–3: palette hexes with roles, type pairing and scale, shapes, spacing, motion, the one memorable element, and a one-line intent per screen in scope. **Wait for the user's approval.** Nothing is implemented before it.

## 3. Theme first, then screens in parallel
- **Theme** (main session or one Opus/high worker): `ui/theme/*` only, with the approved brief verbatim. Verify: `:app:compileDebugKotlin :feature:studio:testDebugUnitTest`. Commit it before screen work starts.
- **One Opus/high worker per screen** (or feature package), launched together, each with `isolation: "worktree"`, its exact files and its own reference PNG paths. Brief: the approved brief, that screen's intent line, "load compose-design and compose-stability", previews (light, dark, fontScale 1.5, widthDp 840). Verify: the module's `updateDebugScreenshotTest` and unit tests, keeping only its own reference PNGs. A worker that needs a missing token reports it instead of editing theme files.
- Gradle runs follow `.claude/rules/android-build.md` (resource policy, shared host).

## 4. Review and integrate
- Integrate the screen worktrees one at a time, then run, in parallel, two fresh-context reviewers:
  - visual (Opus): the after-screenshots plus the brief; a designer's critique against compose-design §3 and its calibration list;
  - code (Opus/xhigh): the overhaul diff; Compose stability, Modifier order, accessibility, token discipline.
- Fix what both reviewers raise first. Logic changes the overhaul uncovers become separate, separately reviewed changes.

## 5. Prove and record
- `rg -n "Color\(0x|[0-9]+\.(dp|sp)\b" <ui dir> --glob '!**/theme/**'` → near-empty; justify survivors.
- `rg -n "List<|Map<|Set<" <ui dir> --glob '*.kt' | rg -v "Immutable|Persistent"` in composable params and state → empty.
- `bash .claude/kit/gradle-check.sh :app:assembleDebug :app:validateDebugScreenshotTest :feature:studio:validateDebugScreenshotTest` on the integrated tree. Copy the refs to `design/after/`.
- PROGRESS.md: decision-log lines (tokens chosen, ideas rejected) and the audit-table UI row. Summary: what changed visually, screenshot paths, open issues, short enough to scan.
