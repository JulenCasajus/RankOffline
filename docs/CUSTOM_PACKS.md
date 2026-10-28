# Custom data packs: authoring guide and current limitations

RankOffline 0.2 uses a **data-only** pack format with a stable `PackId`, declared provenance,
offline catalog/groups, optional bundled cover assets, and declarative HTTPS cover sources.
The five official IDs are `animes`, `mangas`, `movies`, `series`, and `books`.

**Important: 0.2 does not yet let users install arbitrary community packs.** It ships
the official-pack install/validation pipeline, not a generic file picker, arbitrary URL
installer, marketplace or external PackId-aware UI. Do not describe unofficial packs as
installable through the production app today.

## Preparing a candidate pack

1. Choose a stable lowercase `PackId` and globally unambiguous canonical item/group
   identifiers; do not reuse identifiers from another installed pack.
2. Prepare a normalized, offline catalog at Work/movie/show/season/item granularity
   appropriate for the content. Include stable provider IDs, title, popularity ordering,
   and any available genres, aliases, credits or cover references.
3. Prepare explicit group records and **ordered membership** if Merge is supported.
   Do not infer group relationships from similar titles at runtime. Group popularity and
   representative member must be precomputed. For series-group covers, use the first
   ordered member's image rather than bundling every season.
4. Add a schema-1 `manifest.json` declaring provider, version, minimum app version,
   capabilities, filters, merge strategy, initial defaults and exact payload integrity.
   See [PACK_FORMAT.md](PACK_FORMAT.md) for the fields and example.
5. If the pack needs remote covers, declare safe HTTPS `coverSources` or individual
   validated references/URLs. Do not include arbitrary scripts, IP addresses,
   credentials, insecure redirects or private network addresses.
6. Only bundle image binaries inside `covers/` if the redistribution rights are
   documented. Metadata availability alone does not license artwork.
7. A pack may optionally declare one PNG, JPEG or WebP ranking-card background through
   `presentation.shareBackground`. Store it below `metadata/`, include it in integrity metadata,
   keep it within the documented byte/dimension limits, and document its redistribution rights.
   Older packs and packs without artwork use RankOffline's procedural fallback.
8. Package only allowlisted data files, compute exact byte lengths and SHA-256
   values, then validate paths, image data and hashes.
9. Verify offline querying, correct ordering/grouping, and preservation of personal
   data through a non-destructive test upgrade.

The current `tools/category_packs/build_category_packs.py` is a **producer for the five
official packs**, not yet a general-purpose third-party pack authoring CLI.
`tools/category_packs/prepare_pack_covers.py` prepares validated, rights-documented
cover payloads for the official build flow.

## Why the community installer is deferred

Personal ratings already use `(pack_id, canonical_item_id)` ownership, but the current
Room catalog and group tables still require globally unique canonical IDs.
Allowing an arbitrary third-party pack could collide with official IDs.
A future installer must either reject all collisions reliably or migrate catalog
keys to composite identity without losing existing ratings.

The proposed community workflow is:
**discover/obtain → validate provenance and integrity → install → register → import**.
A possible future server/registry would list pack metadata and signed/hashed artifacts,
not execute publisher code on the device.

The 0.2.1 development tree includes a virtual `RankOfflineAll` view that combines the
**installed READY official packs** without creating a sixth pack or duplicating ratings.
It does not remove the collision restriction above: arbitrary community PackIds are not
accepted until catalog/group identities can be safely rejected or namespaced.

See [PACK_FORMAT.md](PACK_FORMAT.md), [MEDIA_ARCHITECTURE.md](MEDIA_ARCHITECTURE.md),
and [ROADMAP.md](ROADMAP.md).

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
