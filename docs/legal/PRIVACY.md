# Privacy

RankOffline is offline-first. Ratings, notes, preferences, installed catalogs
and covers are stored in the app's private storage. The app includes no account,
analytics, advertising, telemetry or background synchronization service.

Internet access is used only when a user operation requires HTTPS pack or cover
data described by a trusted descriptor/installed pack. v0.2.2 ships with no
remote registry configured. Cleartext traffic is disabled. Pack attribution
links are displayed as untrusted text; only a small app-owned legal/source URL
allow-list is opened directly.

Android cloud backup and device transfer are disabled. Users can explicitly
export/import personal schema-2 JSON through the system picker or share sheet.
Shared files are temporary cache files exposed through a non-exported
FileProvider with URI grants.

Installed packs are separate works and must disclose their data sources,
licences and notices. RankOffline does not transmit personal ratings to pack
publishers. This document is technical project documentation, not a substitute
for the owner's final store privacy policy and Data safety declaration.
