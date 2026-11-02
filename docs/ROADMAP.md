# Roadmap

## Required before a release candidate

- Complete Gradle sync/build/lint/KSP and emulator validation.
- Add Kotlin/Room end-to-end installation tests for the generated schema-2
  artifacts and Room migration/schema tests.
- Design contextual Android 13+ notification permission UX.
- Audit launcher/onboarding/podium artwork ownership and replace where needed.
- Confirm documentary provenance and publication rights for the supplied
  default and package-specific Share artwork.
- Validate Android 17 behavior, accessibility, localization and large screens.

## Explicitly later

- Trusted pack registry/discovery and publisher identity policy.
- Public local pack import UX. The current SAF installer is deliberately
  debug-only; the schema-2 authoring/validation CLI exists under `scripts/`.
- Pack updates, synchronization and v0.2.1 migrations.

These items are not current features and must not be advertised as available.
