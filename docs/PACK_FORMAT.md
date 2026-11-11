# Pack format

A `.pack` is a data-only ZIP archive. It is neither an APK nor a personal backup. The
root `manifest.json` uses `schemaVersion: 2` and declares the pack's identity,
compatibility, presentation, fields, filters, ratings, cover sources, payload paths,
attribution and integrity data.

The following versions are independent:

| Contract | Current version | Purpose |
|---|---:|---|
| Android application | `versionName` in `app/build.gradle.kts` | Installed application version |
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

A cover source declares either ordered HTTPS image URL templates or a bounded JSON
metadata request. Each image candidate retains the exact `allowedHosts` set of the
source that produced it. The downloader validates URLs, DNS/IP, redirects, size and
image content before storing a file. Installed local covers and downloaded covers remain
inside the owning pack directory.

See [PACK_MANIFEST_SPEC.md](PACK_MANIFEST_SPEC.md) for the manifest fields and
[PACK_GENERATION.md](PACK_GENERATION.md) for generation and validation commands. The
Kotlin parser and installer are authoritative if documentation and code differ.
