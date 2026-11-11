# Development

## Requirements

Use the checked-in Gradle wrapper, an Android SDK with API 37, and a JDK supported by
the configured Gradle and Android Gradle Plugin versions. The project has been built
with Temurin 25. `gradle/libs.versions.toml` and
`gradle/wrapper/gradle-wrapper.properties` are the source of truth for dependency and
wrapper versions. The Android module targets API 37, supports API 26 and newer, and
emits Java 17 bytecode.

Open the repository root in Android Studio. Production Kotlin is under
`app/src/kotlin/`, resources under `app/src/res/`, and tests under `app/tests/`. AGP's
built-in Kotlin support is used; the Compose compiler, Serialization, Room and KSP
plugins are declared in `app/build.gradle.kts`.

## Local checks

```bash
./scripts/check-kotlin-limits.sh
python3 -m unittest discover -s scripts/tests -v
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest \
  --no-daemon --no-configuration-cache
```

`assembleDebugAndroidTest` compiles the instrumented-test APK. To execute those tests,
use an isolated emulator or test device and run `./gradlew
:app:connectedDebugAndroidTest`. Some network tests require explicit opt-in or local
fixtures; a skipped test is not a passed network test. Use `git diff --check` before
publishing source changes. Room schema exports are stored in `app/schemas/` and should
be reviewed when the database changes.

## Pack development

The debug and release variants read variant-specific HTTPS package indexes from
`BuildConfig`. The index supplies archive URLs, sizes, hashes and parsed manifests; no
official pack is bundled in the APK. Pack installation verifies the archive and imports
its catalog before marking it READY. An offline restart should keep an already installed
catalog available.

Build packs first, then validate them with `./scripts/validate_packs_with_kotlin.sh`;
the commands and inputs are in [PACK_GENERATION.md](PACK_GENERATION.md). To prepare a
distribution directory, use `scripts/prepare_pack_release.py` with the exact immutable
`.pack` artifacts and matching HTTPS release URL. The script stages files and writes a
new index; it does not upload or publish them. Check the prepared files and hashes
before any release operation.

## Signing and release builds

A release build uses the upload signing configuration only when all four
`RANKOFFLINE_UPLOAD_*` signing properties or environment variables are supplied. Keep
keystores, passwords and `local.properties` out of Git. A locally built release APK
without upload signing is not a publishable signed artifact.

Before a public release, test the migration from a published 0.2.0 installation,
installation and recovery with real packs, Search/MyRanking and Merge, backup and
restore, cover fallback, sharing, and process restart on supported Android versions.
Perform destructive tests on an isolated emulator, not a user's installation.
