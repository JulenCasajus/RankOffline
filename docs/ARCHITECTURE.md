# Architecture

## Boundaries

`app/src/java/org/rankoffline/app/` is organized physically by domain while
temporarily retaining the single package `org.rankoffline.app`:

- `app/`: process bootstrap, ViewModel and shared UI.
- `packs/`: manifest parsing, installation, storage, WorkManager and pack UI.
- `catalog/`: format validation, Room DAOs/repositories and catalog UI.
- `ratings/`: generic scoring configuration, persistence and editor UI.
- `covers/`: validated URL policy, physical cover storage and rendering.
- `share/`: Top 7/Top 10 preparation and bitmap rendering.
- `backup/`: personal JSON schema 2 export/import.
- `preferences/`, `theme/`, `settings/`, `onboarding/`, `legal/`, `data/`.

All 193 destinations in `FASE_5_TREE_MAPPING.tsv` are present. The 194th file,
`ratings/data/RatingEntityMappers.kt`, was added during phase 6 and is retained
in the corresponding rating-data domain. The 195th file,
`app/ui/FloatingNavigation.kt`, centralizes the later floating-navigation UI.
The 196th Kotlin file is the debug-only SAF pack installer under
`app/src/debug/`; it is not part of release builds or the universal engine.

Room stores catalog and personal rating rows with composite `(packId, id)`
identity. `PackId` is a positive `Long`; zero exists only as virtual
RankOfflineAll scope. Merge changes representation, not identity.

## Runtime initialization

`RankOfflineApplication` registers the process-wide lazy `RankOfflineServices`
graph before a persisted worker can resolve it. The current trusted-source
loader intentionally returns an empty registry: remote discovery is not a
v0.2.2 feature. UI and workers share the same `PackManager`.

Room writes, pack installation, recovery and optional initial cover downloads
are coordinated through repositories and WorkManager. The app does not execute
pack code; packages contain validated data only.

## Generated code

Room uses KSP. `MediaDao`, `MediaCatalogDao`, `MediaGroupDao` and
`PersonalDataDao` inherit SQL methods from focused interfaces; Room schema JSON
is emitted to `app/schemas/`. `BuildConfig` is enabled only for application
version reporting.

## Invariants

- Physical packs: `files/packs/pack-<id>/`; personal files: `files/local/<id>/`.
- No `pack-0` directory.
- All results require every participating installed pack to be READY.
- Catalog/ranking are paged; cover ownership and fallback stay centralized.
- Personal backups never contain catalogs, covers or package archives.
- No v0.2.1 migration is attempted.
