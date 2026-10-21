# Directory Structure
*Last Updated: 2026-10-21*

```
akastudio/
├── settings.gradle.kts          # includes :app only (for now)
├── build.gradle.kts             # root plugin aliases
├── gradle/libs.versions.toml    # version catalog (single source of versions)
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/com/akane/akastudio/
│       │   ├── MainActivity.kt
│       │   └── ui/{home,theme}/  # scaffold Home screen + design tokens
│       ├── test/kotlin/…/ui/home/HomeViewModelTest.kt
│       ├── screenshotTest/kotlin/…/ui/home/HomeScreenScreenshots.kt
│       └── screenshotTestDebug/reference/  # committed screenshot refs
├── CLAUDE.md, PROGRESS.md       # project instructions / decision log
└── .claude/                     # android-kit: live-rules, kit scripts, skills, this map
```

Planned (see [architecture.md](architecture.md)): `core/ingestion`, `core/vision-curator`, `core/color-engine`, `core/collage-composer`, `core/export-pipeline`, `feature/studio`, each a Gradle module with `src/main/kotlin/com/akane/akastudio/<area>/`.
