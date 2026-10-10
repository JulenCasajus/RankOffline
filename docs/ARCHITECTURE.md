# RankOffline architecture

RankOffline is a single-module, local-first Android app. Compose renders state exposed by `RankOfflineViewModel`; repositories perform persistence, file, and network work away from the main thread.

## Data and preferences

- Room owns `anime_rank.db` (schema 7): `anime`, `rating`, `rating_score`, and `catalog_metadata`. Migrations are explicit and non-destructive.
- `rating.final_score` is the sole canonical score. Simple and elaborate saves overwrite that same field; detailed category rows are retained.
- DataStore `app_preferences` owns display, rating-mode/configuration, launcher-icon, and language preferences.
- The versioned `anime_catalog.json` is transactionally imported only when `anime_catalog.version` changes. Its Top-5000 popularity data lives on the same anime rows. User ratings and cover paths survive catalog upserts.

## Covers, popularity, and network

- Covers are resolved locally first and stored under app-private `filesDir/covers`; valid downloads are installed atomically.
- AniList supplies popularity and primary cover metadata. Jikan is a cover fallback by MAL ID. Requests use HTTPS, finite timeouts/retries, and cancellation-aware coroutines.
- Top-X refresh is always explicit. Scrolling does not trigger network access, and the bundled Top-5000 ranking works offline on first launch.

## Localization and titles

- Android string/plural resources provide English, Spanish, German, French, Russian, Japanese, Simplified Chinese, Basque, and Italian UI. `SupportedLanguage` centralizes tags, and AndroidX per-app locales apply the DataStore selection.
- English is the default. Resource parity and placeholders are tested across every locale.
- Anime titles are not translated. The bundled source currently contains one canonical title per row; every locale uses that title. No language change causes network requests, and no inferred or machine-translated titles are accepted.

## Privacy and security

- Ratings, notes, categories, preferences, database rows, and downloaded covers remain on-device and are never included in API requests.
- There is no analytics, telemetry, remote crash reporting, or account system.
- INTERNET is the only permission. Cleartext traffic and Android Auto Backup are disabled. Launcher aliases are the only intentionally exported entry points.
