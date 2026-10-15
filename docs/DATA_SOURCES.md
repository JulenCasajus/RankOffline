# Data sources and provider constraints

RankOffline combines independently licensed/provider-controlled metadata. The
application's GPL license does not relicense catalogs, images, API responses,
or other third-party material. Provider requirements must be reviewed again
before producing or publishing a production catalog because policies can
change.

## Source matrix

| Experience | Primary source | Catalog unit | Grouping authority | Runtime cover source |
| --- | --- | --- | --- | --- |
| Anime | AnimeAPI | Anime entry | Existing conservative AniList relations + normalized format | Existing stored URL; AniList/Jikan fallback |
| Manga | MangaDex | Manga work/title | Verified variant/edition relations only | MangaDex cover-art CDN |
| Series | TMDb | Numbered TV season | Parent TMDb series ID | TMDb image CDN |
| Movies | TMDb | Movie | Canonical TMDb collection ID | TMDb image CDN |
| Books | Open Library | Work | None initially; Work ID only if Editions are later loaded | Open Library Covers API |

No provider popularity metric is comparable across media types. It is used only
to sort within the active catalog.

## Anime: AnimeAPI, AniList, and Jikan

AnimeAPI remains the master catalog and the existing 40,742-entry asset remains
unchanged during the multi-media architecture work. AniList currently enriches
known IDs with popularity, metadata, relations, and cover lookup; Jikan is a
cover fallback by MAL ID.

This integration carries a material permission risk. The current
[AniList API Terms of Use](https://docs.anilist.co/guide/terms-of-use) prohibit
use in competing, non-complementary anime/manga list or tracker services and
also restrict mass collection. RankOffline should obtain authorization for its
continued current AniList use. The multi-media project must not expand AniList
usage, add AniList Manga, or introduce new AniList endpoints without a separate
decision.

This block does not remove current published behavior. Any future replacement
must preserve stable Anime IDs, catalog coverage, popularity, relations, cover
fallbacks, and all user data.

Anime title search is separately enriched from the pinned `2026-42` release of
[cedya77/anime-offline-database](https://github.com/cedya77/anime-offline-database),
the active continuation of
[manami-project/anime-offline-database](https://github.com/manami-project/anime-offline-database).
The offline generator uses only exact MAL/AniList IDs from `sources`, `title`,
and `synonyms`; it performs no title matching and makes no additional AniList
requests. The derived `anime_aliases` asset is kept separate from the AnimeAPI
catalog under ODbL 1.0/DbCL 1.0 provenance. Synonyms do not carry reliable
language labels, so stored alias language is null; coverage is source-provided
and no translations are generated.

## TMDb: Movies and Series

TMDb supplies movie, TV-series, season, collection, and poster metadata.

### Generation

The [TMDb daily ID exports](https://developer.themoviedb.org/docs/daily-id-exports)
are discovery/index files, not complete metadata dumps. Offline tooling will
select candidate IDs and enrich them through details endpoints using a bearer
token provided only as `TMDB_READ_ACCESS_TOKEN`. The token must never enter
source, Gradle configuration, Android resources, generated catalogs, logs, or
the APK. TMDb documents bearer application authentication in its
[authentication guide](https://developer.themoviedb.org/docs/authentication-application).

Tooling requirements:

- cache every successful detail response;
- resume without repeating completed requests;
- limit request rate and honor HTTP 429/`Retry-After`;
- use bounded retries/backoff for transient failures;
- support small `--limit` and `--dry-run` validation;
- never run network generation during Gradle builds or CI.

Movies store the canonical collection ID/name when present. Series generation
creates numbered-season items from the parent series and excludes season zero;
it does not enumerate episodes.

The offline Movie generator reads explicit IDs or a bounded local daily export,
then requests deterministic English details with appended credits and
alternative titles. It emits the generic Movie catalog plus a separate
canonical-collection artifact; it does not infer broader universes. Publishing
Movie data in the application still requires the TMDb attribution described
below.

### Images and attribution

Only `poster_path` (and a collection poster reference when relevant) is stored.
At runtime the app constructs an HTTPS URL using TMDb's documented image
configuration; `w500` is the initial intended poster size. TMDb's
[image guide](https://developer.themoviedb.org/docs/image-basics) documents
this URL model.

TMDb requires attribution in an About/Credits area, the acknowledgement:

> This product uses the TMDB API but is not endorsed or certified by TMDB.

and an approved, unmodified TMDb logo. See the official
[TMDb FAQ](https://developer.themoviedb.org/docs/faq). No logo should be
redrawn or fabricated. An approved asset is a release-readiness requirement if
it is not safely acquired during implementation.

TMDb licensing/terms, including the distinction between non-commercial and
commercial API use, must be checked for the intended distribution before
publication.

## MangaDex: Manga

MangaDex is a read-only metadata source for RankOffline. There is no account
flow, reader, chapter-page download, reading progress, or MangaDex rating sync.
A MangaDex manga resource represents the RankOffline item; chapters and volumes
are subordinate data and are not synthesized as items.

The offline generator accepts explicit UUIDs or an explicitly bounded listing
request, expands only cover/author/artist metadata, and caches successful public
API responses for resumable runs. Production catalog selection remains bounded
pending a provider-compliant enumeration strategy; it never scrapes HTML or
uses authentication/user synchronization.

The generator follows the current
[official MangaDex API documentation](https://api.mangadex.org/docs/) and
OpenAPI schema, including the documented 100-item maximum list page. Discovery
requires an explicit bound. Since safe complete enumeration is not established,
the tool reports that limitation rather than scraping HTML or evading limits.

The official documentation currently describes a global request allowance and
HTTP 429 enforcement in its
[limitations page](https://api.mangadex.org/docs/2-limitations/). The generator
must identify RankOffline with an appropriate User-Agent, avoid abusive
request patterns, honor endpoint headers/cooldowns, cache responses, and resume.
It may not distribute load over multiple IP addresses to bypass limits.

The current relation enumeration includes specific variant relationships such
as `monochrome`, `colored`, `preserialization`, `serialization`, and
`alternate_version`, as well as broader relationships such as prequel/sequel,
franchise, and shared universe. Only relations verified to mean editions or
variants of substantially the same work may drive the initial merge mode.
Broader work/franchise relations remain excluded.

Variant artifacts use connected components across only those five allowlisted
relations, require at least two selected catalog items, and use stable hashes of
sorted MangaDex UUIDs. MangaDex attribution remains required for release.

Store the manga UUID, canonical/alternative titles as justified, year, status,
original language, demographic, genres/tags, authors/artists, primary cover-art
filename, and conservative variant edges. Covers use the UUID and filename and
prefer the 512px thumbnail where appropriate.

MangaDex requires service credit. RankOffline treats the provider's current
no-advertising/no-paid-service condition as a hard integration constraint, and
the exact acceptable-use/monetization policy must be re-verified immediately
before production integration and release. RankOffline currently has no ads,
paid service, accounts, or telemetry; those constraints must not be relaxed
without first reassessing MangaDex permission.

## Open Library: Books

The initial Book catalog is Work-level: one Open Library Work is one RankOffline
item. Editions and chapters are deliberately excluded, no Book group artifact
is generated, and the UI hides Merge editions.

Open Library distinguishes Works from Editions and publishes monthly bulk
files. Its [Developer Center](https://openlibrary.org/developers) and
[API guidance](https://openlibrary.org/developers/api) direct bulk imports to
data dumps rather than using low-volume APIs as a catalog backend. The tooling
therefore consumes local monthly files from the
[data dumps page](https://openlibrary.org/developers/dumps), streaming gzip
records without loading a full dump into memory.

The offline generator streams a local Works dump and can optionally build and
reuse a disk-backed SQLite index from a local Authors dump to resolve canonical
`AUTHOR` credits. Work subjects become bounded generic genre/filter metadata,
and the first valid Work Cover ID is stored for later explicit, on-demand cover
retrieval. Catalog records retain the deterministic Works dump order so full
generation remains streaming and approximately constant-memory. The tool never
crawls Open Library or the Covers API.

Tooling must:

- require an explicit local Works dump path and accept an optional local Authors
  dump/index;
- never automatically download multi-gigabyte dumps without an explicit flag;
- use a streaming/two-pass join or a bounded on-disk index when authors are
  required;
- keep Editions, ratings, and reading-log inputs out of the initial generator;
- explain missing inputs and stop instead of falling back to mass API calls;
- validate with tiny compressed fixtures before production processing.

Book popularity is not invented. If exposed, its documented formula derives
from source counts such as reading-log or rating counts and retains useful raw
counts. It is only meaningful inside Books.

Work records do not provide a complete set of translated Edition titles. The
initial catalog therefore searches the canonical Work title plus conservative
Work-level aliases such as title and subtitle combined. A future offline
Edition pass may enrich aliases without turning Editions into rankable items.

Cover ID is the preferred stored reference. Explicit user download resolves a
Large HTTPS cover URL and uses `default=false` so a missing image returns a real
failure. Open Library explicitly asks clients not to crawl the Covers API; see
the [Covers API guidance](https://openlibrary.org/dev/docs/api/covers).

Open Library's [licensing note](https://openlibrary.org/developers/licensing)
states that Internet Archive asserts no new proprietary rights over the
database while warning that underlying contributions may carry existing rights.
About/licence text must credit Open Library/Internet Archive without claiming
that its data is covered by RankOffline's GPL license.

## Catalog production and legal checkpoints

Production generation is pending until each provider tool passes deterministic
fixture tests and the user approves scope after measurements. Caches, raw dumps,
tokens, and multi-gigabyte generated files are not repository artifacts.

Before a production catalog or release:

1. re-read the current provider terms and API documentation;
2. confirm that catalog breadth and redistribution are permitted;
3. record entry count, source date, generator version, and asset hash;
4. measure raw/compressed size, Room import duration, peak memory, and search
   latency on a representative device;
5. include required attribution and approved branding assets;
6. confirm that no credential or user data is embedded;
7. retain source-specific licensing separately from RankOffline GPL-3.0-only.

Current unresolved release items include AniList authorization, the approved
TMDb logo asset, MangaDex enumeration/policy verification, provider catalog
scope, and whether Book Editions have sufficient product value to include.
