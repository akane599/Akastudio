# AkaStudio — Codebase Map
*Last Updated: 2026-10-21*

> **Greenfield seed.** Only the android-kit scaffold (`:app`, placeholder Home screen) exists so far. Most of this map describes the *intended* design from the product spec and will grow as modules land.

AkaStudio is an on-device photo curator for Android. It takes photos (PhotoPicker) or a `.zip` and produces an editorial 9:16 Story: a hero shot or a 2–4 photo collage. The pipeline covers OKLab cross-image color harmonization, 16-bit GPU grading and lossless/anti-compression export. Kotlin + Compose, Clean Architecture + MVI, minSdk 31, compileSdk 37.

| Doc | What's in it |
|---|---|
| [architecture.md](architecture.md) | Intended module layers and the ingest → curate → grade → compose → export flow |
| [tech-landscape.md](tech-landscape.md) | Toolchain versions, source-of-truth build files, planned dependencies |
| [directory-structure.md](directory-structure.md) | Current tree plus planned module layout |
| [onboarding.md](onboarding.md) | Build/test/screenshot commands and where to start |

## How to use / maintain this map
- Read INDEX first, then open only the doc you need.
- After code changes, assess the map and run `update-codebase-map` when structure, modules, entry points or flows changed. Keep paths real: verify before citing.
