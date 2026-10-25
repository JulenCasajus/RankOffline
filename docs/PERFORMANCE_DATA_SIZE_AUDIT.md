# Performance and data-size audit

Date: 2026-10-15
Scope: Block 19, `feature/multi-media`, Room 11, version 0.1.3 (code 4)

## Result

The current Anime-only production payload is suitable for final regression testing. No open P0
performance issue remains after correcting Anime-alias packaging and removing byte-identical icon
copies. The final debug APK is 29,898,124 bytes (28.513 MiB), 6,039,001 bytes smaller than the
initial audit build. Production Manga, Movie, Series, and Book catalogs are absent, so their size
and runtime behavior are not yet measurable.

The principal scaling constraint is substring search (`LIKE '%query%'`) over 40,742 Anime and
218,249 aliases. It is acceptable-to-WATCH on desktop SQLite, but normal B-tree title indexes do
not accelerate the leading wildcard. FTS/search redesign remains deferred. Before large generic
catalogs ship, generic unbounded result loading and per-group detail queries also need measurement
and likely batching.

## Methodology

- `./gradlew assembleDebug` without `clean`; APK inspected with ZIP metadata, not repacked.
- Source sizes use exact filesystem bytes. Git sizes use tracked files only.
- `tools/performance/audit_data_size.py` creates an ignored
  `tools/.cache/performance/anime-audit.sqlite` database and JSON report.
- The SQLite model uses the real committed 40,742-item Anime catalog, all 218,249 aliases,
  real genre/studio/group rows, and deterministic modeled ratings for every tenth Anime.
- Search samples have one warm-up plus 30 timed iterations. Host: macOS Darwin 24.6.0,
  Intel i9-9880H, Python 3.14.7, SQLite 3.53.4, JDK 21.0.11.
- Merged-search timings reproduce its text/member/alias visibility shape; the shared group-rating
  aggregate is timed separately rather than claiming a byte-for-byte Room execution benchmark.
- These are desktop SQLite measurements, not Android-device latency or an exact Room database.
  WAL files, user history, filesystem variance, Android SQLite versions, and device contention are
  excluded.

Reproduce after building the debug APK:

```bash
python3 tools/performance/audit_data_size.py --iterations 30
```

The command is offline, makes no provider requests, changes no catalog, and writes only under the
ignored `tools/.cache/` path.

## Baseline

| Metric | Value | Status / notes |
|---|---:|---|
| Final debug APK | 29,898,124 bytes / 28.513 MiB | Measured |
| APK entry contents | 87,008,288 bytes / 82.978 MiB | Measured uncompressed entry sum |
| APK compressed entry payload | 29,732,272 bytes / 28.355 MiB | Measured; excludes ZIP/signing overhead |
| Initial audit APK | 35,937,125 bytes / 34.272 MiB | Measured before Block 19 fixes |
| Initial APK entry contents | 109,445,543 bytes / 104.375 MiB | Measured before fixes |
| Anime catalog | 16,347,488 bytes / 15.590 MiB | Measured; 40,742 Anime |
| Anime aliases, gzip | 3,140,724 bytes / 2.995 MiB | Measured; SHA-256 `a5e371…7a98` |
| Anime aliases, JSONL | 21,381,437 bytes / 20.391 MiB | Measured decompressed |
| Alias records / aliases | 31,995 / 218,249 | Manifest and stream verified |
| Synthetic SQLite database | 54,792,192 bytes / 52.254 MiB | Modeled, 4 KiB pages after `VACUUM`/`ANALYZE` |
| Alias table plus indexes | 40,169,472 bytes / 38.309 MiB | Modeled; 73.31% of synthetic DB |
| Largest tracked app data file | 16,347,488 bytes | `anime_catalog.json` |
| Legal assets total | 51,030 bytes / 49.834 KiB | Measured source bytes |
| Room schema exports total | 113,631 bytes / 110.968 KiB | Measured, schemas 5–11 |
| Native libraries | 60,292 bytes / 58.879 KiB | Measured APK; AndroidX/DataStore, four ABIs |

Release APK size was not measured: release signing was intentionally left untouched. A useful
near-term warning is either a >10% debug-APK increase from this baseline or a single new bundled
dataset adding >5 MiB compressed. This should be advisory until generic production scopes exist;
no failing CI threshold is justified yet.

## APK composition

Combined `classes*.dex` size is 61,408,128 bytes uncompressed and 18,208,246 bytes compressed.
The Anime catalog and alias asset are the next largest data entries. Legal notices plus the TMDB
logo add only about 66 KiB uncompressed and are not size concerns.

| Largest final APK entries | Uncompressed bytes | Compressed bytes |
|---|---:|---:|
| `classes.dex` | 42,428,620 | 10,716,435 |
| `assets/anime_catalog.json` | 16,347,488 | 2,425,226 |
| `classes4.dex` | 13,950,200 | 5,541,792 |
| `assets/anime_aliases.jsonl.gzip` | 3,140,724 | 3,140,724 |
| `classes5.dex` | 2,851,820 | 1,143,918 |
| `classes3.dex` | 2,021,812 | 759,340 |
| `resources.arsc` | 898,492 | 898,492 |
| green icon bitmap | 627,815 | 627,815 |
| yellow icon bitmap | 585,376 | 585,376 |
| blue icon bitmap | 577,814 | 577,814 |
| red icon bitmap | 551,412 | 551,412 |
| dark-glow icon bitmap | 419,618 | 419,618 |
| star icon bitmap | 397,129 | 397,129 |
| white-star icon bitmap | 359,879 | 359,879 |
| pastel-star icon bitmap | 353,572 | 353,572 |
| no-background star bitmap | 213,435 | 213,435 |
| podium 1 bitmap | 208,019 | 208,019 |
| podium 2 bitmap | 172,975 | 172,975 |
| podium 3 bitmap | 164,441 | 164,441 |
| `classes2.dex` | 155,676 | 46,761 |

The committed source totals are 19,539,732 bytes in app assets, 5,042,946 bytes in resources,
and 113,631 bytes in Room schema exports. The top 20 files within those paths are:

| File | Bytes |
|---|---:|
| `app/src/main/assets/anime_catalog.json` | 16,347,488 |
| `app/src/main/assets/anime_aliases.jsonl.gz` | 3,140,724 |
| `drawable-nodpi/rankoffline_green_source.png` | 627,815 |
| `drawable-nodpi/rankoffline_yellow_source.png` | 585,376 |
| `drawable-nodpi/rankoffline_blue_source.png` | 577,814 |
| `drawable-nodpi/rankoffline_red_source.png` | 551,412 |
| `drawable-nodpi/rankoffline_dark_glow_source.png` | 419,618 |
| `drawable-nodpi/rankoffline_star_source.png` | 397,129 |
| `drawable-nodpi/rankoffline_star_white_source.png` | 359,879 |
| `drawable-nodpi/rankoffline_star_pastel_source.png` | 353,572 |
| `drawable-nodpi/rankoffline_star_no_background_source.png` | 213,435 |
| `drawable-nodpi/rankoffline_podium_1.png` | 208,019 |
| `drawable-nodpi/rankoffline_podium_2.png` | 172,975 |
| `drawable-nodpi/rankoffline_podium_3.png` | 164,441 |
| `drawable-nodpi/rankoffline_dark_source.png` | 109,988 |
| `app/schemas/.../11.json` | 49,252 |
| `assets/legal/GPL-3.0.txt` | 35,149 |
| `values-ru/strings.xml` | 30,395 |
| `values-ja/strings.xml` | 25,167 |
| `values-fr/strings.xml` | 24,372 |

No raw AOD source dump, Open Library dump, TMDB/MangaDex cache, SQLite tooling cache, token,
secret, or generated generic production catalog is tracked.

## Anime alias efficiency and database model

The alias stream compresses 6.81:1. It costs 101.20 compressed bytes and 688.95 uncompressed
bytes per Anime with aliases, or 14.39 compressed bytes and 97.97 uncompressed bytes per alias.
The feature is compact in the APK but index-heavy after import, which is expected for search data.

| Synthetic SQLite object | Bytes | MiB |
|---|---:|---:|
| `anime_alias` table | 16,257,024 | 15.504 |
| alias primary-key autoindex `(anime_id,title)` | 11,100,160 | 10.586 |
| `idx_anime_alias_title` | 8,953,856 | 8.539 |
| `idx_anime_alias_anime` | 3,858,432 | 3.680 |
| `anime` table | 3,756,032 | 3.582 |
| `idx_anime_title` | 1,392,640 | 1.328 |
| `anime_genre` table + PK + genre index | 3,035,136 | 2.895 |
| `anime_studio` table + PK + studio index | 1,122,304 | 1.070 |
| Anime groups/member tables and indexes | 974,848 | 0.930 |
| Modeled ratings and score tables/indexes | 827,392 | 0.789 |

The explicit alias-title index is not used for leading-wildcard substring searches. The explicit
anime-ID index is also not selected because the composite primary-key index already starts with
`anime_id`. Together they cost 12,812,288 modeled bytes. Room is frozen in this block, so neither
is removed. Re-evaluate both with Android SQLite plans in a future schema revision; exact/prefix
queries could change the conclusion.

Generic tables are empty in the model because no production generic catalogs exist. Their measured
4 KiB-per-object allocation is structural overhead only and must not be extrapolated to a catalog.

## Search benchmark

Classification applies only to this desktop synthetic benchmark: FAST <25 ms, ACCEPTABLE 25–100
ms, WATCH 100–300 ms, PROBLEM >300 ms. p95 is the nearest-rank result over 30 warm iterations.

| Merge | Query | Rows (limit 200) | Median ms | p95 ms | Median class |
|---|---|---:|---:|---:|---|
| Off | empty, app SQL shape | 200 | 4.097 | 4.674 | FAST |
| Off | `a` | 200 | 18.394 | 29.687 | FAST |
| Off | `e` | 200 | 24.520 | 30.503 | FAST |
| Off | `Frieren` | 15 | 89.676 | 96.237 | ACCEPTABLE |
| Off | `Shingeki` | 41 | 95.062 | 100.049 | ACCEPTABLE |
| Off | `進撃の巨人` | 24 | 96.921 | 130.377 | ACCEPTABLE |
| Off | `Attack on Titan` | 42 | 100.627 | 109.715 | WATCH |
| Off | `One Piece` | 132 | 109.932 | 232.112 | WATCH |
| Off | nonexistent long string | 0 | 94.958 | 102.893 | ACCEPTABLE |
| On | `a` | 200 | 42.404 | 46.234 | ACCEPTABLE |
| On | `Shingeki` | 27 | 127.571 | 130.455 | WATCH |
| On | `Attack on Titan` | 30 | 132.166 | 135.262 | WATCH |
| On | nonexistent long string | 0 | 130.126 | 146.850 | WATCH |

The direct empty-query shape measured 3.657 ms median versus 4.097 ms for the app SQL shape. SQLite
short-circuits `query = ''`; the 0.44 ms difference does not justify duplicating the DAO query.
Short queries return 200 rows early and are not the worst case. Rare/nonexistent aliases require
more of the catalog to be examined.

### Query plans

| Query | Plan summary | Index/use | Risk | Action |
|---|---|---|---|---|
| Anime canonical/alias search | Scan Anime; correlated alias `EXISTS`; temp sort | Alias PK by `anime_id`; title indexes cannot help `%query%` | WATCH at current size | Keep behavior; device-measure in Block 20/future profiling |
| Anime empty search | Planner lists same branches; runtime short-circuits | No alias rows visited in measured fast path | Low | No change |
| Anime merged search | Scan groups and ungrouped Anime; member/alias correlated lookups; temp sort | Group PK, member-by-Anime index, alias PK used | WATCH | FTS/batched design only in a later schema if device data warrants |
| Anime ranking | Scan rated rows; PK join to Anime/category score; temp sort | Rating and score PKs used | Low for user-owned rated set | No change |
| Generic title/alias search | Media-type-prefixed item index; alias `EXISTS` by media ID | Scoped before alias lookup; leading wildcard remains | Unknown without production data | Measure once real catalogs exist |
| Generic ranking | Media-type-prefixed item index; rating PK; temp sort | SQL excludes unrated rows | User library sized, but unbounded | Defer bounded/paged result design |
| Anime/generic genre filter | Per-item `EXISTS`; child composite PK | Genre secondary index supports option generation | Low | No change |
| Generic credit filter | Per-item `EXISTS` by media ID/role/name | Credit PK; role/name index supports scoped options | Low | No change |
| Group-member lookup | Group/member PK plus Anime/item PK; position sort | Correct join indexes present | Low per detail view | No change |

## Startup and import

Logical startup order is preferences, removed-category cleanup, Anime catalog bootstrap, Anime alias
bootstrap, legacy cover metadata initialization, Anime count/filter options, then optional Manga,
Movie, Series, and Book bootstraps. Search/ranking flows observe only the active media. A single
300 ms debounced active query is used; switching media cancels the old `mapLatest`/`flatMapLatest`
work rather than launching five catalog searches.

Unchanged paths are cheap and run on `Dispatchers.IO`:

- Anime reads the 65-byte version marker and returns before the 15.59 MiB JSON parse.
- Anime aliases read the 425-byte manifest and compare release/hash metadata before gzip parsing.
- Generic item/group importers compare manifest metadata before decompression and parse JSONL in
  batches of 250 only when changed.
- Missing optional generic assets fail the two local `AssetManager.open` checks and return
  `UNAVAILABLE`; no catalog parse occurs.

Changed-import memory is bounded for generic JSONL catalogs and groups. Anime alias import streams
250 records at a time but keeps a 31,995-ID duplicate-detection set. The legacy Anime importer uses
`readText()` plus a full `JSONArray` for the 15.59 MiB catalog. This is a one-time version-change
path, not every startup, but its peak heap is not measured and JSON-to-JSONL conversion remains
explicitly deferred.

Build-time tooling follows its intended constraints: TMDB exports and Open Library dumps stream;
Open Library author names use a disk-backed SQLite index; MangaDex is bounded/cached; Anime alias
source JSONL streams, while the 15.59 MiB RankOffline catalog is loaded for its identity index. No
tool or provider was run against live data for this audit.

## Ranking and groups

- Anime individual/category ranking is a single SQL query; only `final_score IS NOT NULL` rows are
  considered and 0.0 remains rated. Category lookup is a join, not N+1.
- Anime group derivation is one CTE aggregate. The synthetic 2,196-group aggregate measured
  6.086 ms median / 7.279 ms p95 (FAST), and results remain non-persisted.
- Generic individual ranking filters rated rows in SQL and loads selected category scores once as
  a map. Sorting is in Kotlin and currently unbounded, but its cardinality is the user's rated set.
- Generic merged search/ranking calls `groupDetail()` for every matched group; each call loads group,
  member ratings, scores, members, and explicit rating, with another score query for category sort.
  This is a real N+1 architectural risk, but cannot be timed responsibly while generic production
  catalogs/groups are absent. Batch it before shipping a large grouped catalog rather than making
  an unmeasured query rewrite here.

## Covers

- Anime filenames and generic filenames are SHA-256 derived, never display-title based. Generic
  storage is media-type scoped; group files use a separate `groups` directory.
- Download counts are SQL `COUNT` queries scoped to the active media. Clear loads only rows that
  have local paths, deletes those files, then clears the scoped columns; it does not scan all items.
- Preload selection joins ratings in SQL with `final_score IS NOT NULL`, so 0.0 is included. It is
  explicit, individual-only, cancellable, and processes batches of at most two concurrent downloads.
- List composition never calls download; `showImages=false` avoids cover Flow collection and bitmap
  loading entirely.
- Decode and filesystem work run on IO. Rows remember their bitmap/path state and stable item IDs
  key lazy lists. The shared LRU uses at most about 1/16 of the process heap.

No representative downloaded covers exist locally, so a typical storage estimate would be invented.
The generic downloader's enforced maximum is 15 MiB per file, giving conservative hard upper bounds
of 1.465 GiB for 100, 14.648 GiB for 1,000, and 73.242 GiB for 5,000 covers. Real usage should be
far lower, but must be measured from a provider-respecting sample later. The legacy Anime downloader
does not enforce the generic 15 MiB stream limit and both list/viewer paths decode full-resolution
files before caching; bound Anime downloads and add sampled thumbnail decode in a focused future
cover block, not this audit.

## Compose and memory

Search/ranking/member lists use stable IDs as lazy keys. No large catalog sorting occurs directly
inside composition; visible option and category sorts are small and/or remembered. Cover decoding
is off-main and bitmap lifetime is LRU bounded. Per-row Anime cover Flows are created only for
composed rows and are disposed with them. No obvious coroutine/context leak was found: preload jobs
are owned by the ViewModel, cancellation stops future batches, and repositories hold application
context only.

`MainActivity` collects several top-level StateFlows, so settings/count changes can recompose a broad
screen subtree. Stable lazy keys and remembered expensive values limit the impact, and there is no
measurement showing a problem. No speculative state-hoisting refactor was made.

## Optimizations applied

1. **Preserve compressed alias bytes in the APK.** AGP 8.7.3 automatically expanded any `.gz`
   asset, stripped the suffix, and packaged `anime_aliases.jsonl` as 21,381,437 bytes. That both
   defeated streaming and made the importer-requested `.jsonl.gz` name absent. A build-only `Sync`
   stages identical bytes as `.jsonl.gzip`; SHA-256 remains
   `a5e371076170b513fd48078e40f28109dc13dc28b8ecca136f947600b8687a98` and the APK stores exactly
   3,140,724 bytes. Source catalog and manifest are unchanged.
2. **Deduplicate launcher icon bitmaps.** Ten `mipmap-nodpi` files were byte-identical to the icon
   picker/foreground `drawable-nodpi` files, costing 4,196,038 uncompressed and compressed APK
   bytes. Default mipmap resource aliases now reuse the drawable bitmaps; Android 26+ adaptive-icon
   resources remain unchanged.

Compressed ZIP entry payload fell by 4,539,846 bytes. The APK file itself fell by 6,039,001 bytes
(16.80%); the extra 1,499,155-byte difference is packaging/signing/layout overhead removed by the
fresh package rewrite and is not attributed to app payload changes.

No SQL, Room schema, scoring, network, provider, catalog content, or user-visible behavior changed.

## Future catalog size model

| Media | Production asset | Current measurement |
|---|---|---|
| Anime | Present | Actual: 40,742 items; 15.590 MiB JSON plus 2.995 MiB alias gzip |
| Manga | Absent | NOT YET MEASURABLE |
| Movie | Absent | NOT YET MEASURABLE |
| Series | Absent | NOT YET MEASURABLE |
| Book | Absent | NOT YET MEASURABLE |

Tiny tooling fixtures are not representative enough to extrapolate provider catalogs. Once a scope
candidate exists, model `compressed catalog bytes / emitted record × selected record count`, then
measure SQLite table/index amplification with the same script pattern. Do not pick record counts
from provider totals by default.

Bundling becomes undesirable when catalog additions dominate the current 28.513 MiB APK, create
multi-megabyte updates for metadata-only changes, or make first import/heap cost device-visible.
At that point a separately downloadable, versioned catalog is preferable, but remote sync/deltas are
out of scope here. Open Library's tens of millions of Works cannot be bundled blindly; Book requires
an explicit selection policy. TMDB Movie/Series and MangaDex catalogs likewise require deliberate,
provider-compliant bounds rather than full enumeration.

## Deferred recommendations

### P0 — before release

No open P0 from this audit. The broken/expanded Anime-alias packaging was P0 and is fixed. Proceed
to Block 20 regression, including targeted alias import/search tests on a connected device.

### P1 — before large production generic catalogs

- Measure first-run import/startup and alias search on representative low/mid/high Android devices.
- Add a bounded result policy or paging for generic search and Kotlin-sorted generic ranking.
- Replace generic merged group N+1 detail/rating loads with batch SQL/repository loading.
- Define explicit production scope and APK/update budgets for Manga, Movie, Series, and especially
  Book catalogs.
- Bound legacy Anime cover response size and decode sampled thumbnails; retain full local image only
  for the viewer.

### P2 — future scale

- If device data confirms search latency, design FTS/trigram/normalized search in a future Room
  version; do not add more B-tree title indexes for `%query%`.
- Re-evaluate redundant Anime/generic alias indexes with Android query plans in that future schema.
- Consider Anime JSON-to-streaming JSONL conversion only if first-import heap measurements justify it.
- Consider downloadable/versioned catalogs when bundled data growth makes install/update size
  impractical. NAS/server sync, deltas, Paging 3, image libraries, and macrobenchmarks remain separate
  product decisions.

## Release recommendation

Proceed to Block 20 full regression validation. The measured current payload has no unresolved size
blocker, Room remains 11, and both applied optimizations are packaging/resource-only. Performance
approval for future production generic catalogs is explicitly withheld until their scopes and real
artifacts can be measured.
