# Pack manifest specification

This document describes the schema-2 manifest accepted by the current RankOffline
engine. A pack is a validated data archive; it cannot execute code. The Kotlin parser,
record validators and installer are the definitive implementation of this contract.

## Identity and versions

`manifest.json` is at the ZIP root. Its top-level keys are:

```text
schemaVersion, packId, version, minRankOfflineVersion, displayName,
presentation, capabilities, fields, filters, subtitle, merge, defaults,
rating, coverSources, catalog, groups, covers, attribution, integrity
```

The parser rejects unknown top-level properties. `schemaVersion` is the integer `2`.
`packId` is a positive JSON integer represented as a Kotlin `Long`; zero is reserved for
the virtual RankOffline All view. `version` identifies pack content, while
`minRankOfflineVersion` states the minimum compatible application version. `displayName`
is the fallback visible name. An item or group ID is unique within its pack; its
complete identity includes `packId`.

Pack identity must remain stable across updates if ratings are to remain attached to the
same items. Archive integrity does not prove publisher identity. A distribution index
must provide a trusted archive URL, size and SHA-256 for the package it advertises.

## Presentation and capabilities

`presentation` contains localized `names`, an `accentColor` in `#RRGGBB` form, and
optional `shareBackgrounds.topSeven` and `shareBackgrounds.topTen` paths. Share
backgrounds are integrity-declared images under `metadata/`, each limited to 8 MiB. When
absent, the app uses the corresponding built-in background.

`capabilities` has boolean `search`, `filters`, `covers`, `popularity`, `merge` and
`ratings` values. Disabled capabilities cannot be contradicted by their payload
declarations. The application supplies the implementation of each capability; the pack
supplies data and configuration only.

`defaults` contains `showImages`, `stickyScoreHeader`, `mergeEnabled` and `ratingMode`
(`simple` or `elaborated`). These are first-use defaults. Local user choices take
precedence. The deprecated shared-cover switch is not part of the generated format;
group and item covers follow the current automatic rules.

## Fields, filters and subtitles

Each `fields[]` entry has a stable `id`, a `type`, a boolean `multiple`, optional
localized `labels` and optional `options` for a `choice` field. Types are `text`,
`integer`, `decimal`, `boolean`, `date` and `choice`. Attributes in catalog records must
match these definitions. Numeric integer values are bounded so they remain exactly
representable in Room's numeric column.

Each `filters[]` entry has `id`, `field`, `type`, `order` and optional localized
`labels`. The referenced field must exist. The manifest parser accepts `multi_select`
for text, choice and boolean fields, and `range` for integer, decimal and date fields.
The current filter UI can present available numeric values as a multi-selection; this
does not change the manifest type. Values selected within a filter use the user's OR/AND
choice; separate filters combine with AND.

`subtitle[]` lists distinct declared field IDs in display order. A field does not have
to be filterable to appear in a subtitle. Empty values are omitted by the UI.

## Groups and Merge

`merge` contains `enabled`, `strategy`, optional `groupType` and optional localized
`labels`. Supported strategy names are `NONE`, `GROUP_ID`, `COLLECTION_ID` and
`RELATION_GRAPH`. Enabled Merge requires the merge capability, a non-`NONE` strategy, a
group type and at least one declared group payload.

Group JSONL records define relationships between items of the same pack. A record has an
`id`, a `members` array and optional `displayTitle`, `groupType`,
`representativeMediaId`, provider, popularity and cover fields. The importer validates
member and representative references. Merge changes the list representation; it does not
create, delete or change group membership. A group can have its own cover; otherwise the
UI falls back to a valid representative image without copying the file.

## Rating definitions

A pack with `capabilities.ratings: true` must declare `rating.categories[]` and
`rating.profiles[]`. Categories define stable IDs, names, descriptions, colours, order,
active state and default weights. Profiles identify a set of weights by category ID. The
parser validates references and bounds. Personal taste and the 0–10 scoring scale are
application rules, not fields a pack may override.

Installed definitions are defaults. User-edited categories and profiles live outside the
pack and survive a normal uninstall or pack update. Reset Default explicitly restores
the installed manifest definitions.

## Cover sources

`coverSources[]` declares source `id`, attribution `sourceId`, optional `providerId`, an
exact `allowedHosts` list, and either ordered `urlTemplates` or one `metadataRequest`. A
metadata request declares a reference key, GET or POST_JSON method, fixed HTTPS URL
template, optional JSON body template, ordered JSON image paths and
`requestIntervalMillis`. The parser limits that interval to 250–60,000 ms.
Metadata-backed references use the `cover:v1|key=positive_id` form.

A candidate URL retains the allowed-host list of the source that produced it; another
source's permissions are never added. The downloader requires HTTPS, checks public
DNS/IP results, validates every redirect, limits redirects to three, bounds response
size and checks image content. HTTP 403, 404 and 410 are definitive absence for a
candidate. A URL blocked by the source's security policy or redirect limit is also
definitive for that candidate. Network failures, HTTP 429 and server errors remain
retryable. The repository records `NOT_FOUND` for an entry only after all applicable
candidates and group fallbacks have been exhausted without a retryable failure.

`remoteCoverRef` in an item or group record is an opaque reference interpreted through
the pack's declared sources. It is not permission to fetch an arbitrary URL.

## Payload and integrity

`catalog`, `groups` and `covers` are arrays of normalized relative payload paths. At
least one catalog payload is required. Catalog and group records are UTF-8 JSONL,
optionally GZIP-compressed with a `.jsonl.gz` suffix. A catalog record contains `id`,
`title`, optional provider/popularity/cover fields, `attributes` and `aliases`; a group
record contains group identity and membership fields. The verified manifest supplies the
`packId`, which is not repeated per line. Unknown record properties and undeclared
attributes are rejected.

`integrity` contains `installedBytes` and `files[]`. Every non-directory ZIP entry
except `manifest.json` must appear exactly once in `files[]` with its normalized
relative `path`, exact `bytes` and SHA-256. The byte total must equal `installedBytes`.
The installer rejects missing or undeclared entries, duplicate or case-colliding paths,
path traversal, symbolic links, executable payloads, oversized data and hash mismatches.
A completed installation is marked READY only after catalog import and consistency
checks.

## Attribution

`attribution` contains a publisher, source entries, licence entries, dataset entries,
notices and optional legal-file paths. Source and licence IDs referenced by datasets
must exist. A source may declare an integrity-listed `logoPath` under
`metadata/attribution/`; accepted image suffixes are PNG, JPEG and WebP, with a 2 MiB
file limit. Legal documents are integrity-listed plain-text files under
`metadata/legal/`. Attribution URLs must be safe HTTPS URLs and are displayed as
pack-supplied information.

The pack publisher is responsible for the accuracy and rights behind its declarations.
Passing technical validation does not verify a licence, trademark permission or source
ownership. Application dependencies and RankOffline's own branding remain separate from
pack-specific attribution.

## Examples and validation

The manifests embedded in packs generated from `scripts/packs/official-packs.json` are
complete examples of the accepted format. To inspect and validate one:

```bash
./scripts/build_5_packs.sh
python3 scripts/validate_pack.py build/pack-output/pack-1.pack
./scripts/validate_packs_with_kotlin.sh
```

See [PACK_GENERATION.md](PACK_GENERATION.md) for build inputs and distribution
preparation. Do not edit a generated ZIP manifest by hand: rebuild the archive and
regenerate its descriptor, size and SHA-256 together.
