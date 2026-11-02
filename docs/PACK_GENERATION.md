# Pack generation and validation

## Scope

The FASE 8 toolchain builds deterministic, data-only schema-2 ZIP archives. It
does not modify the Kotlin engine, publish files, add a remote registry, update
installed packs or download fresh provider datasets. The five official IDs
exist only in `scripts/packs/official-packs.json`:

| Pack | `packId` | Source snapshot |
|---|---:|---|
| Animes | 1 | Anime v0.2.0 schema-1 archive |
| Mangas | 2 | MangaDex 2026-10-16 |
| Movies | 3 | TMDB 2026-10-16 |
| Series | 4 | TMDB 2026-10-16 |
| Books | 5 | Open Library 2026-09-30 top 50,000 |

## Inputs and commands

By default the builder looks for the old project at
`../../AnimeRankOffline` relative to this repository. Override it without
editing configuration:

```bash
export RANKOFFLINE_LEGACY_ROOT=/absolute/path/to/AnimeRankOffline

./scripts/build_5_packs.sh
python3 scripts/build_packs.py --pack 4
python3 scripts/build_packs.py --all --output /tmp/rankoffline-packs
python3 scripts/validate_pack.py build/pack-output/pack-1.pack
python3 -m unittest discover -s scripts/tests -v
./scripts/validate_packs_with_kotlin.sh
```

The generic entry point also accepts a standalone declarative configuration:

```bash
python3 scripts/build_packs.py --config /absolute/path/to/config.json
```

New provider/category transformations belong in an input adapter. Deterministic
JSON/GZIP/ZIP writing, manifest construction, hashing and validation stay in the
generic layer.

## Output

```text
build/pack-output/
├── pack-1.pack
├── pack-2.pack
├── pack-3.pack
├── pack-4.pack
├── pack-5.pack
├── pack-index.json
└── validation-report.json
```

`pack-index.json` contains the complete parsed manifest, byte count, whole-file
SHA-256, filename and stable registry ID. `archiveUrl` deliberately remains
`null` until an owner publishes the exact artifact at a trusted HTTPS URL. Once
filled, those values are sufficient to construct `PackDownloadDescriptor`;
the app is not connected to this index in v0.2.2.

Build products and every `*.pack` are ignored by Git. The approved source
backgrounds under `scripts/pack-assets/` are versioned so official builds remain
reproducible.

## Optional Share backgrounds

Place owned/licensed final images at these exact paths before building:

```text
scripts/pack-assets/pack-<id>/share-top-seven.webp  # exactly 1080 x 1920
scripts/pack-assets/pack-<id>/share-top-ten.webp    # exactly 1920 x 1080
```

Each file must be at most 8 MiB. If absent, the builder omits the manifest key
and RankOffline uses its corresponding default. The validator reads WebP/PNG/
JPEG dimensions and rejects swapped or malformed images. The five official
background pairs use high-quality WebP to reduce package download size; WebP is
not a schema requirement for custom packages.

## Conversion audit

The source archives are schema 1 and cannot be renamed into schema 2. Conversion
performs these explicit operations:

- textual `packId` becomes the assigned positive JSON integer;
- provider-specific item properties become declared dynamic attributes;
- old `.jsonl.gzip` streams are decoded and deterministically regenerated as
  `.jsonl.gz`; redundant per-payload manifests are retained under
  `metadata/source/`, not imported as JSONL;
- item/group records no longer repeat pack identity or obsolete `mediaType`/
  `memberCount` structure;
- uppercase group types become valid lowercase field IDs;
- Series member positions are reindexed in existing array order to the required
  complete range `0..n-1`;
- TMDB poster references lose their leading slash because the universal URL
  component encoder rejects `/`; the resulting URL path is otherwise the same;
- titles/aliases/credit names containing controls or irregular whitespace are
  normalized to safe single spaces;
- values beyond the engine's hard 64-alias/64-values limits are retained as
  canonical JSON text in declared `source_*_overflow_N` attributes instead of
  being silently discarded. They are audit data, not additional searchable
  aliases/filter values;
- 13 Anime source IDs containing characters forbidden by the runtime regex are
  deterministically canonicalized, with every old/new pair recorded inside
  `metadata/conversion-report.json`;
- the old Anime `anime:v1|...` references required provider-specific API code
  removed from the universal engine. They are preserved in
  `legacy_cover_reference`, while Anime cover capability is disabled. No direct
  image URLs are invented.

The last two points are functional limitations, not hidden compatibility. Full
Anime covers and search/filter semantics for overflow values require either
new provider-approved direct source data or an explicit future engine contract.

## Validation levels

The independent validator checks strict manifest keys/types, safe paths, exact
ZIP inventory, byte counts/SHA-256, executable signatures/suffixes, UTF-8 JSONL,
all current item/group limits, ID and alias uniqueness, typed fields, filters,
rating definitions, group/reference integrity, cover sources, attribution,
legal assets and Share dimensions. Its unit suite also confirms rejection of
schema 1, tampered hashes, executable payloads and missing group members.

`validate_packs_with_kotlin.sh` compiles the current runtime parser/archive and
record-format sources with a small non-Android harness, then parses every line
of every generated artifact. A successful complete run is recorded in
`validation-report.json`. It avoids Gradle and does not duplicate parser logic.

A release candidate still needs:

1. installation through `PackInstaller` and import through `CatalogImporter`;
2. Room/KSP compilation in the complete Android project;
3. emulator checks for search, filters, Merge, covers, ratings and Share.

The current app has no public local-import UI and its trusted source registry is
empty. Do not copy archives directly into `files/packs/`; that bypasses the
installer and completion protocol. A trusted descriptor/developer installation
harness or the future import UX must be authorized before end-to-end emulator
installation.

## Distribution checklist

Before attaching these files to an APK debug release on GitHub, the owner must:

- complete the Kotlin/emulator validation above;
- review current provider/dataset/logo terms and all attribution wording;
- decide whether Anime's disabled cover capability is acceptable or replace its
  legacy references with approved direct image references;
- publish the exact immutable files, then insert their real HTTPS URLs into a
  distribution index without changing bytes/hashes;
- verify GitHub release filenames, byte counts and SHA-256 after upload;
- keep the APK, `.pack` files and personal backup formats clearly distinct.

No script in this repository publishes or creates a release.
