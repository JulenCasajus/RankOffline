# Production catalog status

> **Current 0.2 preview (2026-10-25):** The detailed "Block 21" measurements below
> document historical build stages, not the exact current installable pack sizes.
> Published `v0.2.0-debug.5` distributes five data-only `.pack` archives and
> a matching index, with **zero bundled cover-image binaries**. The current
> production Books pack contains 50,000 Work-level items, 13 normalized genre
> labels, and a conservative initial **2 series groups / 10 members**. The
> latest Books archive is approximately 7.49 MB; previous Book catalog/gzip
> measurements below reflect an earlier snapshot without these groups.
> Installing a pack may perform per-device initial Top-100 cover retrieval
> only when that pack's Show Images setting is enabled. Defaults are being
> finalized for debug.6 (Books images OFF; Sticky Score Header ON for all;
> Series Merge ON when One Cover per Group defaults ON).
>
> **No external image rights are inferred** from provider cover URLs.
> Archive/index hashes and sizes must be regenerated and rechecked together
> for the eventual stable `v0.2.0` release.

Block 21 targets one provider-ranked snapshot per media type. Counts and byte
sizes are recorded here only after deterministic assets exist; missing inputs
are reported rather than replaced with fixtures or estimates.

| Media | Requested snapshot | Ranking rule | Current status |
|---|---:|---|---|
| Anime | 40,742 | Existing RankOffline popularity | Existing asset retained unchanged |
| Manga | 10,000 | MangaDex `followedCount DESC`, cached provider order | Complete: 10,000 items and 206 conservative variant groups |
| Movies | 50,000 | TMDB daily-export popularity DESC, ID tie-break | Complete: 50,000 items and 2,156 collection groups |
| Series | 10,000 parents + numbered seasons | TMDB parent popularity DESC, then season number and ID | Complete: 10,000 parents, 54,724 season items, and 6,181 season groups |
| Books | 50,000 Works | Open Library `sort=readinglog` (`readinglog_count DESC`), Work ID tie-break | Complete: 50,000 Work-level Book items, no Edition items or groups |

## Generator behavior

- TMDB daily exports are sorted before Top-N selection. Successful metadata
  responses remain in the ignored disk cache and generation is resumable.
- MangaDex discovery uses the official bounded list endpoint, excludes the
  `pornographic` content rating, stores deterministic ranks, and caches both
  ordered discovery pages and resource/relation responses.
- Open Library popularity selection is separately cached and writes a compact
  ranked Work-ID JSONL file. Full metadata comes from the streaming Works and
  Authors dumps; there is no 50,000-Work API crawl. Search pagination uses
  bounded `readinglog_count` windows after Open Library's 10,000-result deep
  offset limit, including complete boundary-tie buckets before deterministic
  Work-ID tie-breaking.

## Bundled covers

No Top-100 image bundle is accepted until redistribution rights are confirmed
per provider. Metadata/data licences do not by themselves license cover art.
Open Library explicitly cautions that underlying image rights can differ and
asks clients not to crawl the Covers service. TMDB permits attributed image/API
use under its current non-commercial terms but limits cached API content to six
months, which a static APK bundle cannot automatically enforce. MangaDex and
Anime artwork also require an explicit redistribution determination. Existing
user-triggered remote cover download remains unchanged.

## Reproduction inputs

- `TMDB_READ_ACCESS_TOKEN` in the local environment plus explicit dated Movie
  and TV daily-export files.
- Open Library Works and Authors dumps dated 2026-09-30. They remain only under
  ignored `tools/.cache/`; the Authors dump is indexed locally for repeatable
  streaming enrichment.
- A documented cover-art redistribution decision for each provider before any
  cover image is committed.

No raw provider dump, token, cache, or temporary whitelist is committed.

## TMDB production snapshot — 2026-10-16

Both catalogs use the official Movie and TV daily ID exports dated 2026-10-16.
The Movie export contained 1,259,405 rows (1,194,591 eligible after excluding
64,814 video records); the TV export contained 234,183 rows. The generators
selected by export popularity descending with numeric TMDB ID as the stable
tie-break. Standard daily exports contained no adult rows; the separate adult
exports were not used.

Two Movie IDs in the selected window returned HTTP 404 during enrichment. The
generator deterministically filled those positions from the next ranked export
candidates and assigned contiguous snapshot ranks 1–50,000. The final resume
pass reused 49,998 cached Movie responses, fetched 100 fallback candidates, and
reported the same two unavailable IDs. TV enrichment fetched 10,100 bounded
candidates without unavailable IDs, selected exactly the first 10,000 valid
parents, and generated every numbered season while excluding Season 0.

| Asset | Records | Compressed bytes | Uncompressed JSONL bytes | SHA-256 |
|---|---:|---:|---:|---|
| `movie_catalog.jsonl.gz` | 50,000 | 9,210,758 | 43,961,054 | `fb423a4725eecbcf63939b4348a29df602f6e0209054689fcb7923bdc6982961` |
| `movie_groups.jsonl.gz` | 2,156 | 165,984 | 1,007,042 | `0efb16f9134498f8f30c36f7c4f98cd0f0f5c2f2f9a5bc3c935c890b333b41e5` |
| `series_catalog.jsonl.gz` | 54,724 | 4,077,048 | 46,850,312 | `e7588e8df1f7dc2c739de591f774861f47ef43594e9ec4efe4614807ccd51910` |
| `series_groups.jsonl.gz` | 6,181 | 617,293 | 4,778,515 | `877417a4f8dc1511f9f7869085dfaa8aed7055f3e4df4bbd58090dca96f42d08` |

The four compressed TMDB data assets total 13,941,781 bytes; their manifests
add 750 bytes. All 54,724 Series items have unique stable identities, all
10,000 selected parents have at least one numbered season, and the validated
Season 0 count is zero. Raw exports and the response cache remain under the
ignored `tools/.cache/` tree. Their local input hashes are
`64fa09b36d5ced9ea2a4b77ccd80fb95fdcd645398f87bc4201bf6f665e773b2`
(Movie) and
`de9891d223c91be8e73bb571af5c3dc8cfd1c10b06f854d2070edb71336e1f80`
(TV). No poster image binaries were downloaded or bundled.

The resulting debug APK is 44,060,118 bytes (42.019 MiB), an increase of
14,099,484 bytes (13.446 MiB) over the provisional 29,960,634-byte APK. The
current Anime, Anime-alias, Movie, and Series production assets and manifests
total 33,431,233 source-tree bytes. Manga and Book production assets remain
pending and are not included in those figures.

Validation for this phase covered 26 focused offline TMDB tooling tests and 12
relevant JVM catalog/integration tests. `assembleDebug` passed. The APK-staged
Movie and Series `.jsonl.gzip` entries were verified byte-for-byte against all
four committed `.jsonl.gz` inputs, preserving each manifest SHA-256.

## MangaDex production snapshot — 2026-10-16

The bounded MangaDex discovery snapshot contains exactly 10,000 unique Work-level
Manga resources ordered by provider `followedCount DESC`. Its 100 cached discovery
pages and 10,000 cached relation responses were complete and valid when production
generation resumed, so the final run made no new MangaDex requests. The ordered
provider-ID snapshot SHA-256 is
`ee503b71b0382c7dbd81a944a30be59ad2e4ac6744bf8dfded77ee5ec6808264`.

Variant groups use only `alternate_version`, `colored`, `monochrome`,
`preserialization`, and `serialization`. The production snapshot contains 56,
40, 40, 157, and 157 selected-to-selected directed edges of those respective
types. It generated 206 deterministic groups containing 428 distinct members.
The generator ignored 1,720 selected-to-selected edges of non-allowlisted types
and five raw provider self-relations; neither produced a merge, self-membership,
or duplicate membership.

| Asset | Records | Compressed bytes | Uncompressed JSONL bytes | SHA-256 |
|---|---:|---:|---:|---|
| `manga_catalog.jsonl.gz` | 10,000 | 2,738,994 | 10,564,086 | `91bd01d47dca26f1cdebcc4b234219ec9d9b73505f1a4622aeac0aabe36d704b` |
| `manga_groups.jsonl.gz` | 206 | 27,808 | 131,022 | `203aab9948d3b59d69b6fcdb2edb57b6f74b16d6d15b39fa67a5f42765c41e5b` |

The compressed Manga data and two manifests total 2,765,004 bytes (2.637 MiB).
The current Anime, Anime-alias, Manga, Movie, and Series production assets and
manifests total 36,196,237 source-tree bytes. A second generation from the same
cache produced byte-identical catalogs and manifests. No chapter/volume items or
cover image binaries are included.

With Manga included, the debug APK is 46,799,488 bytes (44.631 MiB), an increase
of 2,739,370 bytes (2.612 MiB) over the 44,060,118-byte TMDB-phase baseline.
Validation covered 13 focused offline MangaDex tooling tests and six JVM catalog
format tests; `assembleDebug` and `assembleDebugAndroidTest` passed. The APK-staged
Manga `.jsonl.gzip` entries and both manifests were verified byte-for-byte against
their source assets. No device was connected, so the already-compiled Manga and
generic-importer instrumentation remains pending rather than starting an emulator.

## Open Library production snapshot — 2026-09-30

The ranked candidate snapshot was obtained from the official Open Library Search
API with `sort=readinglog`. Sixty cached bounded responses yielded 50,100 unique
Work candidates ordered by `readinglog_count DESC`, with Work ID as the stable
tie-break. Full metadata and author names came from the matching official
2026-09-30 dumps published by Open Library/Internet Archive:

- `ol_dump_works_2026-09-30.txt.gz`: 4,073,320,823 compressed bytes.
- `ol_dump_authors_2026-09-30.txt.gz`: 782,352,949 compressed bytes.

The Works scan resolved 50,095 of the 50,100 ranked candidates. Five unavailable
Works inside the requested Top 50,000 were replaced deterministically by the next
five resolvable candidates from the same snapshot. Final production ranks are
contiguous 1–50,000. The Authors index processed 15,444,099 author records;
49,669 Books have at least one resolved author, comprising 64,104 author credits,
while 331 Works have no resolvable author in the snapshot. Missing optional author
metadata does not invalidate an otherwise usable Work.

| Asset | Records | Compressed bytes | Uncompressed JSONL bytes | SHA-256 |
|---|---:|---:|---:|---|
| `book_catalog.jsonl.gz` | 50,000 | 6,312,242 | 32,450,603 | `4c887dd85a139d782a938b1e4fc8cc347ec2a3ea3f2ca9ffbdedbdc2a16e489e` |

The manifest adds 209 bytes, for 6,312,451 Book production bytes (6.020 MiB).
All 50,000 provider identities are unique Open Library Work IDs ending in `W`;
there are zero Edition identities and no Book-group asset. A second run from the
same cached inputs produced byte-identical gzip and manifest output. The catalog
contains 45,459 cover references only; no Covers API crawl or image binary was
performed.

With Books included, the Anime, aliases, Manga, Movie, Series, and Book production
assets and manifests total 42,508,688 source-tree bytes (40.539 MiB). The debug APK
is 52,982,301 bytes (50.528 MiB), an increase of 6,182,813 bytes (5.896 MiB) over
the 46,799,488-byte Manga-phase baseline. Its Book asset is byte-identical to the
source gzip and carries the manifest hash above.

Validation covered all 17 focused Open Library tooling tests, the complete 82-test
offline generator/tooling suite, and 153 JVM tests. `lintDebug`, `assembleDebug`,
and `assembleDebugAndroidTest` passed. No Android device was connected, so the
already-compiled connected instrumentation remains pending.

## Final Block 21 catalog-pack validation — 2026-10-18

All five production snapshots are represented by deterministic ZIP packs and a packaged immutable
index. The final offline tooling inventory contains 83 passing tests (the prior 82 plus the pack
reproducibility test). Room 12 decouples user-owned rating parents from removable catalog rows;
Room 13 stores the publication-time group representative, member count, and popularity summary so
merged browsing never has to rediscover or aggregate group structure on the device;
migration and connected repository tests verify exact scores, score detail, notes, status, profile
IDs and group ratings survive catalog removal and reconnect by canonical identity after reinstall.

The current debug APK intentionally retains the catalogs until an official HTTPS pack release is
available. After the device-test fixes it measures 53,149,765 bytes (50.688 MiB). The verified downloadable configuration,
built with `rankoffline.bundleProductionCatalogs=false`, contains the pack index but no production
catalog assets and measures 24,527,608 bytes (23.391 MiB), a reduction of 28,588,481 bytes
(27.264 MiB). No cover images were bundled and the Top-100 redistribution decision remains open.

Fresh-start validation also showed that importing all production catalogs can take several minutes
on an emulator. Onboarding and navigation are therefore made ready after preferences and pack state
load; category imports remain on the IO path and expose an honest per-category `LOADING` state in
the background instead of blocking the first-launch surface.

The post-device-test startup path now publishes the first UI state before legacy cover migration
and catalog imports, with `RankOfflineStartup` timing markers for first-render readiness and
background completion. Cover binaries remain unbundled: the image-enabled onboarding choice uses a
bounded, two-worker Top-100 private-cache warm-up after catalogs become available. Catalog cover
references cover Manga 100%, Movies 98.30%, Series seasons 96.07%, and Books 90.92%; within Top-100
popularity ranks the counts are Manga 100/100, Movies 100/100, and Books 98/100. Missing images are
not fabricated, and Open Library's no-crawl guidance prevents adding a bulk fallback crawl.
