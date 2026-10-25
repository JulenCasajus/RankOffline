# RankOffline data-pack format (schema 1)

RankOffline Core contains no production catalog. Each data-only pack is identified by a stable,
lowercase `PackId`; the official IDs are `animes`, `mangas`, `movies`, `series`, and `books`.
Installed packs live at `files/packs/<pack_id>/`:

```text
manifest.json
catalog/
groups/
covers/
metadata/
.complete
```

`manifest.json` declares provider/provenance, minimum compatible RankOffline version, capabilities,
ordered filters, subtitle fields, a core-owned merge strategy, defaults, and the byte length and
SHA-256 of every payload. Defaults seed new settings only; user overrides are namespaced by PackId.
Personal item identity is `(pack_id, canonical_item_id)`. In schema 1 the Android catalog tables
still require canonical item and group IDs to be globally unique across simultaneously installed
packs; external pack installation is therefore deferred until collision rejection or composite
catalog keys are implemented.

```json
{
  "schemaVersion": 1,
  "packId": "series",
  "displayName": "Series",
  "version": "tmdb-2026-10-16",
  "minRankOfflineVersion": "0.2.0",
  "provider": {"id": "tmdb", "name": "TMDB"},
  "coverSources": [{
    "id": "tmdb_poster",
    "providerId": "tmdb",
    "urlTemplates": ["https://image.tmdb.org/t/p/w500/{reference}"],
    "allowedHosts": ["image.tmdb.org"],
    "attributionName": "TMDB",
    "attributionUrl": "https://www.themoviedb.org"
  }],
  "capabilities": {"search": true, "filters": true, "covers": true, "popularity": true, "merge": true, "ratings": true},
  "filters": [
    {"id": "genre", "field": "genre", "type": "multi_select", "order": 1},
    {"id": "year", "field": "year", "type": "range", "order": 2},
    {"id": "network", "field": "network", "type": "multi_select", "order": 3}
  ],
  "subtitle": ["genre", "year", "network"],
  "merge": {"enabled": true, "strategy": "GROUP_ID", "groupType": "SEASONS"},
  "defaults": {"showImages": true, "stickyScoreHeader": false, "mergeEnabled": false, "oneCoverPerGroup": true, "ratingMode": "simple"},
  "integrity": {"installedBytes": 123, "files": [{"path": "catalog/series.jsonl.gzip", "bytes": 123, "sha256": "<64 lowercase hex>"}]}
}
```

The example is illustrative, not a declaration of the current or forthcoming shipped defaults.
The current official trusted index on `main` declares Books `showImages=false`,
`stickyScoreHeader=true` across all five packs, and Series
`mergeEnabled=true` with `oneCoverPerGroup=true`. These defaults were
included in debug.6; the stable APK still requires its own matching
seven-asset candidate verification. Existing user overrides take precedence.

Supported filter types are `multi_select` and `range`. Supported current merge strategies are
`NONE`, `GROUP_ID`, `COLLECTION_ID`, and `RELATION_GRAPH`; unknown values reject the pack safely.
Packs cannot contain absolute/traversal paths or executable `.dex`, `.jar`, `.class`, `.so`, or
`.apk` payloads. Downloads are size/hash checked, extracted to a temporary directory, and promoted
atomically only after validation. `covers/` is optional and `metadata/cover_index.json` maps a
canonical item/group ID to a declared local cover file. After verification, extracted images are a
user-removable per-pack cover cache; the verified `.pack` archive is retained separately and normal
startup recovery does not restore covers the user deleted.

`coverSources` is optional for backward compatibility. It declaratively maps a catalog provider to
one or more HTTPS templates using only `{reference}` and `{providerId}`. Hosts and attribution are
explicit. A catalog `remoteCoverRef` may alternatively be a schema-1 JSON string containing a
`sourceId`, stable `identity`, and ordered direct/fallback `urls`. The core rejects non-HTTPS URLs,
IP/local destinations, undeclared hosts, traversal, unsafe redirects, and executable content; a
pack never supplies executable resolver code or credentials. Older official artifacts continue
through their restricted built-in compatibility adapters.

After a pack's catalog and groups are verified/imported, it can be browsed and rated without
waiting for optional initial Top-100 cover acquisition. `Show Images` controls this
installation-time, user-private HTTP cover fetch; it never enables online title search.
The cover cache is counted and deleted per pack, including any properly licensed files
bundled inside the pack. Manual Top-N cover retrieval remains available.

Deleting a pack purges its catalog, groups, covers, cache, ratings, notes, ranking state, saved
filters, and pack settings while leaving global language/theme/onboarding state intact. Future
third-party installation needs only an obtain/validate/install/register UI; it must remain data-only.

Release tooling creates `RankOffline-<Name>-v0.2.0.pack` ZIP-compatible artifacts plus the trusted
`category_pack_index.json`. Upload those files beside the APK in the GitHub `v0.2.0` release.

The trusted index has a separate `minimumDatabaseVersion`. It means the oldest Room schema that
contains the catalog/group tables required by that artifact; it is not the current Room version and
does not replace `schemaVersion`, catalog `formatVersion`, group `formatVersion`, or
`minRankOfflineVersion`. The normalized schema-1 official packs require database 13; Room 14 adds
PackId-scoped personal ratings without changing the catalog/group contract.

## Precomputed groups

Group discovery is a release-time concern. A group JSONL record contains the stable provider group
identity, explicit members, `representativeMediaId`, `memberCount`, `popularity`, and
`popularityRank`. Series use the TMDB parent-show ID, Movies use the explicit TMDB collection ID,
and Manga use the deterministic connected component of allowed MangaDex variant relations.
Books use a conservative Work-level series-group artifact built from explicit/verified
relationships rather than treating editions as independent catalog entries.
Android imports these values verbatim and performs indexed, paginated group queries; it does not
infer relationships from titles, contact a provider, or rebuild membership after installation.

Current normalized payload formats are catalog format 1 and group format 2. A pack reaches READY
only after payload hashes/counts and imported group counts have been verified.
