# AkaStudio — Codebase Map
*Last Updated: 2026-10-21*

AkaStudio is an on-device Android photo curator. It takes photos (PhotoPicker) or a `.zip` and produces an editorial 9:16 Story: a hero shot or a 2–4 photo collage. Pipeline: ingest, curate (Q/Ψ decision), prepare, GL grade and compose (OKLab harmonization, 16-bit), export (PNG16 / WebP lossless / Social JPEG / Ultra HDR). Kotlin + Compose, Clean Architecture + MVI, no DI framework. minSdk 31, compileSdk 37. Seven Gradle modules: `:app`, five `:core:*`, `:feature:studio`.

| Doc | What's in it |
|---|---|
| [architecture.md](architecture.md) | Module graph and the end-to-end flow |
| [modules.md](modules.md) | Per module: purpose, public `api/` seams, key types, dependencies |
| [entry-points.md](entry-points.md) | MainActivity → StudioRoute → StudioViewModel, StudioContainer wiring, factories |
| [patterns.md](patterns.md) | Conventions: frozen `api/`, pure logic + thin adapters, MVI, NIMA confinement, GPU parity |
| [directory-structure.md](directory-structure.md) | Annotated tree per module |
| [tech-landscape.md](tech-landscape.md) | Toolchain, dependencies, ML models, GLES3 |
| [onboarding.md](onboarding.md) | Build/test commands per module, gradle-check helper, no-KVM notes |

Decision log: see `PROGRESS.md` at the repo root (not duplicated here).

## How to use / maintain this map
- Read INDEX first, then open only the doc you need.
- After code changes, assess the map and run `update-codebase-map` when structure, modules, entry points or flows changed. Keep paths real: verify before citing.
