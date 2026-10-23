# RankOffline architecture

RankOffline 0.2 is a local-first Android application made of a generic Core APK and independently installed, data-only packs. The five official `PackId` values are `animes`, `mangas`, `movies`, `series`, and `books`.

## Runtime layers

```text
Compose UI
  -> RankOfflineViewModel and domain coordinators
  -> GenericMediaRepository / GenericMediaGroupRepository
  -> shared Room media_* tables
  -> verified files/packs/<pack_id>/ data
```

All five official media types use the shared catalog, alias, filter, search, pagination, group, rating, ranking, and cover infrastructure. Provider-specific code is limited to release-time normalization, remote discovery, and cover URL resolution. The legacy Anime repositories/tables remain only as an upgrade bridge and test surface for published 0.1.3 data; normal 0.2 catalog and rating writes use `media_*`.

## Room and user data

Room owns `anime_rank.db`, currently schema **14**. The active shared tables are:

- `media_item`, `media_genre`, `media_credit`, and `media_alias` for catalog/reference data;
- `media_group` and `media_group_member` for precomputed provider relationships;
- `media_rating` / `media_rating_score` for item-owned personal state;
- `media_group_rating` / `media_group_rating_score` for explicit group-owned personal state;
- `media_catalog_metadata` for durable import version/hash/count verification.

Personal rating identity is `(pack_id, canonical_id)`. Item and group identities stay distinct. A saved simple or elaborate rating writes one canonical final score plus notes, status, profile, bonus, and any applicable category values. Room is the durable source of truth; UI revisions only trigger queries after successful persistence.

Schema migrations are explicit and non-destructive. Migration 13 → 14 adds PackId ownership and moves stable Anime catalog, group, ratings, detailed scores, notes, status, profiles, and cover metadata into the common model. Conflicting legacy/common Anime ratings select one authoritative complete record by `updated_at`; ties retain the common record, and detailed scores follow the same winner. No destructive fallback is configured.

## Pack lifecycle

A pack is not READY merely because files exist. Per-pack lifecycle serialization coordinates startup recovery, install, import, and purge:

```text
download -> verify -> atomic file install -> import catalog
         -> import/verify precomputed groups -> finalize runtime -> READY
```

On process restart, unchanged catalog and group hashes take the `NoChange` path. Import failures produce ERROR/retry state; partial packs never activate. Operations for different packs may proceed independently, while operations for the same PackId cannot race.

Pack deletion is an explicitly destructive product action: it purges that pack's reference data, ratings, detailed scores, notes/status, pack settings, covers, cache, and files. Global language, palette, onboarding, and app-icon settings remain.

## Merge and search

Relationships are computed by release tooling, never by the phone. Packs contain complete group records with provider group identity, representative ID, ordered members, member count, and popularity summary. Runtime Merge only switches between indexed, SQLite-paginated queries over imported memberships. It performs no title inference, provider request, or relationship write.

Offline search uses the same repository for every media type, including Anime aliases and manifest-declared filters/subtitle fields. Books intentionally have no Merge capability.

## Covers

Item and group covers use explicit storage identities and separate Room columns. A common UI contract observes the local path and download state; group covers read `media_group.local_cover_path`. Files are decoded off the main thread and cached in memory.

Provider adapters resolve URLs only for explicit cover operations. Anime can fall back to AniList/Jikan using its stable ID; Open Library candidates are tried in the stored order cover ID, ISBN-13, ISBN-10, edition OLID, and work OLID. Existing local covers remain usable offline and normal browsing/startup never requires a provider call.

## Settings and themes

DataStore owns global settings and PackId-namespaced media settings. Existing singular `MediaType` keys are still accepted during compatibility reads. Theme palettes persist only four source colors—background, surface, primary, and secondary. Material semantic colors are derived once per effective palette using contrast against the actual container. Old custom-theme JSON fields such as `mode` and `tertiary` are ignored safely.

## Privacy and security

Ratings, notes, profiles, database rows, preferences, packs, and downloaded covers remain in app-private storage. RankOffline has no account, telemetry, analytics, or remote rating storage. Packs are data only: manifests, paths, sizes, and SHA-256 are validated, traversal is rejected, and executable payload types are forbidden.
