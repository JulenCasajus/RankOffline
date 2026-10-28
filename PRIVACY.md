# Privacy

RankOffline has no account, analytics, telemetry, advertising SDK, or remote
rating service. Ratings, detailed scores, notes, statuses, profiles, settings,
installed catalogs, and cover files are stored in the application's private
Android storage.

Network access is used only for explicit pack transfers and cover retrieval,
including the optional initial cover acquisition selected for a newly installed
pack. Browsing, searching, Merge, and rating use the installed local catalog and
do not perform remote title discovery. Cover providers receive the normal
network metadata needed for an HTTPS image request; personal ratings and notes
are not sent to them.

Personal-data exports are user-initiated, versioned, **unencrypted JSON** files.
They can contain ratings, detailed scores, notes, statuses, profiles, pack
settings, and custom themes. They exclude catalog records, pack archives,
credentials, and cover binaries. Android's Storage Access Framework gives the
selected document provider control of the destination; that provider may be a
cloud-synchronized service. Users should choose and protect the destination
accordingly.

Sharing a backup creates a temporary copy in RankOffline's private cache and grants the Android
Sharesheet recipient read access through a `content://` URI. The selected receiving application can
retain or forward that unencrypted file. RankOffline removes old temporary share files on subsequent
share operations; Android may also clear application cache independently.

Android Auto Backup and device-to-device backup are disabled in the manifest.
Uninstalling the application may remove its private local data unless the user
first exports a personal-data backup.
