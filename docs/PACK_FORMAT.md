# RankOffline data pack format (v0.2.0 development)

> **Draft specification**, based on the v0.2.0 pack parser and importers. The stable v0.1.3 app does not support these packs. The v0.2.0 app currently distributes **official** pack IDs; third-party installation from the app UI is not yet implemented.

A `.pack` is a **ZIP archive containing data only**, never executable Kotlin/Java/native code. It has one root `manifest.json` and declared payload files.

## Archive layout

```text
my-pack.pack
├── manifest.json
├── catalog/
│   ├── book_catalog.manifest.json
│   └── book_catalog.jsonl.gzip
├── groups/                     # only if merge is supported
│   ├── movie_groups.manifest.json
│   └── movie_groups.jsonl.gzip
├── metadata/                   # optional, e.g. cover_index.json
└── covers/                     # optional licensed assets
```

The exact catalog/group basenames are currently determined by the official media-type adapter. **A custom pack ID alone does not register a new catalog adapter.**

## Required root manifest

`PackManifestParser` requires these fields:

| Field | Type / rule |
|---|---|
| `schemaVersion` | integer `1` |
| `packId` | `[a-z][a-z0-9-]{1,63}`, stable, e.g. `my-books` |
| `displayName`, `version`, `minRankOfflineVersion` | nonempty strings |
| `provider` | `{ "id": "...", "name": "..." }` (`id` uses lowercase letters, digits, underscore) |
| `catalog` | nonempty array of declared `catalog/` paths |
| `groups`, `covers` | arrays, may be empty |
| `capabilities` | six booleans: `search`, `filters`, `covers`, `popularity`, `merge`, `ratings` |
| `filters` | array of `{ "id", "field", "type", "order" }`; type `multi_select` or `range` |
| `subtitle` | array of fields declared in `filters` |
| `merge` | `{ "enabled": bool, "strategy": "NONE|GROUP_ID|COLLECTION_ID|RELATION_GRAPH", "groupType": string? }` |
| `defaults` | `showImages`, `stickyScoreHeader`, `mergeEnabled` booleans and `ratingMode` = `simple` / `elaborated` |
| `integrity` | `installedBytes` and `files` array, each `{ "path", "bytes", "sha256" }` |

Example *shape* (fill real lengths and hashes before packaging; for a compatible generic Books catalog):

```json
{
  "schemaVersion": 1,
  "packId": "my-books",
  "displayName": "My Books",
  "version": "1.0.0",
  "minRankOfflineVersion": "0.2.0",
  "provider": {"id": "personal", "name": "My Collection"},
  "catalog": [
    "catalog/book_catalog.manifest.json",
    "catalog/book_catalog.jsonl.gzip"
  ],
  "groups": [],
  "covers": [],
  "capabilities": {
    "search": true, "filters": false, "covers": false,
    "popularity": false, "merge": false, "ratings": true
  },
  "filters": [],
  "subtitle": [],
  "merge": {"enabled": false, "strategy": "NONE", "groupType": ""},
  "defaults": {
    "showImages": false, "stickyScoreHeader": false,
    "mergeEnabled": false, "ratingMode": "simple"
  },
  "integrity": {
    "installedBytes": 12345,
    "files": [
      {"path": "catalog/book_catalog.manifest.json", "bytes": 345, "sha256": "REPLACE_WITH_REAL_64_CHAR_HEX_SHA256"},
      {"path": "catalog/book_catalog.jsonl.gzip", "bytes": 12000, "sha256": "REPLACE_WITH_REAL_64_CHAR_HEX_SHA256"}
    ]
  }
}
```

**This is a schema illustration, not an installable archive.** The dummy values must be replaced. End-user import of `my-books` is not supported in current builds.

`integrity.installedBytes` must equal the sum of declared file bytes; all referenced paths must be in `integrity.files`. Paths must be relative, inside `catalog/`, `groups/`, `metadata/`, or `covers/`, with no empty, `.`, `..`, or backslash segments. `.dex`, `.jar`, `.class`, `.so`, and `.apk` are prohibited.

The official *distribution index* `category_pack_index.json` (embedded in the debug APK) also declares archive name/bytes/SHA-256, installed bytes, supported format and minimum DB version. Merely hosting an archive does **not** cause an already built APK to discover it.

## Generic catalog data

For Manga/Movie/Series/Books the v0.2.0 generic catalog format is **v1**:

- `<type>_catalog.jsonl.gzip`: one JSON record per line.
- `<type>_catalog.manifest.json`: `formatVersion`, singular `mediaType`, `provider`, `catalogVersion`, `sha256`, `itemCount` (optional `generatedAt`).
- Records require stable `id`, `mediaType`, `provider`, `providerId`, `title`; optional date, format, language, popularity, cover ref, `genres`, `credits`, `aliases`.

IDs must start with the singular media-type prefix, e.g. `book:`, `movie:`. The record's `provider` and `mediaType` must match the catalog manifest.

`sha256` in a catalog/group manifest is the checksum required by its importer; use the current generators for its exact compressed-vs-uncompressed convention. File SHA in the root manifest is for the **extracted payload file bytes**.

### Explicit precomputed Merge (generic format v2)

`<type>_groups.manifest.json` includes `formatVersion: 2`, `mediaType`, `provider`, `catalogVersion`, `sha256`, `groupCount`, and `membershipCount`.

Each JSONL record supplies `id`, `mediaType`, `groupType`, `provider`, `providerGroupId`, `displayTitle`, `representativeMediaId`, `memberCount`, optional popularity fields/cover reference, and an ordered `members` array of `{ "mediaId", "position" }`. Require at least two unique members, and the representative must be one of them.

The official mappings are `SEASONS` (Series), `COLLECTION` (Movies), and `VARIANTS` (Manga). Do not invent fuzzy title relationships in the Android runtime.

### Anime compatibility exception

Anime uses an existing JSON catalog and an **Anime group graph v1**: group records use `id`, `type`, `rootAnimeId`, `displayTitle`, and ordered `members` of `{ "animeId", "position" }`. `AnimeGroupCatalogImporter` preserves legacy Anime ID/rating semantics. It is an adapter under the common pack lifecycle, not a second pack system.

## Pack verification and installation

1. Download archive using official index expectations (archive byte length/SHA-256).
2. Validate manifest and allowlisted ZIP entries.
3. Extract to private staging, checking every payload's size/SHA-256.
4. Atomically promote with previous version retained for rollback.
5. Import catalog and precomputed groups to Room; verify count/membership consistency.
6. Finalize and mark runtime READY; on failure, roll back safely.

There is no execution of pack-supplied scripts, and no runtime group discovery.

See [CUSTOM_PACKS.md](CUSTOM_PACKS.md) for the actual external-pack support status.
