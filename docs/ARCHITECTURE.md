# RankOffline architecture

RankOffline is a single-module, local-first Android app. Jetpack Compose renders state exposed by `RankOfflineViewModel`; repositories handle persistence, files, catalog work, and network operations away from the main thread.

## Persistence

### Room

Room owns `anime_rank.db`, currently schema **10**.

Main tables:

- `anime` — bundled catalog data, AniList metadata, popularity, and local cover metadata.
- `anime_genre` / `anime_studio` — indexed metadata used by Room filters.
- `rating` — one row per rated anime, including the canonical `final_score`, shared notes/status, and nullable `profile_id` for the last elaborate profile used.
- `rating_score` — detailed category values keyed by anime ID and stable category ID.
- `anime_group` / `anime_group_member` — precomputed format-normalized groups derived from conservative AniList relations.
- `group_rating` / `group_rating_score` — explicit group ratings, kept independent from member ratings.
- `catalog_metadata` — bundled catalog version/integrity metadata.

`rating.final_score` is the sole saved score for an individual anime. Simple
and elaborate saves both write this field. Group rows use the independent
`group_rating.final_score` when explicitly rated, otherwise a non-persisted
derived average of their rated members.

Migrations are explicit and non-destructive. Migration **7 → 8** adds the
nullable rating profile reference; **8 → 9** adds searchable catalog metadata;
and **9 → 10** adds dates, precomputed groups, and independent group ratings.
No destructive fallback is configured.

### DataStore

The `app_preferences` DataStore owns:

- display preferences;
- simple/elaborate rating mode;
- editable rating categories;
- rating profiles and their category weights;
- launcher icon selection;
- app language;
- the optional Merge seasons view.

Category/profile relationships use stable category IDs, never localized or user-editable names.

For compatibility, existing user category configuration is preserved. When profile support is initialized for an existing installation, the previous weighting configuration becomes the initial General profile rather than overwriting user choices.

## Rating flow

### Simple mode

Compose editor → ViewModel → repository → `rating.final_score`.

The simple 0–10 score is saved directly. Existing detailed category values and the previously selected elaborate profile remain intact.

### Elaborate mode

Compose editor selects a profile and category scores → ViewModel → repository → score calculator → Room.

The calculator:

1. excludes N/A categories;
2. reads weights from the selected profile;
3. normalizes applicable positive weights;
4. calculates weighted quality;
5. applies the personal-taste bonus;
6. caps the result at 10.

Saving updates the same canonical `final_score`, stores detailed `rating_score` rows, and records the selected `profile_id`.

Changing a profile definition never triggers background recalculation of existing anime.

## Categories and profiles

Categories define **what** is evaluated: stable ID, name, description, color, and order.

Profiles define **how much** each category matters by mapping category IDs to weights.

Important invariants:

- renaming/recoloring a category does not break profiles;
- new categories enter existing profiles at 0%;
- deleting a category removes its detailed score rows and profile references while preserving existing final scores and notes;
- at least one usable profile always exists;
- deleting a profile never rewrites existing final scores;
- category reset and profile reset are separate operations.

## Catalog

The versioned `anime_catalog.json` is imported transactionally only when
`anime_catalog.version` changes. Metadata junction rows and group membership
are reconciled inside the same transaction.

The bundled catalog currently contains 40,742 unique entries and a complete local Top 5000 popularity snapshot. Catalog upserts preserve user ratings, notes, settings, profile/category configuration, and downloaded cover metadata.

Catalog generation is an explicit manual operation through
`tools/fetch_catalog.py`; it is not part of normal builds or CI. AnimeAPI
remains the master catalog. AniList enrichment queries already-known IDs in
batches, and group identity is derived offline from explicit relations rather
than title matching.

## Optional grouping

Merge seasons is off by default. When enabled, persisted groups replace their
members in search and ranking only when at least two entries share a
conservative AniList family and normalized format bucket (Series, Movies,
Special, or Music).

An explicit group rating is independent from all member ratings. Without an
explicit group rating, the UI derives a non-persisted average from rated
members. Changing either side never silently rewrites the other.

## Covers, popularity, and network

Covers resolve locally first and are stored under app-private `filesDir/covers`. Downloads are validated and installed atomically.

AniList supplies popularity and primary cover metadata. Jikan is a cover fallback by MAL ID.

Top-X refreshes are explicit user actions. Scrolling lists does not trigger downloads, and the bundled popularity snapshot allows offline popularity sorting on first launch.

Requests use HTTPS, finite timeouts/retries, and cancellation-aware coroutines.

## Localization

Android resources provide:

- English
- Spanish
- German
- French
- Russian
- Japanese
- Simplified Chinese
- Basque
- Italian

English is the default. AndroidX per-app locales apply the persisted language choice.

Anime titles are canonical catalog data and are not machine-translated when the UI language changes.

Default category/profile labels may be localized when defaults are created or reset, but user-edited persisted names are not silently renamed on language changes.

## Privacy and security

Ratings, notes, categories, profiles, database rows, preferences, and downloaded covers remain on-device.

There is no analytics, telemetry, remote crash reporting, or account system.

The app requests only INTERNET. Cleartext traffic and Android Auto Backup are disabled. Launcher aliases are the only intentionally exported entry points.

Network requests use public anime IDs and cover/popularity metadata only; personal rating contents are never serialized into those requests.
