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
6. install packaged cover references, refresh registry/runtime state, and publish READY;
7. commit replacement only after all preceding steps succeed.

Startup uses the same per-pack lifecycle coordinator. An unchanged imported hash/count takes the `NoChange` path. Files without a verified Room import are not selectable. Interrupted replacements recover the last valid pack or expose an ERROR/retry state.

## Deletion

**Delete pack and data** is the centralized destructive boundary. It removes the pack files, catalog, groups, bundled/downloaded covers, cache, PackId-scoped ratings and detailed scores, notes/status, ranking state, filters, profiles/categories, and pack settings. Global language, palette, app icon, and onboarding state remain. If the active/last pack is deleted, navigation falls back safely to another READY pack or the zero-pack package-management experience.

## Generated artifacts

Run `python3 tools/category_packs/build_category_packs.py`. It writes a deterministic matched set below `tools/.cache/category-packs/output/` and copies its trusted index to `app/src/main/assets/category_pack_index.json`.

The current matched local set contains no redistributed cover binaries because provider metadata/API availability is not redistribution permission. Cover index support remains available for properly licensed assets.

Do not publish an APK against a different pack/index generation. Upload the five `.pack` files beside the matching APK only during an explicit release operation.
