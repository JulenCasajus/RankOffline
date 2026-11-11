# Personal backup format

Personal exports are UTF-8 JSON with `schemaVersion: 2` and `source: "rankoffline"`.
They are separate from `.pack` archives and contain no catalog rows, downloaded
archives, covers or cached summaries.

A backup with `scopeId: 0` contains global settings and the selected pack-local data. A
positive `scopeId` limits the backup to one pack and cannot change global settings or
another pack's ratings. Pack-local ratings remain valid when the catalog is uninstalled.

The format can carry global view, language, theme, icon and navigation preferences;
per-pack display and rating settings; item and group ratings; notes; status; category
scores; and profile selections. Optional fields allow a backup to omit a local settings
file that did not exist at export time.

Import modes are `MERGE_NEWER`, which chooses the newer personal rating by update
timestamp, and `REPLACE`, which replaces data only within the backup's scope. Input is
validated before mutation. A restore journal coordinates Room, DataStore and local JSON
writes so an interrupted restore can be recovered.

The decoder limits encoded input to 64 MiB. Validation limits a backup to 500 pack
settings and 100,000 item and group ratings combined. The Kotlin data classes and
validation code in `app/src/kotlin/backup/` define the exact fields and limits.

Upgrading an installed 0.2.0 app is a separate, one-time migration path. It reads the
old personal database directly; it is not a schema-1 JSON backup importer.
