# Architecture

RankOffline is an offline-first Android application. The APK implements a generic
catalog and rating engine; installed `.pack` files provide catalog data, presentation,
rating definitions, cover sources and attribution. Packs are validated data archives,
not executable plugins.

## Source layout

Production Kotlin lives under `app/src/kotlin/<domain>/` and uses the package
`org.rankoffline.app`. The main domains are `app`, `packs`, `catalog`, `ratings`,
`covers`, `preferences`, `settings`, `backup`, `share`, `theme`, `onboarding`, `legal`
and `data`. Android resources and the manifest are under `app/src/res/` and
`app/src/AndroidManifest.xml`. Tests live in `app/tests/unit/`,
`app/tests/instrumented/` and `app/tests/fixtures/`.

## Data and installation

A positive `PackId` identifies an installed pack. Room uses pack-scoped keys for catalog
entries, groups and ratings. `PackId(0)` is the virtual RankOffline All view and has no
physical pack directory. Group membership comes from the catalog; Merge changes how
entries are displayed, not their identity.

The pack installer verifies archive size, SHA-256, file inventory, paths and payload
hashes before activating files under `files/packs/pack-<id>/`. The catalog importer
writes items, attributes, aliases, groups and relations to Room. Installation state and
import fingerprints support recovery without reimporting a valid READY catalog. Room
uses write-ahead logging. User ratings and editable settings remain under separate
storage so a normal uninstall retains them.

WorkManager persists pack and initial-cover operations. Archive transfers have FIFO
priority over new cover transfers; local import does not reserve the archive network
turn. Cover batches stage their Room updates and commit them after network work, so an
unrelated catalog import need not hold every image transfer. Source-specific HTTPS host
rules and rate limits apply to each cover candidate.

## UI state

The ViewModel and `CatalogSessionCache` retain Search and MyRanking pages, cursors and
scroll positions independently for up to five recently used views, including RankOffline
All. Each retained page is bounded to 1,200 entries. The cache is process memory only.
For navigation recency, DataStore stores only physical pack IDs for later preloading; it
does not store pages, bitmaps or scroll positions.

Startup prioritizes the visible READY view. After its first useful page appears, up to
four other recent READY physical packs are preloaded sequentially, or three when All
occupies a cache slot. Preloading reads local catalog data and does not download covers
or import packs. Local cover thumbnails use a bounded in-memory bitmap cache.

## Personal data

Personal JSON backups contain ratings and settings, not catalog payloads or covers. A
one-time migration reads user-owned data from the published 0.2.0 database when that
database is present; it does not modify the old database. Current catalog storage
remains separate from that legacy database.

The authoritative contracts are the Kotlin parsers and importers. See
[PACK_FORMAT.md](PACK_FORMAT.md), [PACK_MANIFEST_SPEC.md](PACK_MANIFEST_SPEC.md) and
[DATA_STORAGE.md](DATA_STORAGE.md).
