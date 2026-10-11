# RankOffline architecture

RankOffline is a single-module, local-first Android app. Jetpack Compose renders state exposed by `RankOfflineViewModel`; repositories handle persistence, files, catalog work, and network operations away from the main thread.

## Persistence

### Room

Room owns `anime_rank.db`, currently schema **8**.

Main tables:

- `anime` — bundled catalog data, popularity metadata, and local cover metadata.
- `rating` — one row per rated anime, including the canonical `final_score`, shared notes/status, and nullable `profile_id` for the last elaborate profile used.
- `rating_score` — detailed category values keyed by anime ID and stable category ID.
- `catalog_metadata` — bundled catalog version/integrity metadata.

`rating.final_score` is the sole score used by ranking. Simple and elaborate saves both write this field.

Migrations are explicit and non-destructive. Migration **7 → 8** adds only nullable `profile_id`, preserving existing ratings, notes, and detailed category scores. No destructive fallback is configured.

### DataStore

The `app_preferences` DataStore owns:

- display preferences;
- simple/elaborate rating mode;
- editable rating categories;
- rating profiles and their category weights;
- launcher icon selection;
- app language.

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

The versioned `anime_catalog.json` is imported transactionally only when `anime_catalog.version` changes.

The bundled catalog currently contains 40,742 unique entries and a complete local Top 5000 popularity snapshot. Catalog upserts preserve user ratings, notes, settings, profile/category configuration, and downloaded cover metadata.

Catalog generation is an explicit manual operation through `tools/fetch_catalog.py`; it is not part of normal builds or CI.

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
