# Creating and adding a personal pack

> **Status: format ready; in-app import NOT available yet.** This is a creator's guide for the v0.2.0 **data-only** contract, not a claim that the stable APK or current development UI can install arbitrary user packs.

## What already exists

RankOffline's upcoming v0.2.0 runtime defines:

- stable pack IDs independent of visible media labels;
- a common data-only ZIP/`.pack` manifest and strict SHA/size checks;
- atomically installed pack files under `files/packs/<pack_id>/`;
- independently imported catalogs and precomputed Merge groups;
- a common pack registry and safe replacement/rollback.

**What does not exist yet:** a public “Import personal pack” button, generic UI/Room adapters for unknown PackIds, a public signing/trust policy, or automatic discovery of arbitrary pack archives. Current app browsing and catalog imports target the **five official media types**. Simply copying `my-pack.pack` onto an Android device will not make it appear in RankOffline.

## Preparing a future-compatible pack

1. **Pick a supported data domain and stable IDs.** For example, a Books catalog uses singular `mediaType: "book"` in its records. Your outer `packId` is a separate identifier (e.g. `my-books`), but this does **not** yet register it with the runtime UI.
2. **Build normalized records.** Keep a permanent canonical ID per title, provider/providerId, display title, and optional year, tags, creators and cover references. Do not reuse the same ID for unrelated entries.
3. **Precompute any Merge graph on your computer.** Build explicit groups, ordered members, representative IDs and counts before packing. Do not ask the device to identify related items by string matching.
4. **Write the catalog and group manifests.** Include exact record counts, versions and checksums; match `mediaType`, provider and group type.
5. **Create the root manifest (`schemaVersion: 1`).** List every data payload and its actual decompressed-on-disk byte length and SHA-256. For a full example and validation rules, see [PACK_FORMAT.md](PACK_FORMAT.md).
6. **ZIP the data files into a `.pack` archive.** Keep `manifest.json` at the archive root. Do not include executables, absolute paths, credentials, or copyrighted artwork you have no right to redistribute.
7. **Validate before distribution.** Test JSON parsing, all archive paths/hashes/sizes, manifest group counts, duplicate IDs and membership integrity, and safe replacement of an older pack.
8. **Test once external imports are supported.** Verify install → READY → browse/rate → Merge → restart → replace → purge on a separate debug installation.

## Useful local checks

```bash
# Show archive contents (manifest must be at its root)
unzip -l MyPersonal.pack

# Read its root manifest
unzip -p MyPersonal.pack manifest.json | python3 -m json.tool

# Compute the archive hash for your distribution metadata
shasum -a 256 MyPersonal.pack
```

These commands **inspect a pack**; they do not install it in RankOffline.

For official-pack generation in the development repository, see `tools/category_packs/build_category_packs.py` and associated source generators. These tools presently target the official pack definitions, not arbitrary new PackIds.

## Data ownership and deletion

Distinguish **updating** an installed pack from **deleting** it: an update must preserve personal ratings, scores, notes and downloaded cover paths for surviving canonical IDs. The app's destructive delete-pack action (when available for a pack) is designed to purge *all* pack-owned data, including personal ratings and covers, after confirmation. Back up data you want to keep **before** deleting a pack.

## Remaining work before anyone can add a personal pack inside the app

The future implementation needs:

- user-selected `.pack` file picker/import surface (Storage Access Framework);
- third-party PackId → supported runtime/media adapter registration;
- creator/provider attribution and clear cover/data licensing;
- validation and trust policy for untrusted archives, plus package size/ZIP-bomb limits;
- indexed catalog and Merge browsing of imported third-party packs;
- export/backup strategy for user-owned ratings.

Do not present these as shipped features. The format above is a **compatibility foundation**, and may be revised before general third-party import is released.
