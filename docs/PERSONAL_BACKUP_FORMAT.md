# Personal-data backup format

RankOffline 0.2.1 uses a user-selected JSON file through Android's Storage Access Framework. The
top-level `schemaVersion` is currently `1` and `source` is `rankoffline`; unsupported versions,
sources and unknown fields are rejected before any state is changed.

The file contains:

- PackId-scoped item and group ratings, detailed category scores, notes, status, profile references
  and update timestamps;
- per-pack `MediaSettings` and catalog sort order;
- virtual RankOfflineAll display/sort settings;
- custom palettes and edits/visibility of built-in palettes.

It never contains catalog/provider records, pack archives, cover binaries, credentials or private
network data. Input and output size, counts, PackIds, identity lengths, score ranges, weights,
references, finite numeric values and palette fields are bounded and validated. The completed UTF-8
representation is checked against the same 16 MiB limit before a SAF destination is opened.

**Merge newer** retains the local rating on timestamp ties and replaces a rating plus its complete
detailed-score set only when the incoming main rating is newer. **Replace** clears the four personal
rating tables transactionally before inserting the validated backup. Personal rows may refer to a
temporarily uninstalled PackId and reconnect when a compatible catalog is installed. Settings are
restored through DataStore. Room and DataStore cannot share one transaction, so restore uses an
app-private durable journal, redundant journal copy and a Room commit marker: an interrupted operation deterministically
rolls settings back when Room did not commit, or idempotently reapplies the imported settings when
Room did commit. Recovery runs during application startup only when a journal exists; unrelated pack
workers do not couple their lifecycle to a personal-data restore.

Merge uses a deterministic policy: newer ratings win and timestamp ties preserve local ratings;
settings explicitly present in the imported backup replace the corresponding pack settings, and an
imported custom palette replaces a local palette with the same stable ID. Replace restores the full
backed-up personal-data scope. The preview explains this before either operation runs.

The JSON backup is not encrypted. The user-selected SAF document provider may retain or synchronize
it. RankOffline validates the complete backup before changing persistent state, but cannot promise
atomic replacement semantics for every external SAF provider during export.

Settings → Packages also supports Android's native Sharesheet. RankOffline writes a short-lived file
inside its private cache and exposes only that file through a read-only `content://` FileProvider URI.
It never shares a raw filesystem path. Import, export, share and restore are mutually exclusive.

## Synchronization boundary

The backup is the first account-free device-transfer mechanism. Internal utilities model local work
identity as `(PackId, canonicalId)`, external provider IDs separately, source rating scales, timestamp
conflicts, missing mappings and ambiguous mappings. A dry run never guesses ambiguous identities and
keeps the source score/scale alongside a converted preview. No OAuth client, polling job or provider
network connector is enabled in 0.2.1; each future connector requires provider authorization and an
explicit user action.

Future revisions must increment `schemaVersion` and provide an explicit reader/migration. They must
not reinterpret version 1 silently.
