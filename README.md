# RankOffline

RankOffline is a **local-first Android app** for browsing an offline anime catalog, rating anime with either a simple score or a configurable weighted system, and building a personal ranking without an account or telemetry.

## Highlights

- **40,742 anime** bundled for offline browsing and search.
- **Simple rating** from 0 to 10 in 0.5-point steps.
- **Elaborate rating** with reusable rating profiles, weighted categories, N/A support, and a separate personal-taste bonus.
- **Custom categories** with editable names, descriptions, colors, and stable IDs.
- **Custom profiles** with independent category weights; profiles can be created, renamed, edited, and deleted.
- **Personal ranking** driven by one canonical saved score per anime.
- Notes and watch status stored locally.
- Optional cover downloads and explicit Top 100/500/1000/5000 popularity refreshes.
- Nine UI languages: English, Spanish, German, French, Russian, Japanese, Simplified Chinese, Basque, and Italian.
- Five selectable launcher icons: Dark, Blue, Red, Green, and Yellow.
- No analytics, telemetry, account system, or cloud rating storage.

## Rating system

RankOffline supports two rating modes.

### Simple

Choose a score from **0.0 to 10.0** in **0.5** steps. Saving writes that value directly to the canonical final score.

### Elaborate

Rate the configured categories and choose a **rating profile** that determines their relative weights. Categories marked **N/A** are excluded and the remaining applicable weights are normalized.

The default categories are:

- Writing
- Characters
- Visuals
- Worldbuilding
- Audio

The default profiles are designed around what primarily carries a work rather than around anime genres:

- General
- Narrative
- Character-driven
- Visual / Action
- World / Adventure
- Atmospheric

A separate personal-taste bonus ranges from **+0.0 to +1.0** in 0.1 steps. The final elaborate score is capped at 10.

Changing a profile later does **not** silently recalculate previously saved anime. A final score only changes when that anime is explicitly saved again.

See [docs/SCORING.md](docs/SCORING.md) for the complete model.

## Offline catalog

The catalog is bundled into the APK, so browsing, search, ratings, notes, ranking, and settings work offline. Internet access is used only for explicit popularity/cover actions.

The bundled catalog currently contains **40,742 unique entries** and a complete local Top 5000 popularity snapshot. Catalog updates are deliberate and versioned.

To rebuild the catalog manually:

```bash
python tools/fetch_catalog.py
```

The script downloads AnimeAPI's master array, enriches known AniList IDs with
metadata and explicit relations, adds the Top 5000 popularity snapshot,
validates the result, and updates both `anime_catalog.json` and its
content-version hash. Group membership is derived offline from those explicit
relations; normal builds never contact AniList to rebuild the catalog. Do not
regenerate the catalog as part of an unrelated change.

## Install

### GitHub Releases

Download the latest `RankOffline-vX.Y.Z.apk` from the repository's **Releases** page and install it on Android.

### Obtainium

Add this GitHub repository to Obtainium to receive published RankOffline updates automatically. Android updates require the same application ID, the same signing key, and a strictly higher `versionCode`.

## Build locally

Requirements:

- Android Studio
- JDK 17
- Android SDK 35

The application ID and namespace are:

```text
org.rankoffline.app
```

Build a debug APK with:

```bash
./gradlew assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

For a locally signed release build, configure `keystore.properties` as described in [docs/RELEASING.md](docs/RELEASING.md), then run:

```bash
tools/build_release.sh
```

Local signing files, generated APKs, and credentials are intentionally ignored by Git.

## Continuous integration and releases

`.github/workflows/android.yml` validates the bundled catalog, runs JVM tests, builds the debug APK, and uploads it as an Actions artifact for pushes to `main` and pull requests.

`.github/workflows/release.yml` builds and verifies the signed release APK using GitHub Actions secrets. A pushed `v*` tag creates the corresponding GitHub Release automatically.

See [docs/RELEASING.md](docs/RELEASING.md) for the release checklist and signing setup.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Scoring model](docs/SCORING.md)
- [Releasing](docs/RELEASING.md)
- [Roadmap](docs/ROADMAP.md)

## Data attribution

The bundled catalog is built from [AnimeAPI by nattadasu](https://github.com/nattadasu/animeApi/tree/v3)
and its upstream/open data sources. AnimeAPI documents its compiled database
under [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/) with the
individual contents under [DbCL 1.0](https://opendatacommons.org/licenses/dbcl/1-0/),
while some raw source components are published under
[CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/). Review the
upstream terms before redistributing the bundled derived database and preserve
the required attribution/share-alike notices.

AniList provides catalog metadata, explicit relations, popularity, and primary
cover metadata. Jikan is used as a cover fallback where needed.

## Architecture and privacy

RankOffline uses Jetpack Compose, Room, and DataStore. Ratings, notes, profile/category configuration, the database, and downloaded covers remain in app-private storage.

Android Auto Backup and cleartext traffic are disabled. Network requests are limited to public anime identifiers and cover/popularity metadata; personal ratings and notes are not sent to those services.

## License and trademarks

RankOffline is free and open-source software licensed under the
[GNU General Public License v3.0](LICENSE) (`GPL-3.0-only`). You may use,
study, modify, and redistribute its original application source code under the
terms of GPLv3, including for commercial purposes.

Previous releases remain available under the licence under which they were
originally published.

The official RankOffline name, logo, launcher icons, and distinctive branding
are governed separately by the [trademark policy](TRADEMARKS.md). Modified or
unofficial distributions must use their own name and branding.

The bundled catalog, Android/Jetpack dependencies, and other third-party
components remain under their own licenses described above; the RankOffline
license does not replace those terms.
