# Custom packs (planned)

RankOffline 0.2 implements the data-only PackId, manifest, validation, registry, install, import, and purge boundaries used by official packs. It does **not** yet expose a file picker, arbitrary URL installer, marketplace, or executable plugin API.

A future external installer is intended to perform only: obtain → validate → install → register → import. Packs may contain declared catalog, group, cover, and metadata files, but never code. See [PACK_FORMAT.md](PACK_FORMAT.md).

Schema 1 currently requires catalog/group canonical IDs to be globally unique across installed packs. Arbitrary third-party installation must remain disabled until collisions are explicitly rejected or those catalog keys are migrated to `(pack_id, canonical_id)`.
