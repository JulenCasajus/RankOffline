# v0.2.1 archive disposition

The reference archive was inspected without importing its Kotlin/Java source.

| Legacy material | Disposition in v0.2.2 |
|---|---|
| GPL `LICENSE` | Reused verbatim |
| Launcher aliases/icons, onboarding and podium images | Reused provisionally because current Kotlin references them; legal audit pending |
| Translations | Mechanically filtered to only current `R.string`/`R.plurals`; provider/category-specific unused entries discarded |
| Theme, locale list, backup exclusion, FileProvider paths | Reconstructed and updated |
| Gradle wrapper scripts/JAR | Bootstrap reused; distribution pinned to 9.8.2; wrapper JAR refresh pending |
| Root/module Gradle, manifest, ProGuard, release script | Replaced |
| README/docs/privacy/legal | Replaced or rewritten for v0.2.2 |
| Old Kotlin/Java and tests | Discarded as implementation source |
| APKs, build outputs, caches, IDE files and embedded Git data | Discarded |
| Built-in catalogs, provider assets/logos and category pack index | Discarded |
| Schema-1 pack builder and provider-specific fetchers | Not copied; incompatible and category-specific |
| Performance/production reports for old datasets | Historical only; not copied into active documentation |

New material includes the Android project configuration, canonical format docs,
release/static-check scripts, default Share backgrounds, Room schema output
directory and current privacy/release guidance.
