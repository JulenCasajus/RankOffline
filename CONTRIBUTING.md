# Contributing to RankOffline

Thanks for helping improve RankOffline! Focused bug reports, reproducible test cases, accessibility improvements, translations, documentation and code contributions are welcome.

## Before working

Read the [README](README.md), [architecture](docs/ARCHITECTURE.md), [roadmap](docs/ROADMAP.md), and [data source constraints](docs/DATA_SOURCES.md). Search [existing issues](https://github.com/JulenCasajus/RankOffline/issues) first; discuss major features before implementing large changes. For vulnerabilities, follow [SECURITY.md](SECURITY.md) rather than filing a public issue.

## Development setup

Use **JDK 17**, **Android SDK 35**, Android Studio and the included Gradle wrapper.

```bash
git clone https://github.com/JulenCasajus/RankOffline.git
cd RankOffline
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

With an appropriate emulator or device connected, you can also run:

```bash
./gradlew connectedDebugAndroidTest
```

Debug builds use a different application ID than signed stable releases. A debug upgrade does not validate migration of a real signed stable installation.

## Contributing changes

1. Fork the repository and create a topic branch from current `main`.
2. Make one focused change; avoid unrelated refactors or generated changes.
3. Add tests for behavior changes, especially Room migrations, offline packs and preservation of user scores/notes.
4. Run applicable tests, `git diff --check` and a debug build. Record what actually passed, failed or was not run.
5. Update relevant user-facing documentation and translations.
6. Open a pull request using the provided template and explain the change, risks and verification.

## Project safeguards

- Preserve ratings, notes and stored settings through updates; never introduce destructive database fallback.
- Keep installed catalogs usable offline and optional artwork distinct from catalog availability.
- Treat pack files, backups and remote content as untrusted input; validate them and never execute pack-supplied code.
- Do not commit signing keys, secrets, user data or locally built APKs.
- Avoid tracking, mandatory accounts or new network use without project discussion.
- **Third-party catalogs, images, provider responses, logos and fonts are not automatically GPL-licensed.** Establish provenance, permission and notices before distributing them.

Original application source code is [GPL-3.0-only](LICENSE) unless stated otherwise. Provider content and project marks have separate terms: [Third-party notices](THIRD_PARTY_NOTICES.md) and [Trademark policy](TRADEMARKS.md). Please submit only work you have the right to contribute under the applicable terms.

The current public stable version is **v0.2.0**; v0.2.1 ideas or local implementations must not be described as shipped.
