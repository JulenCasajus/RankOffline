# Changelog

## 0.2.2 integration work

- Added a debug-only SAF installer that exercises the validated local pack,
  Room import and READY path without bundling archives in the APK.
- Added separate supplied Share backgrounds for every official package and new
  default Top 7/Top 10 artwork, stored as high-quality WebP.
- Added reproducible schema-2 package validation and the documented declarative
  Anime cover-source proposal.

## 0.2.2 — unreleased

- Replaced the legacy category-specific engine with 194 universal Kotlin files.
- Adopted numeric positive `PackId`; zero is virtual RankOfflineAll.
- Added manifest schema 2, catalog/group format 2 and personal backup schema 2.
- Added composite pack-scoped Room storage, paged All, generic covers, group
  relationships, Top 7/Top 10 Share and durable pack work.
- Reconstructed the Android project with AGP 9.5, Gradle 9.8.2, Kotlin 2.4.21,
  API 37, Compose, Room/KSP and WorkManager.
- Removed built-in content/provider catalogs and obsolete schema-1 pack tools.
- Added a declarative, deterministic schema-2 pack builder, an independent
  strict validator, negative fixtures and conversion configurations for the
  five official catalog snapshots. Generated `.pack` files are not committed.

Not included: legacy migration, remote registry, pack updates/synchronization,
Google Play publication or definitive Share artwork.
