# AnimeRank Offline

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

The app itself has **no Internet permission**. Anime titles are bundled into the APK at build time.

The repository includes a small sample asset so it opens immediately. Before making a real APK, run:

```bash
python tools/fetch_catalog.py
```

This downloads AnimeAPI's master array and converts it into `app/src/main/assets/anime_catalog.json`. The database is imported into local SQLite the first time the app starts.

AnimeAPI currently aggregates tens of thousands of anime mappings/titles from multiple databases. Its compiled database is ODbL 1.0 + DbCL 1.0 and requires attribution/share-alike for derived public databases. Keep the attribution if you distribute the full catalog.

## Build locally

Recommended: Android Studio, JDK 17, Android SDK 35.

1. Run `python tools/fetch_catalog.py` to bundle the full offline catalog.
2. Open the project in Android Studio.
3. Sync Gradle.
4. Build > Build APK(s).

If you have Gradle 8.9 installed:

```bash
gradle :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## GitHub build

`.github/workflows/android.yml` automatically:

1. downloads the current catalog,
2. builds the Android APK,
3. uploads the APK as a GitHub Actions artifact.

You can also run it manually from **Actions > Build Android APK > Run workflow**.

## Current MVP features

- Offline anime catalog + search
- Automatic ranking by final score
- 5 weighted quality categories
- N/A per category with weight renormalization
- Personal taste bonus from +0.0 to +1.0
- 0.5-step quality sliders
- Notes
- Watch status
- Local SQLite storage
- Dark/neon UI inspired by the provided rating chart

## Planned next steps

- Backup/import ratings as JSON
- Favorites and tags
- Filters by score/status
- Optional offline cover cache
- Franchise grouping
- Release APK signing

## Data attribution

Catalog build source: AnimeAPI by nattadasu and its upstream/open data sources. Database licensing reported by AnimeAPI: ODbL 1.0 + DbCL 1.0 (with some source components under other compatible licenses). Review the upstream licensing before publishing a redistributed database.
