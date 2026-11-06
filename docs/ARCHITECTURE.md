# Architecture

## Boundaries

`app/src/` is organized physically by domain while
temporarily retaining the single package `org.rankoffline.app`:

- `app/`: process bootstrap, ViewModel and shared UI.
- `packs/`: manifest parsing, installation, storage, WorkManager and pack UI.
- `catalog/`: format validation, Room DAOs/repositories and catalog UI.
- `ratings/`: generic scoring configuration, persistence and editor UI.
- `covers/`: validated URL policy, physical cover storage and rendering.
- `share/`: Top 7/Top 10 preparation and bitmap rendering.
- `backup/`: personal JSON schema 2 export/import.
- `preferences/`, `theme/`, `settings/`, `onboarding/`, `legal/`, `data/`.

The application has a single launcher experience in debug and release variants.

Room stores catalog and personal rating rows with composite `(packId, id)`
identity. `PackId` is a positive `Long`; zero exists only as virtual
RankOfflineAll scope. Merge changes representation, not identity.

## Runtime initialization

`RankOfflineApplication` registers the process-wide lazy `RankOfflineServices`
graph before a persisted worker can resolve it. The debug variant loads a
configurable HTTPS package index from GitHub; the release variant currently has
no remote source. UI and workers share the same `PackManager`.

Room writes, pack installation, recovery and optional initial cover downloads
are coordinated through repositories and WorkManager. The app does not execute
pack code; packages contain validated data only.

## Catalog session

The ViewModel retains pages actually visited for up to five physical packs in
access order. Search and MyRanking have separate page and scroll states. A
single tab retains at most 1,200 rows per pack; if the user pages farther, the
current screen remains visible, but returning to it may require a fresh query.
RankOfflineAll is loaded only on demand and is excluded from background
preloading. On startup, only the active pack's first page is required for the
visible screen. After that page appears, at most four other READY recent packs
are preloaded sequentially with 200 Search and 200 ranking rows each. This
never downloads images or triggers imports. Only the five recent pack IDs are
stored in DataStore; pages, scroll positions and decoded thumbnails stay in
process memory. A visible uncached page or an incomplete installation pauses
background preloading. Changing the active pack or recent-pack order replans
the remaining work; completed pages remain cached, while an interrupted Room
query starts again when that pack is still eligible. Switching between Search,
MyRanking and Settings does not itself discard completed preload work.

## Generated code

Room uses KSP. `MediaDao`, `MediaCatalogDao`, `MediaGroupDao` and
`PersonalDataDao` inherit SQL methods from focused interfaces; Room schema JSON
is emitted to `app/schemas/`. `BuildConfig` also provides variant-specific
remote source configuration.

## Invariants

- Physical packs: `files/packs/pack-<id>/`; personal files: `files/local/<id>/`.
- No `pack-0` directory.
- All results require every participating installed pack to be READY.
- Catalog/ranking are paged; cover ownership and fallback stay centralized.
- Personal backups never contain catalogs, covers or package archives.
- No v0.2.1 migration is attempted.
