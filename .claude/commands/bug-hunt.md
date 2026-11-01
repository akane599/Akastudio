---
description: Hunt for bugs in existing code. Read-only Opus reviewers by area with the Android bug checklist, then one fix per bug you pick, each starting from a test that reproduces it
argument-hint: "[scope: path, module or feature | blank = the riskiest areas from the codebase map]"
---

Find bugs in existing code. Scope: $ARGUMENTS

1. **Size the coverage.** Use the scope, or when it's blank, `.claude/.codebase-info/` to pick the riskiest areas first (ingestion and zip handling, concurrency and background work, GL resource ownership, state restoration and navigation, export encoding). One reviewer per bounded area; split only when areas need distinct context, and never give two reviewers the same source tree. State which areas are covered and which are not.

2. **Dispatch read-only reviewers in parallel**, one per area, in a single message: `code-audit-bugs-high` agents (or `general-purpose` on Opus with an explicit read-only instruction). Each brief contains only: the area's paths; the absolute path of `$CLAUDE_PROJECT_DIR/.claude/kit/android-bug-checklist.md`; "Hunt for real bugs in this area with that checklist and report in its format. Run no Gradle task and edit nothing." Don't hunt yourself or pass findings between reviewers while they run.

3. **Triage once.** Merge the reports and drop duplicates. For each finding, read the cited lines and the necessary callers yourself and keep it only if the scenario holds. Show the user a numbered list (severity, one line, `file:line`) and let them pick what gets fixed. Unpicked findings go to PROGRESS.md's "Open issues" only if the user says so.

4. **One fix per picked bug**, in parallel where the files don't overlap (`isolation: "worktree"` for each editing worker):
   - First a test that reproduces the bug and fails on the current code (keep the red output), then the fix until it passes. A device-only bug gets an instrumented test under the module's `src/androidTest`, run under the emulator lock.
   - Verify with the module's narrowest test task through `.claude/kit/gradle-check.sh`.
   - A crash, data-loss or export-format fix gets an independent fresh-context review before commit.
   - Integrate each fix as its own commit, then run the relevant gate on the integrated tree.

5. **Record:** a "Bug hunt" row in PROGRESS.md's audit table (date, areas, found / fixed). If a bug reveals a convention worth keeping, propose a line for `.claude/rules/`.
