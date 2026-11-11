# 🎯 RankOffline

**Rate what you love. Rank it your way. Keep it offline.**

RankOffline is a **free, open-source Android app** for discovering and ranking **Anime, Manga, Movies, Series, and Books**. Build a personal library with customizable ratings, beautiful rankings, and offline access to your installed catalogs — **no account, no cloud sync, no analytics**.

**[Download RankOffline v0.2.2](https://github.com/JulenCasajus/RankOffline/releases/tag/v0.2.2)** · [Installation](#installation) · [Rating system](#rate-your-way) · [Privacy](#offline-first-and-private)

**Project information:** [Privacy](docs/legal/PRIVACY.md) · [Changelog](docs/CHANGELOG.md) · [Development](docs/DEVELOPMENT.md)

## ✨ Discover. Rate. Rank.

| Discover your favorites | Rate your way | See your personal podium |
|:---:|:---:|:---:|
| <img src="docs/screenshots/anime-browse.webp" alt="Search the Anime catalog with cover art and personal scores" width="240"> | <img src="docs/screenshots/elaborate-rating.webp" alt="Elaborate rating with weighted categories and a Narrative rating profile" width="240"> | <img src="docs/screenshots/ranking-podium.webp" alt="Personal Anime ranking with a gold silver and bronze podium" width="240"> |

## 📚 Five independent offline catalogs

Choose only the categories you want. **Anime, Manga, Movies, Series, and Books** are separate downloadable data-only packs; the app itself does not require all five. After a pack is installed, you can browse its catalog, search, filter, rate, and rank **without an internet connection**.

| Manga with artwork | Series grouped by show | Books without images |
|:---:|:---:|:---:|
| <img src="docs/screenshots/manga-browse.webp" alt="Browse the Manga catalog with cover images" width="240"> | <img src="docs/screenshots/series-merged.webp" alt="Series catalog grouped by parent TV show" width="240"> | <img src="docs/screenshots/books-no-covers.webp" alt="Browse Books in a compact text-first interface" width="240"> |

Cover images are **optional and downloaded separately** when requested or enabled in the settings for a new pack. They are cached locally for offline viewing.

## ⭐ Rate your way

RankOffline offers two independent ways to evaluate a work:

**Simple rating:** choose a score from **0 to 10**, in **0.5-point steps**. The optional **personal taste bonus** adds **+0.0 to +1.0** in 0.1 steps when enabled; it is off by default.

**Elaborate rating:** evaluate categories and choose a reusable rating profile. Each pack declares its own default categories and profiles; the Anime pack includes **Writing, Characters, Visuals, Worldbuilding, and Audio** and profiles such as **General** and **Narrative**. Mark categories **N/A** to exclude them and renormalize the remaining weights. If enabled, personal taste is added after quality scoring; the final score is capped at 10.

Create and edit your own categories, category colors/descriptions, profiles, and weights. Add **personal notes** and keep a local status for each item. Saved scores are not silently recalculated just because you change a profile later.

| Simple rating | Elaborated rating |
|:---:|:---:|
| <img src="docs/screenshots/simple-rating.webp" alt="Simple Manga rating with personal taste bonus and notes" width="280"> | <img src="docs/screenshots/elaborate-rating.webp" alt="Elaborated Anime rating with weighted criteria and a Narrative profile" width="280"> |

Read the [scoring documentation](docs/SCORING.md) for the full formula and behavior.

## 🔎 Powerful search and sorting

Browse completely offline by title, including supported **alternative titles and aliases**. For example, searching *Attack* and *Shingeki* can find the same series using different names, while preserving the underlying identity and ratings.

| Search using an alias | Search using the main title |
|:---:|:---:|
| <img src="docs/screenshots/anime-alias-search.webp" alt="Attack on Titan results found using the Attack alias" width="280"> | <img src="docs/screenshots/anime-primary-title-search.webp" alt="Shingeki no Kyojin results found by their main title" width="280"> |

Use the **filters declared by each pack**, such as genre, format, year or studio where available. Filters support multiple values and OR/AND matching within a field. Sort by **popularity**, **name**, **final score** or individual rating categories, depending on the screen and available data. My Ranking can show a full scored list or a **Top 3 podium**, and its sort criterion changes who gets the gold, silver and bronze positions.

## 🔗 Merge seasons, collections, and variants

Switch **Merge** on or off without changing the original catalog data. RankOffline uses provider relationships prepared beforehand, not fuzzy matching on your phone. Supported examples include anime seasons, manga variants, movie collections, TV seasons, and conservatively identified book series.

| Movies as individual entries | Movies grouped by collection |
|:---:|:---:|
| <img src="docs/screenshots/movies-ungrouped.webp" alt="Movie catalog with Merge collections disabled" width="280"> | <img src="docs/screenshots/movies-merged.webp" alt="Movie catalog with Merge collections enabled" width="280"> |

## 🎨 Make it yours

- **Theme palettes:** choose a built-in palette, customize supported themes, or save your own.
- **Eight launcher icons:** switch the app icon from Settings.
- **Nine interface languages:** English, Spanish, German, French, Russian, Japanese, Simplified Chinese, Basque, and Italian.
- **Per-pack settings:** choose whether to show images, show titles on the podium, and keep the score header visible. The Merge button controls group presentation in Search and My Ranking.
- **Cover management:** optionally download covers, show larger artwork, and remove downloaded covers.

| Color themes | Launcher icons | Interface languages |
|:---:|:---:|:---:|
| <img src="docs/screenshots/themes.webp" alt="RankOffline theme palette choices" width="240"> | <img src="docs/screenshots/icons.webp" alt="Choose from eight RankOffline launcher icons in Settings" width="240"> | <img src="docs/screenshots/languages.webp" alt="RankOffline interface language selection with nine available languages" width="240"> |

<details>
<summary><strong>More screenshots: filters, category rankings, and cover viewer</strong></summary>

| Advanced filters | Rank by Writing | Full-size cover preview |
|:---:|:---:|:---:|
| <img src="docs/screenshots/filters.webp" alt="Anime genre, format, year, and studio filters" width="220"> | <img src="docs/screenshots/ranking-by-category.webp" alt="Personal ranking sorted by Writing scores" width="220"> | <img src="docs/screenshots/cover-viewer.webp" alt="Full-size Berserk manga cover with a cover removal action" width="220"> |

</details>

## 📥 Installation

**Android 8.0 (API 26) or newer.**

1. Get the signed APK from the **[RankOffline v0.2.2 GitHub Release](https://github.com/JulenCasajus/RankOffline/releases/tag/v0.2.2)** (`RankOffline-v0.2.2.apk`). Install it on Android.
2. In RankOffline, install the catalog packs you want while connected to the internet.
3. Optionally enable/download cover images. Your installed catalogs, ratings, notes, and downloaded covers then work offline.

**Obtainium:** add `https://github.com/JulenCasajus/RankOffline` as a GitHub source to track stable updates. Exclude prereleases if you only want signed stable versions. The stable app ID is `org.rankoffline.app`; development APKs use `org.rankoffline.app.debug` and are separate installations.

> **Data safety:** ordinary pack uninstall keeps ratings, notes and personal settings. An explicit purge removes that pack's personal data. Updating from the published 0.2.0 app can import existing personal ratings; Android automatic cloud backup remains disabled.

## 🔒 Offline-first and private

- Browse, search, filter, rate, and rank installed catalogs **offline**.
- Personal ratings, notes, rating profiles, preferences, and cached covers stay in **app-private storage**.
- **No login, analytics, telemetry, or cloud rating database.**
- Network access is used **only** for packs and cover downloads.
- The app uses **Jetpack Compose, Room, and DataStore**. Its data-only pack files are integrity-checked before installation.

## 🛠️ For developers

Requirements: **Android Studio, a supported JDK, and Android SDK 37**. The app supports Android 8.0 (API 26) and newer.

```bash
./scripts/check-kotlin-limits.sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

Debug and stable builds use different Android application IDs. Release signing uses the `RANKOFFLINE_UPLOAD_*` properties or environment variables described in [Development](docs/DEVELOPMENT.md). **Do not commit signing keys or secrets.**

Useful technical resources: [Architecture](docs/ARCHITECTURE.md) · [Pack format](docs/PACK_FORMAT.md) · [Manifest specification](docs/PACK_MANIFEST_SPEC.md) · [Pack generation](docs/PACK_GENERATION.md) · [Scoring](docs/SCORING.md) · [Backup format](docs/BACKUP_FORMAT.md).

Version **0.2.2** adds RankOffline All, personal-data import/export, an upgrade path for 0.2.0 ratings, and durable background pack operations. See the [changelog](docs/CHANGELOG.md) for details.

## 📖 Data sources and attribution

- **Anime:** [AnimeAPI](https://github.com/nattadasu/animeApi), [AniList](https://anilist.co), [Jikan](https://jikan.moe), and pinned title aliases from [anime-offline-database](https://github.com/cedya77/anime-offline-database).
- **Manga:** [MangaDex](https://mangadex.org).
- **Movies and Series:** [The Movie Database (TMDB)](https://www.themoviedb.org).
- **Books:** [Open Library](https://openlibrary.org).

Third-party catalogs, artwork, API responses, names and logos have their own licensing and attribution requirements, distinct from the app's license. See each installed pack's attribution and the [third-party notices](docs/legal/THIRD_PARTY_NOTICES.md). This app is not endorsed or certified by its data providers.

## ⚖️ License and branding

RankOffline's original application source code is free and open-source software licensed under **[GPL-3.0-only](LICENSE)**. The original RankOffline name, logos, launcher icons and distinctive branding are subject to the separate [trademark policy](docs/legal/TRADEMARKS.md); modified or unofficial distributions must use their own branding. Provider content is not relicensed by the app's GPL.

## ❤️ Support RankOffline

RankOffline is free and open source. If it is useful to you, you can support its continued maintenance and development through **[GitHub Sponsors](https://github.com/sponsors/JulenCasajus)**. Donations are optional and the app remains free.
