# RankOffline roadmap

**Current public stable:** [v0.2.0](https://github.com/JulenCasajus/RankOffline/releases/tag/v0.2.0). Items below describe work under development or longer-term proposals, **not yet included in the publicly released APK**.

## v0.2.1: development and independent review

Local development has been reported for these areas. Compilation and local test results do **not** prove they are ready for a stable release; the changes still require independent review, device checks, and a properly signed/publicly tagged release.

- Consolidation of shared catalog engines while keeping published-version migrations.
- More persistent, cancellable and resumable downloads and optional cover operations.
- Physical pack-storage reporting alongside clearly labeled estimates for personal data.
- An explicit option to uninstall a pack but preserve personal ratings/notes, separate from destructive removal.
- Improved rating-profile selection.
- **RankOfflineAll**: a virtual combined Search/Ranking view across at least two READY packs; not a sixth downloadable catalog and not a duplicate ratings database.
- Versioned JSON personal-data import/export using Android's document picker, with validation and explicit Merge/Replace behavior.
- Validation and documentation for possible future community catalog packs, **not** a publicly shipped pack registry.

**Release checks still needed:** real-device interrupted WorkManager operations, upgrade/migration of signed stable data, full catalog performance, recovery/backup round-trips, large-font UI, accessibility, source permissions and bundled-asset rights.

## Longer-term ideas

- Safe optional community pack discovery, subject to publisher permissions, provenance and identity collision checks; no executable plugins.
- Interoperable ratings imports when permission and correctness can be established.
- Legally distributable cover assets where allowed.
- Continued performance, nine-language localization, TalkBack and large-text work.

## Invariants

- Offline browsing and ranking after pack installation; no mandatory account.
- Do not execute code from catalogs or backups; validate all untrusted inputs.
- No destructive Room migration fallback and no silent data loss during normal updates.
- Provider relationships are prepared from vetted data, not guessed from phone-side fuzzy title matching.
- Personal data remains reliably scoped to a pack identity.

Related guidance: [Architecture](ARCHITECTURE.md) · [Data sources](DATA_SOURCES.md) · [Releases](RELEASING.md) · [Privacy](../PRIVACY.md).
