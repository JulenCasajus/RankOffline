# Multi-media architecture

This document defines the staged architecture for evolving RankOffline from its
published anime-only schema into one application with five isolated media
experiences. It is a design contract for the implementation blocks; it does not
describe features that are already available unless explicitly stated.

## Baseline and invariants

The public baseline is RankOffline v0.1.3 (`versionCode` 4, Room schema 10).
Its Anime data is mature and must remain compatible:

- database name: `anime_rank.db`;
- catalog identity and existing Anime IDs;
- individual and group ratings, notes, category scores, and profile IDs;
- downloaded covers and their database metadata;
- customized categories, profiles, rating mode, and Merge seasons setting;
- the existing offline catalog, popularity snapshot, filters, and grouping.

The first multi-media release therefore uses an additive transition. It does
not copy Anime into a new generic schema and does not rename or replace the
published Anime tables.

## Media types and identity

The domain has five stable, non-localized media values:

| Persisted value | User-facing experience | Provider |
| --- | --- | --- |
| `anime` | Anime | AnimeAPI with the existing enrichment sources |
| `manga` | Manga | MangaDex |
| `series` | Series | TMDb |
| `movie` | Movies | TMDb |
| `book` | Books | Open Library |

Localized labels are presentation only. Provider-derived IDs are the durable
identity and must not depend on a title, translation, or display order:

- `manga:mangadex:<uuid>`
- `series:tmdb:<seriesId>:season:<seasonNumber>`
- `movie:tmdb:<id>`
- `book:openlibrary:<workOlid>`
- optional future edition: `book:openlibrary:<editionOlid>`

Anime keeps its existing IDs. Generic group IDs are provider-derived as well,
for example `series:tmdb:<seriesId>` and
`movie:tmdb:collection:<collectionId>`.

## Layering and common model

The target flow remains local-first:

```text
Compose screens
    -> media-aware ViewModel state
    -> common media repository/domain contract
        -> Anime adapter -> existing AnimeRepository / Anime Room tables
        -> generic adapter -> new non-Anime Room tables
    -> provider-specific cover resolver when the user requests a cover
```

Search, Ranking, reusable list rows, rating editors, and group details should
consume a common domain projection (named `MediaItem` or an equivalent). It
must expose only shared presentation and interaction data: stable ID, media
type, provider identity, titles, dates/year, format, representative creator,
popularity, cover state/reference, and optional group/rating projections.
Provider-specific persistence models remain behind repository adapters.

This does **not** require a large rewrite of `Anime`, `AnimeRepository`, or the
current Compose file. The safe sequence is to introduce the common contract,
adapt Anime to it, and then implement the generic path. Provider-only metadata
may remain in provider entities or child tables rather than bloating the common
model.

## Room 10 to 11 transition

Schema 11 is an additive, non-destructive migration. These schema 10
tables remain the Anime source of truth without destructive changes:

- `anime`, `anime_genre`, `anime_studio`
- `rating`, `rating_score`
- `anime_group`, `anime_group_member`
- `group_rating`, `group_rating_score`
- `catalog_metadata`

Manga, Series, Movies, and Books use the deliberately separate transitional
`media_*` schema while Anime remains on its mature tables. The generic shape is:

- `media_item` — provider item, media type, searchable title/date/popularity,
  cover reference/state, and compact provider metadata;
- `media_genre` — indexed many-to-many filter values;
- `media_credit` — indexed creators, studios, networks, or production companies
  with a role/type;
- `media_alias` — alternative provider titles for future offline search;
- `media_group` and `media_group_member` — provider-backed grouping and ordered
  membership;
- `media_rating` and `media_rating_score` — canonical individual rating and
  category values;
- `media_group_rating` and `media_group_rating_score` — explicit group ratings;
- `media_catalog_metadata` — independent catalog version/hash/import metadata.

Foreign keys and indices must cover media type, title search, year, popularity,
genre/credit filtering, rating joins, and group membership. There will be no
`fallbackToDestructiveMigration`.

Migration tests must start from the exported, published schema 10 and prove
that every existing Anime table and representative user value survives 10 to
11 unchanged. A fresh schema 11 installation is tested separately.

Anime continues to use `AnimeRepository`. Manga, Series, Movies, and Books use
`GenericMediaRepository` and the generic `media_*` tables. Both paths adapt to
the common `MediaItem` domain model and share the existing score calculator;
this avoids rewriting published Anime persistence or duplicating rating rules.

New non-Anime catalogs use a gzip-compressed JSON Lines asset plus a JSON
manifest. Imports stream records in bounded batches, verify the SHA-256 of the
exact compressed bytes, and commit data and per-media catalog metadata in one
transaction. Catalog refreshes update provider-owned metadata and relations but
preserve downloaded-cover state, ratings, and group data. Items absent from a
new catalog are intentionally not pruned because deletion could cascade user
data. Anime retains its existing JSON catalog and importer unchanged.

Generic cover references are resolved through provider-specific, HTTPS-only
rules for TMDb, MangaDex, and Open Library. Downloads are explicit/on-demand,
size-bounded, image-validated, and atomically installed under app-private
`covers/<media-type>/` directories with hashed filenames. Catalog refreshes
never download covers. The existing Anime cover path and behavior remain
separate and unchanged.

## Rating semantics

Every individual target has one canonical final score. The existing rules are
shared across media types:

- simple and elaborate modes write the same final score;
- `0.0` is a valid rating; absence of a rating row means unrated;
- switching mode or editing/deleting a profile never recalculates saved scores;
- category values support N/A independently from zero;
- notes, personal-taste bonus, quality score, and selected profile follow the
  existing explicit-save behavior;
- individual and group ratings are independent;
- a derived group score is an in-memory arithmetic mean and is never persisted
  as the official group rating.

The existing score calculator should be reused through a small target/repository
adapter rather than copied per medium. Anime stays on its existing rating tables;
new media use the generic tables.

## Settings ownership and migration

One Preferences DataStore (`app_preferences`) remains appropriate.

Global settings:

- active media type (new, default `ANIME`);
- app language;
- launcher icon;
- show images;
- sticky score.

Per-media settings:

- simple/elaborate mode;
- categories and category colors/order;
- profiles and profile weights;
- merge/grouping preference when that catalog supports grouping;
- future media-specific personal-taste customization.

The schema 10 keys `elaborate_rating_enabled`, `rating_config`,
`rating_profiles`, and `merge_seasons_enabled` are existing Anime data. During
the settings migration they must seed the `ANIME` namespace exactly once and
must not be replaced by defaults. Temporarily mirroring legacy Anime keys is
acceptable for downgrade friendliness. Corrupt or unknown new values fall back
safely without rewriting valid legacy data.

The implemented snapshot is stored under `media_settings_v1`, keyed by the
stable lowercase media values. It is authoritative for the multi-media app;
Anime edits are also mirrored to the four legacy keys above for temporary
downgrade compatibility. The legacy inactive-category cleanup runs only while
migrating legacy Anime settings, never for the other media namespaces.

Other media types receive their documented defaults independently. Resetting
categories or profiles affects only the active media type. Global preferences
must never be duplicated into per-media namespaces.

## Navigation and state

The three main destinations remain Search, MyRanking, and Settings. A compact
media label/dropdown is part of each main header. Changing it updates the one
global active media type while retaining the current main destination.

Search query, sort, filters, list position, ranking sort, and ranking filters
are process-local state keyed by `MediaType`. Switching Anime to Books and back
must restore the Anime state without leaking Anime rows into Books. Only the
active media selection is persisted initially; scroll positions need not
survive process death.

An unavailable catalog renders a localized empty state. It never falls back to
another media type.

## Catalog assets and import

Each media type owns an independent asset and metadata record:

```text
anime_catalog      (existing format and importer remain unchanged)
manga_catalog
series_catalog
movie_catalog
book_catalog
```

New catalogs should use a streaming-friendly representation such as compressed
JSON Lines when measurements justify it. Imports are transactional, versioned
by content hash, and idempotent. A catalog update may reconcile catalog rows and
provider groups but must preserve ratings, notes, settings, downloaded covers,
and explicit group ratings.

Production catalogs are not generated during normal Gradle builds or CI. Tool
development begins with small deterministic fixtures. Full scope/limits are a
product decision after Block 19 measures asset size, import time, memory, and
search latency.

Movies and Series optionally bootstrap their local-first item and group assets
(`movie_catalog*` / `movie_groups*` and `series_catalog*` / `series_groups*`).
Missing bundles produce an unavailable state rather than falling back to Anime.
Movie `COLLECTION` and Series `SEASONS` groups use the generic repositories;
the Android app never calls the TMDb metadata API.

## Grouping policy

The UI and rating semantics are common; provider identity rules are not.

### Anime

Keep the current conservative AniList-relation family and normalized-format
grouping unchanged. Existing group IDs and explicit ratings must remain stable.

### Series

One item represents each numbered TMDb season; season zero is excluded. The
parent TMDb series is the group identity and supplies the preferred group title
and cover. A visible group requires at least two represented numbered seasons.
Episodes are not catalog or rating targets.

### Movies

Only canonical TMDb collection membership creates groups. The collection ID is
the group identity. Names, keywords, studios, and inferred shared universes are
not grouping evidence. A group requires at least two catalog members.

### Manga

A MangaDex manga entity is the individual work; chapters and volumes are not
synthesized as rating targets. A future initial Merge editions/variants mode may
use only relation types verified in the current official schema as true variants
(for example colored/monochrome or serialization variants). Franchise, universe,
prequel/sequel, side-story, and spin-off relations are excluded from this mode.

### Books

The initial item is an Open Library Work. Work-only catalogs do not expose a
fake Merge editions control. If Edition items are later deliberately imported,
their Work OLID provides the stable group identity. Book series/sagas are not
inferred from titles.

For every medium, Merge off shows individuals. Merge on replaces two or more
valid members with one group row. Active filters use independent `ANY(member)`
conditions combined with `AND` at group level, matching the existing Anime
semantics.

## Covers

The current Anime cover pipeline already provides the required safety baseline:
explicit download, HTTPS, private storage, cancellation, image validation,
atomic installation, and no network work caused by list scrolling.

A common cover provider abstraction should add URL/reference resolution without
copying the downloader:

- Anime: retain current stored URL and AniList/Jikan fallback behavior;
- TMDb: `poster_path` resolved to an HTTPS `w500` URL;
- MangaDex: manga UUID plus cover filename, normally using the 512px thumbnail;
- Open Library: Cover ID with the Large endpoint and `default=false`.

Storage is namespaced (`covers/anime`, `covers/manga`, `covers/series`,
`covers/movie`, `covers/book`) and filename generation must not accept provider
path separators. Existing Anime paths remain valid.

## Localization and privacy

The existing nine Android resource locales remain authoritative. Persisted
media values, provider IDs, category IDs, and group IDs are never translated.
All added UI labels use resources and the existing completeness tests are
extended.

The local-first privacy model is unchanged: no accounts, analytics, telemetry,
crash reporting, or upload of ratings, notes, categories, profiles, or personal
taste. Runtime network traffic is limited to explicit public metadata/cover
operations; bulk catalog generation is an offline maintainer tool.

## Deferred decisions

The following are deliberately unresolved until their assigned blocks provide
evidence:

- production entry limits for Movies, Series, Manga, and Books;
- catalog serialization/compression choice and resulting APK/Room footprint;
- whether complete MangaDex enumeration is demonstrably supported by the then
  current official API;
- whether MangaDex variant relations yield useful groups in the selected data;
- whether Book Editions are valuable enough to import and expose;
- popularity formulas for Manga and Books (no cross-provider normalization);
- whether generic Top-X cover preload is worth its complexity;
- approved TMDb logo asset acquisition and final attribution presentation;
- authorization for RankOffline's continued existing AniList usage.

Cross-media rankings, inferred universes/sagas, readers, progress tracking,
accounts, sync, social features, and recommendations remain outside this plan.
