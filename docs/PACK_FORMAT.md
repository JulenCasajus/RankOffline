# `.pack` format overview

A `.pack` is a data-only ZIP archive. It is not an APK, backup or executable
plugin. The validated root `manifest.json` uses `schemaVersion: 2` and binds the
archive identity, compatible app version, capabilities, data files, integrity,
presentation and legal attribution.

## Four independent versions

| Concept | Current value | Purpose |
|---|---:|---|
| APK `versionName` | 0.2.2 | Installed Android application release |
| pack manifest `schemaVersion` | 2 | Shape and rules of `manifest.json` |
| catalog/group `formatVersion` | 2 | JSONL record representation |
| personal backup `schemaVersion` | 2 | User-data JSON; never a `.pack` |

Changing one does not implicitly change the others.

## Data

Catalog and optional group payloads are UTF-8 JSONL, optionally GZIP-compressed
as declared by the manifest. Item and group IDs are local to one positive
numeric `packId`; the importer supplies the verified containing `packId` rather
than trusting each line. Field definitions are generic (`text`, `integer`,
`decimal`, `boolean`, `date`, `choice`). Unknown properties and undeclared
fields are rejected.

Every payload/resource must be declared by path, byte count and SHA-256 in the
integrity section. Paths are relative, normalized and contained inside the
installation root. See [PACK_MANIFEST_SPEC.md](PACK_MANIFEST_SPEC.md) for the
complete normative contract.

Pack tools from v0.2.1 implement schema 1 and are not compatible.

## Archive contract enforced by v0.2.2

`manifest.json` is not listed inside `integrity.files`. Every other non-directory
ZIP entry is listed exactly once; undeclared and missing entries are rejected.
Catalog/group payloads accepted by the importer end in `.jsonl` or `.jsonl.gz`
(not the historical `.jsonl.gzip`). Explicit ZIP directory entries are not
needed by the generator.

Item and group records use strict JSON decoding. Item records contain only
`id`, `title`, optional provider/popularity/cover values, `attributes` and
`aliases`; group records contain only identity, membership, presentation,
provider/popularity and cover values. The containing verified manifest supplies
the numeric `packId`.

The maintained commands and conversion limitations are documented in
[PACK_GENERATION.md](PACK_GENERATION.md).

## Remote covers

A source declares either ordered HTTPS `urlTemplates` or one bounded
`metadataRequest`. Metadata-backed records use
`cover:v1|reference_key=positive_id` and may contain several keys so sources can
be tried in manifest order. A metadata recipe is data, not executable provider
code: it is limited to GET/POST JSON, a fixed HTTPS host, ordered JSON response
paths, request spacing and approved image hosts. The resulting image still
passes through the ordinary size, MIME, content and storage checks.
