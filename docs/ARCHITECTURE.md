# Architecture

RankOffline is a single-module, local-first Android application built with Kotlin and Jetpack Compose.

## Main layers

- **Compose UI** renders application state and user actions.
- **ViewModel / state layer** coordinates screens and user intent.
- **Repositories** handle persistence, catalogs, files, and network operations.
- **Room** stores structured application and user data.
- **DataStore** stores lightweight preferences and configuration.
- **App-private files** store downloaded catalogs and covers.

## Local-first model

The app is designed so personal data remains on-device.

User-owned data includes ratings, notes, ranking state, preferences, and rating configuration.

Catalog data is treated separately from user-owned data. The v0.2.0 work is moving catalogs toward independently installable/removable packs so catalog storage can be reclaimed without deleting personal ratings.

## Catalogs

Stable v0.1.3 ships an anime-focused catalog.

The v0.2.0 development branch expands the model to:

- Anime
- Manga
- Movies
- Series
- Books

Catalog imports are validated and installed atomically. A catalog must never become active in a partially written or invalid state.

Downloaded category packs live in app-private storage and are validated before activation.

## Scoring

Simple and elaborate rating modes ultimately produce one canonical saved score per item.

The elaborate system supports:

- editable rating categories;
- reusable weighting profiles;
- N/A categories;
- a separate personal-taste bonus.

See [SCORING.md](SCORING.md).

## Privacy and networking

RankOffline has no analytics, telemetry, remote crash reporting, or account system.

Network access is limited to features that explicitly need remote catalog, cover, or update data. Personal rating contents are not uploaded to catalog providers.

## Migrations

Room migrations are explicit and non-destructive.

Changes to catalog storage must preserve user-owned data. A catalog update or removal must never silently erase ratings or other personal information.
