# RankOffline

Android app (Kotlin + Jetpack Compose) to keep an **offline anime catalog** and rate anime with a quality-first weighted system plus a separate personal-taste bonus.

## Rating system

Base quality score:

- Writing — **35%**
- Characters — **25%**
- Engagement — **20%**
- Visuals — **15%**
- Worldbuilding — **5%**

Personal taste: **+0.0 to +1.0**.

`final = min(10, weighted_quality + personal_taste)`

A category can be marked **N/A**. Its weight is not treated as zero; the remaining weights are normalized. This follows the idea of not punishing a work for an element it does not need.

## Score scale

10 Flawless · 9 Amazing · 8 Great · 7 Good · 6 Decent · 5 Average · 4 Subpar · 3 Poor · 2 Bad · 1 Unwatchable

## Offline catalog

Anime titles are bundled into the APK at build time, so catalog browsing, search, ratings, notes, and settings work offline. Internet access is reserved for explicit cover resolution; scrolling lists does not start network requests. Ratings, notes, and category settings are not sent to cover providers.

The repository includes the versioned full catalog asset (currently 40,744 entries). Popularity can be refreshed explicitly in the app from Settings; until then, entries without a bundled popularity snapshot fall back to title order. To update the catalog and bundle a complete AniList Top 5000 snapshot deliberately, run:

```bash
python tools/fetch_catalog.py
```

This downloads AnimeAPI's master array, enriches it with AniList Top 5000 popularity values and stable local ranks, and updates both `anime_catalog.json` and its content-version hash. The command refuses to replace the asset if the Top 5000 response or catalog matching is incomplete. Room upserts a changed asset without deleting ratings, cover metadata, settings, or rankings refreshed in the app; unchanged catalogs are not reimported.

AnimeAPI currently aggregates tens of thousands of anime mappings/titles from multiple databases. Its compiled database is ODbL 1.0 + DbCL 1.0 and requires attribution/share-alike for derived public databases. Keep the attribution if you distribute the full catalog.

## Instalación

### GitHub Releases

Descarga `RankOffline-vX.Y.Z.apk` desde la sección **Releases** del repositorio e instálalo en Android. Android puede pedir permiso para instalar aplicaciones desde el navegador o gestor de archivos utilizado.

### Obtainium

Añade el repositorio GitHub de RankOffline a Obtainium para recibir las nuevas versiones publicadas mediante GitHub Releases. Las actualizaciones requieren el mismo `applicationId`, la misma clave de firma y un `versionCode` superior.

## Build local

Recommended: Android Studio, JDK 17, Android SDK 35.

The definitive Android application ID and namespace are `org.rankoffline.app`.

1. Open the project in Android Studio.
2. Sync Gradle.
3. Build > Build APK(s).

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Para crear el APK de distribución firmado, configura primero el keystore local siguiendo [docs/RELEASING.md](docs/RELEASING.md) y ejecuta:

```bash
tools/build_release.sh
```

El resultado se copia a `dist/RankOffline-v0.1.0.apk`. Las claves y contraseñas de firma nunca deben añadirse al repositorio.

## GitHub build

`.github/workflows/android.yml` builds from the catalog committed with the source and automatically:

1. runs unit tests,
2. builds with the Gradle Wrapper,
3. uploads the debug APK as a GitHub Actions artifact.

You can also run it manually from **Actions > Build Android APK > Run workflow**.

## Current MVP features

- Offline anime catalog + search
- Automatic ranking by final score
- Configurable weighted categories with stable IDs
- N/A per category with weight renormalization
- Personal taste bonus from +0.0 to +1.0
- 0.5-step quality sliders
- Notes
- Watch status
- Room storage with explicit non-destructive migrations
- Always-available on-demand cover downloads and explicit Top 100/500/1000/5000 popularity-and-cover updates
- Cover preload progress and private cover-cache cleanup
- Dark/neon UI inspired by the provided rating chart
- Five selectable launcher icons: Dark (default), Blue, Red, Green, and Yellow

## Planned next steps

- Backup/import ratings as JSON
- Favorites and tags
- Filters by score/status
- Franchise grouping

## Data attribution

Catalog build source: AnimeAPI by nattadasu and its upstream/open data sources. Database licensing reported by AnimeAPI: ODbL 1.0 + DbCL 1.0 (with some source components under other compatible licenses). Review the upstream licensing before publishing a redistributed database.

## Architecture and privacy

Compose observes a ViewModel, which delegates to repositories backed by Room and DataStore. Database, catalog, image, and network work runs off the main thread. Ratings, notes, categories, the database, and cached covers remain in app-private storage. Android Auto Backup and cleartext traffic are disabled, and the app contains no analytics.

Database v7 migrates legacy installations explicitly, adding nullable AniList popularity in v6 and a unique nullable local popularity rank in v7. The five legacy score columns become `rating_score` rows keyed by permanent category IDs; no destructive fallback is configured. A complete Top 5000 snapshot can be bundled for offline sorting, while catalog-only assets preserve rankings previously refreshed in the app. Top 100/500/1000/5000 actions transactionally reconcile the requested ranking before downloading only missing covers.
