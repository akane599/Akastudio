---
name: update-screenshots
description: Update Compose screenshot reference images for one module, keeping only the screens in scope and restoring every other reference that updateDebugScreenshotTest rewrote. Use only for a change that intentionally alters UI.
disable-model-invocation: true
argument-hint: <module> <keep-pattern>...
---

# Scoped screenshot reference update

`updateDebugScreenshotTest` rewrites **every** reference image in a module. This skill runs it, then keeps only the references that match your patterns.

## Steps

1. Make sure the UI change is intended and that `:<module>:validateDebugScreenshotTest` fails only on the screens you meant to change. Run it first if you haven't, and read the diff report. If another screen changed, that is a regression, not something to update.
2. Run:
   ```
   bash .claude/skills/update-screenshots/scripts/scoped-update.sh $ARGUMENTS
   ```
   - `<module>` is `app` or `feature:studio`, the two modules with screenshot tests.
   - Each `<keep-pattern>` is a bash glob matched against the path under `src/screenshotTestDebug/reference/`. Examples: `'*/StudioScreenBackScreenshotsKt/*'`, `'*StudioAppReady_*'`.
   - The script refuses to start if the reference dir already has uncommitted changes.
3. Look at every kept image with Read, comparing the new image with `git show HEAD:<path>`, and check that it shows the intended change in light, dark, fontScale 1.5 and widthDp 840.
4. Run `:<module>:validateDebugScreenshotTest` again. It must pass.
5. When several workers changed UI in parallel, only one owner updates `app/src/screenshotTestDebug/`, after the feature UI work has landed.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | In-scope changes kept |
| 1 | Nothing matched the patterns. Every change was reverted, so fix the patterns and rerun. |
| 2 | Usage error, or the reference dir was dirty |
| any other | Gradle failed. The full log path is printed. |
