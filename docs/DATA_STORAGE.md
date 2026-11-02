# Data storage

All operational data is app-private:

```text
files/
├── packs/pack-<positive-id>/   # installed immutable/derived pack data
├── local/<positive-id>/        # editable personal pack preferences/ratings
├── share-defaults/             # installed default Top 7/Top 10 backgrounds
└── restore-journal/            # restore recovery state when required
databases/rankoffline_universal.db
cache/
├── pack-downloads/
├── shared-personal-backups/
└── shared-ranking-cards/
```

Exact cache/journal children are implementation details and may be cleaned or
recreated. `PackId(0)` is virtual and must never create a physical pack folder.
Uninstalling a catalog retains independent personal data unless the user
explicitly chooses purge.

Android cloud backup and device-to-device transfer are disabled. User-controlled
portability is the validated personal JSON export through SAF/FileProvider.
