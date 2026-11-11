# Data storage

RankOffline stores operational data in Android app-private storage:

```text
files/
├── packs/pack-<positive-id>/       installed pack manifest and payloads
├── local/<positive-id>/            personal preferences, rating definitions and summaries
├── share-defaults/                 bundled default sharing backgrounds
└── personal-restore-journal.json   interrupted-restore recovery state, when needed

databases/rankoffline_universal.db  catalog, group and rating tables

cache/
├── pack-downloads/                temporary archive downloads
├── shared-personal-backups/       temporary files for user-initiated sharing
└── shared-ranking-cards/          temporary ranking images
```

Local cover files are stored within the owning pack directory, including `covers/items/`
and `covers/groups/`. Room records their paths and resolution state. A normal pack
uninstall removes pack files and catalog rows but retains personal ratings and settings.
Explicit purge is a separate user action.

`PackId(0)` represents RankOffline All and never creates a `pack-0` directory. The 0.2.0
database, if present during an upgrade, is read by the one-time personal-data migration;
current catalog data uses the universal database above.

Android cloud backup and device-to-device transfer are disabled in the manifest. Users
can export and import validated personal JSON through the system picker or share sheet.
Temporary shared files are exposed through a non-exported FileProvider with URI grants.
