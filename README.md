# RankOffline v0.2.2

RankOffline is an offline-first, universal catalog and rating engine for
Android. The APK does not define Anime, Manga, Movies, Series, Books, providers,
or official packs. A validated `.pack` supplies catalog semantics, presentation,
cover sources, and attribution.

This repository contains the reconstructed Android Studio project and the 194
Kotlin files produced by refactor phases 1–6. It does not reuse the v0.2.1
Kotlin/Java engine.

## Current status

- Android module, manifest, resources, Room/KSP, Compose, WorkManager and
  release configuration are present.
- Manifest schema 2, catalog/group format 2 and personal backup schema 2 are
  represented by the current engine.
- Reproducible schema-2 pack generation and independent validation tools are
  available under `scripts/`; generated artifacts remain outside Git.
- No remote pack registry, pack-update service, legacy migration, analytics or
  advertising is included.
- Full Android Studio compilation, emulator validation, notification-permission
  UX and end-to-end pack fixtures remain pending.

## Open in Android Studio

1. Install JDK 21 and Android Studio with the Android 17 / API 37 SDK. Keep
   Build Tools 36.0.0 available (AGP 9.5's tested default); newer 37.x tools may
   also be installed by the SDK Manager.
2. Open this repository root, not the `app/` directory.
3. Let Android Studio use the Gradle wrapper and JDK 21.
4. Create `local.properties` through the IDE if required.
5. Sync, then run `./scripts/check-kotlin-limits.sh` and `./gradlew lint test
   assembleDebug` before launching an API 26 and an API 37 emulator.

See [DEVELOPMENT.md](docs/DEVELOPMENT.md), [ARCHITECTURE.md](docs/ARCHITECTURE.md),
[PACK_MANIFEST_SPEC.md](docs/PACK_MANIFEST_SPEC.md), and
[PACK_GENERATION.md](docs/PACK_GENERATION.md). The complete checked-in layout is
recorded in [PROJECT_TREE.md](docs/PROJECT_TREE.md).

## Licence

Application source is GPL-3.0-only; see [LICENSE](LICENSE). RankOffline branding
and imported visual assets are handled separately in
[TRADEMARKS.md](docs/TRADEMARKS.md) and
[legal/ASSET_PROVENANCE.md](docs/ASSET_PROVENANCE.md).
