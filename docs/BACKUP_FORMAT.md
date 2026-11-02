# Personal backup format

Personal exports are UTF-8 JSON with `schemaVersion: 2` and
`source: "rankoffline"`. They are not `.pack` files and never include catalogs,
covers, downloaded archives or cached summaries.

`scopeId: 0` is a global export and carries global preferences plus all selected
pack-local settings/ratings. A positive `scopeId` is pack-only and cannot alter
global settings or contain another pack. Ratings remain valid when their pack
is uninstalled.

The format includes:

- global All/view/theme/language/icon/last-scope preferences when global;
- per-pack preferences including `useGroupCoversForItems`;
- per-pack rating definitions and profiles;
- item and group scores, notes, status, profile and category values.

`MERGE_NEWER` resolves personal rows by update timestamp and imports supplied
settings. `REPLACE` replaces only the selected backup scope; a partial replace
must not erase another pack. Imports are validated before mutation and use a
restore journal across Room, DataStore and local JSON boundaries.

Limits currently include 64 MiB encoded input, at most 500 pack settings and
100,000 item+group ratings. There is intentionally no v0.2.1 backup migration.
