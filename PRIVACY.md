# Privacy and data handling

**Scope:** the publicly released RankOffline Android app **v0.2.0** (26 October 2026). This document describes the application's behavior, not the independent practices of third-party providers.

## Summary

RankOffline is free and offline-first. It requires no account, includes no advertising or analytics SDK, and does not operate a server for users' ratings or notes. Once a catalog pack is installed, browsing, searching, filtering, rating and ranking work without an internet connection.

## Data stored on your device

RankOffline stores installed catalog data, your ratings and notes, rating profiles, statuses, settings and optional image caches in Android app-private storage using Room, DataStore and local files. The app does not provide automatic cloud synchronization of your personal ratings.

## Network requests

The app requests Android's `INTERNET` permission. You need connectivity to download chosen catalog packs from GitHub Releases. Optional artwork requests may contact AniList/Jikan, MangaDex, TMDB or Open Library, depending on the catalog and settings. Choosing a website link in the About/attribution screen opens that destination through Android's link handler.

These services and their infrastructure receive request information, potentially including your IP address, requested image or pack, time and other technical metadata. They may retain logs under their own policies. **RankOffline does not upload your personal ratings or notes to these services.** Offline-first does not mean optional downloads are anonymous.

For source information see [Data sources](docs/DATA_SOURCES.md) and [Third-party notices](THIRD_PARTY_NOTICES.md).

## Covers

Cover downloads are optional; downloaded images are cached locally for offline display. Catalogs remain usable without downloading covers.

## Deleting data and backups

**Important for v0.2.0:** choosing to delete a pack **and its data** deletes that pack's local ratings, notes, covers and pack settings. Normal updates and catalog refreshes are designed to preserve personal data, but clearing Android app data or uninstalling RankOffline can erase local data. Android automatic cloud backup is disabled.

A separate option to keep personal data when removing packs, and JSON personal-data export/import, are being developed for a future release. **Neither feature is present in the public v0.2.0 release.** Do not rely on them as a backup method yet.

## Voluntary contact and reports

If you create a public GitHub issue or comment, the information you submit is governed by GitHub's services, not an in-app RankOffline account. Avoid sharing ratings exports, private notes, tokens or sensitive logs publicly. Follow [SECURITY.md](SECURITY.md) for sensitive reports.

For questions or corrections, use the [issue tracker](https://github.com/JulenCasajus/RankOffline/issues). This notice should be reviewed whenever publicly released behavior changes.
