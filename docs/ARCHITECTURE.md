# Architecture

> **Version note:** `main` currently represents the v0.1.3 stable code. The v0.2.0 architecture below documents the ongoing local `feature/multi-media` work and is **not yet a released product**.

RankOffline is a local-first Android app: Kotlin + Jetpack Compose for UI, ViewModel/StateFlow for coordination, Room for structured data, DataStore for preferences, and app-private files for installed packs and downloaded covers.

## Separation of concerns

```text
UI (Compose) / ViewModel
  ├── App settings and custom palettes (DataStore)
  ├── User-owned ratings, scores, notes and profiles (Room)
  ├── Pack lifecycle coordinator / readiness
  │     ├── downloaded .pack → verify SHA/size → stage → promote
  │     ├── catalog import → precomputed group import → finalization
  │     └── READY only when runtime data is usable
  └── Catalog / grouped queries / cover repositories (Room + private files)
```

Installed data-only payloads are stored under `files/packs/<pack_id>/` with `manifest.json`, `.complete`, and `catalog/`, `groups/`, `metadata/`, and optionally `covers/`. Archive bytes and each extracted file's SHA-256 are checked. `READY` is distinct from “files exist”; recovery, replacement and purge are serialized for each pack.

## Official pack IDs and catalogs

| Pack ID | Media type | Catalog adapter | Merge |
|---|---|---|---|
| `animes` | `anime` | Anime-specific legacy Room/import format | Seasons / related Anime graph |
| `mangas` | `manga` | Generic media items | `VARIANTS` |
| `movies` | `movie` | Generic media items | `COLLECTION` (explicit TMDB collections) |
| `series` | `series` | Generic media items | `SEASONS` (explicit TMDB show IDs) |
| `books` | `book` | Generic media items | No Merge graph |

The plural **pack ID** is stable for installed files; the singular **media type** is retained for persisted record compatibility.

### Why Anime has a separate group importer

`AnimeGroupCatalogImporter` is **not a second pack installer**. There is one common pack archive/verification/lifecycle layer, but currently **two database adapters**:

- Anime: older `anime` / `anime_group` / `anime_group_member` schema; group records carry `rootAnimeId`, type and ordered Anime IDs. `BundledAnimeGroupCatalogManager` passes that graph to `AnimeGroupCatalogImporter`.
- Manga/Movies/Series: generic `media_item` / `media_group` / `media_group_member` schema; group records carry representative IDs, popularity and explicit members. `GenericMediaGroupCatalogImporter` handles these.

Both obtain relationships from **precomputed pack data**. Neither should discover relationships when Merge is tapped. The separation exists for backward-compatible Anime ratings, existing IDs and schema, not because each provider needs a separate install system. Unifying database adapters later is an optional, migration-sensitive project, not a prerequisite for fast Merge.

## Merge pipeline

```text
On the pack producer's machine:
  identify explicit relationships → precompute IDs/members/representative
  → write group records + manifest → build pack/index

On Android, once:
  download → verify → install → import catalog
  → “Preparing merge” (import/verify relationships) → finalize → READY

In everyday use:
  Merge OFF → individual items
  Merge ON  → indexed, paginated SQL over groups + ungrouped items
```

Only user-dependent score aggregates are computed at runtime. Group memberships and immutable popularity metadata come from the pack, not title matching on device.

## User data, covers and deletion

Ratings, profile-specific scores, notes and progress are separate from reference catalog records. Catalog **updates/recovery** must preserve them, as well as downloaded cover paths. Cover paths distinguish ordinary items from Movie/Series groups.

**Deleting a pack through the explicit destructive “delete pack” action is different from updating it:** the v0.2.0 product design deliberately purges that pack's reference data, downloaded covers, ratings, notes, profiles/settings and other pack-owned state, after confirmation. Global theme, language and general app preferences remain. Never describe this deletion as preserving ratings.

A plain temporary import error must **never** trigger a user-data purge.

## Storage and compatibility

- Room schema: **13** in the v0.2.0 development work; explicit migrations.
- Raw pack `schemaVersion`: **1**.
- Archive format: ZIP-based `.pack`, data-only, no executable plugins.
- Local packs are fully useful offline; network access is only for requested downloads/searches/covers.
- External-pack manifest parsing is groundwork; **end-user third-party import/UI adapters are not implemented**.

See [PACK_FORMAT.md](PACK_FORMAT.md) and [CUSTOM_PACKS.md](CUSTOM_PACKS.md).
