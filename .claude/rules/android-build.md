# Android build, test and dependency rules

- Gradle: always preserve its exit code. From the checkout root use `bash .claude/kit/gradle-check.sh <tasks>` (full log retained in `.claude/kit/logs/`, last 80 lines on failure), or raw `./gradlew <tasks> --console=plain -q` if the helper is absent. Never use a grep/head pipeline as the pass/fail oracle.
- Verify with the narrowest task: `./gradlew :<module>:testDebugUnitTest --tests '<Class>' --console=plain -q`; UI changes add `:<module>:validateDebugScreenshotTest`. Only a change that intentionally alters UI updates references, via `updateDebugScreenshotTest`.
- `updateDebugScreenshotTest` rewrites every reference image in the module: before committing, restore any reference outside the screens you meant to change. When several workers change UI in parallel, one owner updates the app-level references (`app/src/screenshotTestDebug/`) after the feature UI work lands.
- Tests that check thread names must compare by prefix (`startsWith`), never exact equality. Under the coroutine debug mode that tests run with, thread names gain a " @coroutine#N" suffix, which made an exact-match test flaky.
- Batch the necessary Gradle tasks per change set rather than running per file. Rerun relevant checks after fixes or to resolve a concrete failure.
- Before Gradle verification, run `python3 .claude/kit/resource-policy.py` and use its current `gradle_command_limit` (machine-wide active Gradle commands, across Claude/Codex projects and worktrees) and `gradle_workers_per_command` (pass `--max-workers=N`). When capacity is occupied, queue only the verification and keep doing independent work. This is advisory coordination, not a lock; don't edit Gradle heaps, swap or OS settings, and always re-probe rather than trusting a saved resource-policy.json.
- The host is shared with another Claude session (com.akane.inspiration): never run `./gradlew --stop`, kill Gradle/java/adb processes, or `adb kill-server`.
- Never bump compileSdk/targetSdk, AGP, Gradle, Kotlin or the JDK target, and never add a dependency, unless the task asks for it. Dependencies live in `gradle/libs.versions.toml`.
- The SDK comes from `ANDROID_HOME`. Don't create or edit `local.properties`.
- Never read, print or commit keystores (`*.jks`, `*.keystore`), `local.properties`, `google-services.json`, `.env*`, `.claude-plugin-config.json`.
- Library APIs: check context7 instead of recalling from memory.
