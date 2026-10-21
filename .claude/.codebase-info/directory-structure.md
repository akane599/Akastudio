# Directory Structure
*Last Updated: 2026-10-21*

Package roots: `com.akane.akastudio` (+ `core.<name>`, `feature.studio`). Paths below are relative to `<module>/src/main/kotlin/com/akane/akastudio/…` unless noted. Every library module also has `AndroidManifest.xml`, `consumer-rules.pro`, `proguard-rules.pro`.

```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml   version catalog
PROGRESS.md                       baseline + decision log
.claude/kit/gradle-check.sh       Gradle wrapper helper (see onboarding)
app/                              AkaStudioApp.kt, MainActivity.kt, res/values (themes, strings)
                                  src/screenshotTest + screenshotTestDebug/reference (StudioAppScreenshotsKt)
core/ingestion/    core/ingestion/{api,decode,metadata,model,picker,pipeline,zip}, IngestionFactory.kt
core/vision-curator/
  core/curator/{api,metrics,saliency,hash,ml,decision,engine}
  src/main/assets/models/        MODELS.md + 4 model binaries (contents not mapped)
core/color-engine/
  core/color/{api,math,analysis,harmonize,look,grade,gl,render,render/grade}
  src/main/assets/shaders/       *.frag.glsl, quad.vert.glsl, grade/grade_{bright,pixel,finish}.frag.glsl
core/collage-composer/  core/composer/{api,compose,crop,horizon,layout}
core/export-pipeline/   core/export/{api,encode,process,pipeline,store}
feature/studio/
  feature/studio/{di,domain,mvi}
  feature/studio/ui/{StudioRoute,StudioScreen, dropzone, canvas(+scope), controls, note, theme}
  res/values + res/values-tr     strings (en, tr)
  src/screenshotTest + screenshotTestDebug/reference   canvas, controls, dropzone, theme
```
Each module's JVM tests: `<module>/src/test/kotlin/...` mirroring main packages. Build outputs (`*/build/`) are not mapped.
