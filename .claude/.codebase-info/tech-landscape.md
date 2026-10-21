# Tech Landscape
*Last Updated: 2026-10-21*

| Item | Value | Source of truth |
|---|---|---|
| Gradle | 9.8.0 | `gradle/wrapper/gradle-wrapper.properties` |
| AGP | 9.4.1 (built-in Kotlin, no `kotlin-android` plugin applied) | `gradle/libs.versions.toml` |
| Kotlin | 2.4.20 + Compose compiler plugin | `gradle/libs.versions.toml` |
| Compose BOM | 2026.09.00 (Material 3) | `gradle/libs.versions.toml` |
| SDK | compileSdk/targetSdk 37, minSdk 31 | `app/build.gradle.kts` |
| JVM target | 17 (built with JDK 21) | `app/build.gradle.kts` |
| Async | Coroutines 1.11.0, StateFlow | catalog |
| Tests | JUnit 4, coroutines-test, Compose Preview Screenshot Testing 0.0.1-alpha16 | catalog |

Repositories are locked to `google()` + `mavenCentral()` (`FAIL_ON_PROJECT_REPOS`) in `settings.gradle.kts`.

**Planned, not yet added** (each needs a ticket that names it): KSP, ExifInterface, LiteRT, MediaPipe tasks-vision. The Ultra HDR `Gainmap` API is in the platform (API 34+), so it needs no dependency.
