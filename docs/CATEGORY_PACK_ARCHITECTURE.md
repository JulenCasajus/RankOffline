# Category-pack architecture

RankOffline Core contains shared UI, Room schema/migrations, ratings, ranking, search, filters, Merge, covers, settings, onboarding, and pack validation. Production catalogs are not embedded in the normal APK.

Official PackIds are `animes`, `mangas`, `movies`, `series`, and `books`. Installed data lives under `files/packs/<pack_id>/`; downloaded covers live under an app-owned media directory and are included in the pack's displayed/reclaimable storage.

## Install and recovery

The trusted APK index declares each archive's final installed bytes, transfer bytes, SHA-256, manifest, and compatibility metadata. Transfer bytes remain internal to HTTP validation; UI storage values use final installed bytes.

Installation is staged and atomic:

1. download and verify the archive;
2. validate the data-only manifest and every payload path/size/hash;
3. promote files while retaining the previous valid generation;
4. import the normalized catalog;
5. import and verify the precomputed group graph when Merge is supported;
6. install validated cover references and finish importing the local catalog/group state;
7. publish catalog READY once its Room data is usable; optional Top-100 cover retrieval, if enabled
   for this pack, is separate from catalog readiness. Cover failures cannot invalidate the catalog.

The current implementation limits archive transfers
to one at a time; safe unrelated imports may overlap transfers, while Room
import writes are serialized. Pending/queued pack and cover states are
represented explicitly. These implementation choices still require stable
candidate and device-level validation. No network title search occurs.

Startup uses the same per-pack lifecycle coordinator. An unchanged imported hash/count takes the `NoChange` path. Files without a verified Room import are not selectable. Interrupted replacements recover the last valid pack or expose an ERROR/retry state.

## Cover storage and progress

Cover binaries obtained from a provider are stored under a PackId-specific removable cache,
separate from the verified archive. The single **Downloaded covers** counter describes locally
available covers for the selected pack, including any rights-cleared cover images extracted
from the pack. Deleting cached covers for one pack must never purge another pack's images.
Optional Top-100 cover acquisition runs only as part of a new installation with that pack's
Show Images setting enabled; catalog search and ratings continue fully offline.

The five official packs currently include no cover-art binaries because licensing for
redistribution of third-party artwork has not been established. Do not describe remote
cover availability as a right to bundle or republish files.

## Deletion

**Uninstall pack, keep personal data** removes the reference catalog, groups, pack files and cover
cache while retaining PackId-scoped ratings, detailed scores, notes/status, profiles/categories and
preferences. Reinstalling compatible canonical identities reconnects that personal state.

**Delete pack and data** is the centralized destructive boundary. It removes the pack files, catalog, groups, bundled/downloaded covers, cache, PackId-scoped ratings and detailed scores, notes/status, ranking state, filters, profiles/categories, and pack settings. Global language, palette, app icon, and onboarding state remain. If the active/last pack is deleted, navigation falls back safely to another READY pack or the zero-pack package-management experience.

## Generated artifacts

Run `python3 tools/category_packs/build_category_packs.py` for the explicitly supported
data-only build (rights-cleared bundled covers can be prepared separately).
It writes a deterministic matched set below `tools/.cache/category-packs/output/` and copies its trusted index to `app/src/main/assets/category_pack_index.json`.

The current matched local set contains no redistributed cover binaries because provider metadata/API availability is not redistribution permission. Cover index support remains available for properly licensed assets.

Do not publish an APK against a different pack/index generation. Upload the five `.pack` files beside the matching APK only during an explicit release operation.
