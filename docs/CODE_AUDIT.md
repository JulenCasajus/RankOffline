# RankOffline code audit

This inventory was produced after localization stabilized. It distinguishes behavior and data-integrity issues from optional style changes. No finding is permission for a broad rewrite.

| Category | Location | Finding and impact | Change risk | Proposed action |
|---|---|---|---|---|
| BUG | `PreferencesManager.kt`, rating settings UI | “Reset to defaults” calls a global reset: it can change image display, sticky header, rating mode, language, and launcher icon from the rating screen. The alias change can also send the Pixel launcher task away. | Medium | Make this control reset only the rating configuration; keep a separately named global reset only if a future UI explicitly needs it. |
| DATA INTEGRITY | `AnimeDatabase.kt`, `AnimeRepository.kt` | The unused `recalculateAll` path rewrites every canonical `final_score` from stored category scores. That contradicts last-save-wins when a newer simple rating exists. It currently has no caller, but retaining it creates a future data-loss trap. | Low | Remove the unused DAO/repository path; explicit elaborate saves remain the only recalculation trigger. |
| DEAD CODE | `MainActivity.kt` | `PhilosophyScreen` has no caller and duplicates rating-system explanation/labels. | Low | Remove it after confirming no navigation reference (confirmed by project-wide search). |
| COMPLEXITY | `MainActivity.kt` | One ~1,700-line file owns every screen and helper. This increases review cost, but splitting everything at once would create a high-risk diff with little behavioral value. | High | Do not perform a wholesale split in this task. Extract only when a future feature gives a clear boundary and matching tests. |
| DUPLICATION | `AppIcon`/`SupportedLanguage` settings UI | Icon and language pickers use similar card/radio layouts. | Medium | Leave separate: the data types, preview image, side effects, and accessibility text differ enough that a generic abstraction would save little. |
| MAINTAINABILITY | default rating configuration | Default category names/descriptions are persisted and editable strings, so localization cannot blindly replace them. | Medium | Keep stable IDs and localize only values still equal to canonical defaults. Preserve edited/custom strings verbatim. |
| MAINTAINABILITY | `SettingsRepository.loadInitialState` | Invalid rating JSON silently falls back to defaults; invalid icon/language values also fall back. This is safe but currently only partly tested. | Low | Keep fail-safe behavior and add focused fallback tests where practical. |
| PERFORMANCE | `CoverRepository` | `downloadStates` and `localPathSnapshots` retain entries for every anime encountered during a process lifetime. Typical queries cap lists at 200, but many searches can grow these maps. | Medium | Monitor; do not add fragile eviction while flows may still be observed. The bitmap cache itself is bounded by `LruCache`. |
| PERFORMANCE | catalog import | On a changed catalog hash, the full JSON is read into a String and `JSONArray`, temporarily duplicating a large asset in memory. It happens only on first install/catalog update and is transactional. | Medium | Retain for now; a streaming parser would add complexity and needs dedicated interrupted-import tests. |
| PERFORMANCE | search query | Leading-wildcard `LIKE` cannot fully exploit the title index, but the query is debounced, limited to 200 rows, and the catalog is ~40k entries. | Medium | Measure before considering FTS; no speculative database migration. |
| SECURITY | `AndroidManifest.xml` | INTERNET is the only requested permission; cleartext is disabled and backup is disabled. Launcher aliases are intentionally exported; no other internal component is exposed. | Low | No permission changes. |
| SECURITY | cover downloads | Initial URLs must be HTTPS, have finite timeouts/retries, and files are validated as images before atomic installation. Redirect behavior still relies on platform cleartext blocking. | Low | Keep standard platform behavior; no pinning or custom crypto. |
| PRIVACY | repositories/network | Network requests contain public anime IDs and fetch popularity/cover metadata only. Ratings, notes, categories, and preferences are never serialized into requests. No analytics/telemetry SDK is present. | Low | Document and preserve. |
| PRIVACY | logs | Logs contain technical page/count/ID/error information, not notes or rating contents. | Low | Keep minimal technical logs; do not add personal data. |
| PERFORMANCE | Compose/state | Root collects several flows, so changes can recompose broad regions; expensive database/network work remains outside composition and cover downloads are explicitly user-triggered. | Medium | Avoid speculative state-tree rewrite; profile before optimizing. |
| STYLE ONLY | formatting/import layout | Some functions and argument lists are dense. | Low | Do not churn solely for style. |

## Audit invariants

- No `GlobalScope`, destructive Room fallback, analytics, telemetry, cleartext opt-in, or main-thread production database/network call was found.
- Network retries are finite: AniList pages use three attempts; individual cover downloads use two attempts; cancellation is rethrown.
- Room remains the persisted catalog/rating source of truth, DataStore owns preferences, ViewModel owns derived UI state, and Compose renders it.
- Catalog generation remains an explicit manual script and is not part of CI/builds.
