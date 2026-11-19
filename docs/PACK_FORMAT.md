# Pack format

A `.pack` is a data-only ZIP archive. It is neither an APK nor a personal backup. The
root `manifest.json` uses `schemaVersion: 2` and declares the pack's identity,
compatibility, presentation, fields, filters, ratings, cover sources, payload paths,
attribution and integrity data.

The following versions are independent:

| Contract | Current version | Purpose |
|---|---:|---|
| Android application | `versionName` in `app/build.gradle.kts` | Installed application version |
| Pack publication | `{slug}-v{N}` in the manifest | Monotone content revision of one pack |
| Pack manifest | 2 | Structure of `manifest.json` |
| Catalog and group records | 2 | JSONL item and group representation |
| Personal backup | 2 | User-owned JSON export; never a `.pack` |

Catalog and group payloads are UTF-8 JSONL, optionally compressed as `.jsonl.gz`. Every
non-directory ZIP entry except `manifest.json` must be declared exactly once in
`integrity.files` with its path, byte count and SHA-256. The installed-byte total must
match the declared files. Undeclared, duplicate, missing, unsafe or oversized entries
are rejected.

Item and group IDs are scoped to a positive numeric `packId`. The verified containing
manifest supplies that identity; individual JSONL records do not repeat it. The parser
rejects unknown record properties and attributes not declared in `fields`. Group
membership is a relation between existing items. Merge determines whether the UI
displays groups or individual entries.

New packages also declare a permanent ASCII `slug` in the manifest. The package
revision is the final positive `-vN` suffix of `version`; the rest must equal `slug`.
The visible `displayName` and localized names can change or coincide between packages.
The local publishing registry assigns IDs and reserves slugs; neither a local build nor
an APK installation allocates a new public ID or increments a published revision.

Group records may declare up to 64 own `aliases`, using the same title, language and
kind shape as item aliases. They are optional, so existing schema-2 packs still parse.
Pack builders must set `minRankOfflineVersion` to at least `0.2.3` when they add this
field: version 0.2.2 rejects unknown group properties. Search uses only the group's
canonical title and these declared aliases; member aliases do not turn into group
aliases during a query.

A cover source declares either ordered HTTPS image URL templates or a bounded JSON
metadata request. Each image candidate retains the exact `allowedHosts` set of the
source that produced it. The downloader validates URLs, DNS/IP, redirects, size and
image content before storing a file. Installed local covers and downloaded covers remain
inside the owning pack directory.

See [PACK_MANIFEST_SPEC.md](PACK_MANIFEST_SPEC.md) for the manifest fields and
[PACK_GENERATION.md](PACK_GENERATION.md) for generation and validation commands. The
Kotlin parser and installer are authoritative if documentation and code differ.
