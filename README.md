# RankOffline

RankOffline is a **local-first Android app for rating and ranking media** without an account, telemetry, or cloud-stored ratings.

The current stable release is **v0.1.3**, focused on anime. The upcoming **v0.2.0** expands the app to Anime, Manga, Movies, Series, and Books.

## What it does

- Rate items with a **simple 0–10 score** or an **elaborate weighted system**.
- Build a personal ranking from your saved scores.
- Keep ratings, notes, preferences, and configuration on-device.
- Browse supported catalogs offline once they are available locally.
- Customize rating categories, profiles, language, launcher icon, and display preferences.
- Use the app without creating an account.

## Privacy

RankOffline is designed around local data.

- No analytics or telemetry.
- No account system.
- No cloud rating storage.
- Personal ratings and notes are not sent to catalog providers.
- Network access is only used when a feature explicitly needs remote catalog or cover data.

## Versions

### Stable — v0.1.3

The stable app currently focuses on anime and is available from GitHub Releases.

### In development — v0.2.0

The multi-media version is being developed with support for:

- Anime
- Manga
- Movies
- Series
- Books
- First-launch onboarding
- Category switching and management
- Downloadable/removable catalog packs
- Separate debug builds that can coexist with the stable app

Development builds may be incomplete and should not be treated as stable releases.

## Install

### GitHub Releases

Download the latest stable APK from the repository's **Releases** page.

### Obtainium

Add this GitHub repository to Obtainium to follow stable releases.

If you intentionally want development builds, enable pre-releases for a separate development installation. Debug builds use a different application ID and can coexist with the stable app.

## Build locally

Requirements:

- Android Studio
- JDK 17
- Android SDK 35

Build a debug APK:

```bash
./gradlew assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release builds require the signing setup described in [docs/RELEASING.md](docs/RELEASING.md).

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Scoring](docs/SCORING.md)
- [Releasing](docs/RELEASING.md)
- [Roadmap](docs/ROADMAP.md)

## Tech

RankOffline is built with:

- Kotlin
- Jetpack Compose
- Room
- DataStore

## License

RankOffline is free and open-source software licensed under the [GNU GPL v3.0](LICENSE).

The RankOffline name, logo, launcher icons, and distinctive branding are covered separately by [TRADEMARKS.md](TRADEMARKS.md).

Third-party catalog data, dependencies, and media metadata remain subject to their own licenses and terms.
