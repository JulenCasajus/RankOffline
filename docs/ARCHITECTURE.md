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

All five official media types use the shared catalog, alias, filter, search, pagination, group, rating, ranking, and cover infrastructure. Provider-specific work is primarily release-time normalization and restricted cover URL
resolution. Remote title discovery has been removed from the Android application. The legacy Anime repositories/tables remain only as an upgrade bridge and test surface for published 0.1.3 data; normal 0.2 catalog and rating writes use `media_*`.

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
         -> import/verify precomputed groups -> catalog READY
         -> optional initial Top-100 cover acquisition (catalog remains usable)
```

Install and recovery work is owned by uniquely named WorkManager jobs, rather than an Activity or
ViewModel coroutine. The worker and UI share one process-wide service graph and the same per-PackId
locks. Recoverable transfer/runtime failures use bounded exponential WorkManager retry; invalid
archives, hashes and incompatible schemas fail permanently. On process restart, active WorkManager
jobs and verified files reconstruct UI state; unchanged catalog and group
hashes, counts and payload sizes are verified through a lightweight startup path and become READY
without enqueuing a recovery job or reopening the large compressed catalogs. Missing, changed or
interrupted state takes the full recovery/`NoChange` path. Network constraints apply to archive/cover transfer without
preventing an installed catalog from recovering offline. Import failures produce ERROR/retry state;
partial packs never activate. Operations for different packs may proceed independently, while
operations for the same PackId cannot race. Android does not run WorkManager while the user keeps
the app force-stopped; eligible work resumes only after Android allows the app to run again.

There are two removal paths. **Uninstall, keep personal data** removes reference catalog/group data,
files and covers while retaining PackId-owned ratings, detailed scores, notes/status and preferences.
Reinstalling the same compatible identities reconnects that data. **Delete pack and data** also
purges those personal records and pack settings. Global language, palette, onboarding, and app-icon
settings remain.

## Storage accounting

Pack files are measured from their actual app-private paths: normalized catalog/group/metadata
payloads, retained verified archive and removable covers. Personal Room rows and DataStore settings
are reported separately as a **logical estimate** because SQLite pages are shared and cannot be
truthfully assigned as exact physical bytes to one PackId. Flows invalidate the estimate after
rating/settings changes; file totals refresh after lifecycle and cover operations.

## Merge and search

Relationships are computed by release tooling, never by the phone. Packs contain complete group records with provider group identity, representative ID, ordered members, member count, and popularity summary. Runtime Merge only switches between indexed, SQLite-paginated queries over imported memberships. It performs no title inference, provider request, or relationship write.

Offline search uses the same repository for every media type, including aliases and
manifest-declared filters/subtitle fields. Books have a conservative precomputed
Work-level series-group artifact; coverage is intentionally limited rather than
inferring unverifiable relationships on the phone.

`RankOfflineAll` is a virtual viewing scope available only with at least two READY packs. It owns no
catalog or rating rows. Popularity pages interleave each source pack's ranks deterministically;
alphabetic Search uses bounded per-pack cursors and a deterministic k-way merge instead of loading
ever-growing prefixes. Rated Ranking combines source results while retaining each row's source
identity. Opening or rating a result uses the source pack's settings. The virtual scope has its own
display and catalog-sort preferences and disappears immediately when fewer than two packs remain.
Category-score ordering is offered only for active category definitions shared by every participating
pack; unrelated same-looking IDs are not silently compared.

## Covers

Item and group covers use explicit storage identities and separate Room columns. A common UI contract observes the local path and download state; group covers read `media_group.local_cover_path`. Files are decoded off the main thread and cached in memory.

Ranking sharing is separate from JSON backup sharing. It reads only the current personal ranking,
titles, scores and local cover paths, then uses one deterministic 9:16 renderer for both preview and
the 1080×1920 PNG. Temporary PNGs remain in an app-private cache and are exposed only through a
short-lived FileProvider read grant. Optional integrity-declared pack artwork supplies a thematic
background; no network is needed and notes/settings are never rendered.

Data-only pack manifests may declare constrained HTTPS cover sources, stable identities,
and allowed hosts. Official legacy providers remain restricted fallback adapters. Anime
can fall back to AniList/Jikan using its stable ID; Open Library candidates are tried
in the stored order cover ID, ISBN-13, ISBN-10, edition OLID, and work OLID. Existing local covers remain usable offline. Normal browsing/startup never requires a provider
call; optional Top-100 cover retrieval during new pack installation and user-requested Top-N
operations may contact declared HTTPS hosts. No remote title search is performed.

## Settings and themes

DataStore owns global settings and PackId-namespaced media settings. Existing singular `MediaType` keys are still accepted during compatibility reads. Theme palettes persist only four source colors—background, surface, primary, and secondary. Material semantic colors are derived once per effective palette using contrast against the actual container. Old custom-theme JSON fields such as `mode` and `tertiary` are ignored safely.

## Personal-data backup

Settings → Packages uses Android's Storage Access Framework to export/import a bounded, versioned
JSON backup and Android's Sharesheet to share a private-cache `content://` URI.
It contains PackId-scoped ratings and detailed scores, notes/status/profile references, media
settings, catalog sort preferences, the virtual All display settings, and custom/built-in palette
edits. It excludes catalogs, pack archives, credentials and cover binaries. Import is validated
before mutation; Room restoration is transactional, supports newer-wins merge or explicit replace,
and detached personal rows remain valid while their pack is uninstalled. A durable restore journal
coordinates Room and DataStore crash recovery without claiming cross-store transactional atomicity.
Import, export and share are serialized as one explicitly visible personal-data operation. External
service synchronization remains disabled; pack-scoped identity, provider-ID mapping, rating-scale
conversion and dry-run conflict primitives exist only as a tested boundary for a future authorized
connector.
See [PERSONAL_BACKUP_FORMAT.md](PERSONAL_BACKUP_FORMAT.md) for the schema and conflict rules.

## Privacy and security

Ratings, notes, profiles, database rows, preferences, packs, and downloaded covers remain in app-private storage. RankOffline has no account, telemetry, analytics, or remote rating storage. Packs are data only: manifests, paths, sizes, and SHA-256 are validated, traversal is rejected, and executable payload types are forbidden.
