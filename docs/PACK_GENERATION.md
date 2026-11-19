# Pack generation and validation

The tools under `scripts/` build deterministic schema-2 data archives. The five official
pack definitions live in `scripts/packs/official-packs.json`. The append-only
`scripts/packs/pack-registry.json` reserves each numeric ID and slug and records the
last published content fingerprint; withdrawn packages retain their reservation.
Future publication tooling can propose the next unused ID and `slug-v1` for a new
registration, or the next numbered revision of an existing slug, using
`scripts/packlib/pack_identity.py`. Only an approved publication updates the shared
registry; contributors do not need an exclusive owner account. The trusted distributor
still reviews and verifies every submitted archive.
Pack-specific conversion
belongs in input adapters, while manifest assembly, ZIP writing, hashing and validation
use the shared pack library. The Android engine has no official-pack branches.

| Official pack | `packId` | Input |
|---|---:|---|
| Animes | 1 | Anime catalog snapshot |
| Mangas | 2 | MangaDex snapshot |
| Movies | 3 | TMDB snapshot |
| Series | 4 | TMDB snapshot |
| Books | 5 | Open Library snapshot |

The official conversion inputs are read from the older project by default. Set
`RANKOFFLINE_LEGACY_ROOT` or pass `--legacy-root` if that checkout is elsewhere. A
custom declarative configuration can be passed with `--config`; it is subject to the
same validator.

```bash
./scripts/build_5_packs.sh
python3 scripts/build_packs.py --pack 5 --output build/pack-output
python3 scripts/build_packs.py --config /path/to/config.json --output /tmp/custom-packs
python3 scripts/validate_pack.py build/pack-output/pack-5.pack
python3 -m unittest discover -s scripts/tests -v
./scripts/validate_packs_with_kotlin.sh
```

A full official build writes `pack-1.pack` through `pack-5.pack`, `pack-index.json` and
`validation-report.json` under `build/pack-output/`. The builder's index records the
embedded manifest, filename, size, SHA-256 and registry ID. Its `archiveUrl` is null
because the artifact has not yet been assigned a distribution URL. A selected-pack build
updates an existing five-pack index only after validating its other entries.

The builder derives group titles and aliases from declared members once, before writing
group payloads. A relation group may use a shared root when its members have distinct
subtitles or numbered seasons and that root does not identify another group. Homogeneous
special and film groups receive a set title; variant groups may use the unmarked edition's
title when the marked edition's name is also declared as its alias. A one-shot item's
aliases retain the one-shot marker. Outside reviewed splits, group IDs, membership and
representatives stay unchanged. Aliases can be supported by parallel season or subtitle roots, by the
canonical root member, or by every declared variant. Reviewed exceptions in the
official pack configuration must match an actual source alias; missing evidence fails
the build. A reviewed set title must match a declared sibling series and have member
title or alias support; this avoids applying a broad franchise name to unrelated
crossovers. A shared numbered alias is accepted only when a member whose canonical
title equals the group root also declares that alias; unanchored numbered roots remain
ambiguous for review.
Candidates naming a specific installment, extending another supported alias with a
possible subtitle, or colliding with another group remain excluded or ambiguous.
`build/group-alias-audit/pack-<id>.jsonl` records each
candidate, its source members, decision and reason; the adjacent summary JSON records
coverage. `pack-<id>-title-roots.jsonl` records title changes and their member evidence.
These reports are local build artifacts and are not embedded in the pack.
An explicit `groupSplitReviewed` entry can divide a mixed source group. It records
the source member-ID hash, exact title prefixes for each new group, member-ID hashes
for the resulting parts and any crossover left as an individual item. The build fails
if a source member changes or a member is omitted or assigned twice. The existing ID
can be retained for one part; new parts receive distinct IDs and their own representative.
The local `pack-<id>-splits.jsonl` report lists the resulting memberships. Group
popularity supplied by a removed crossover is discarded rather than assigned to a
different franchise.
If derived aliases enter a group payload, the builder sets the manifest's minimum app
version to at least 0.2.3, because earlier APKs reject the new optional field.

Optional, licensed Share backgrounds can be supplied at
`scripts/pack-assets/pack-<id>/share-top-seven.webp` (1080 × 1920) and
`share-top-ten.webp` (1920 × 1080). The builder includes them only when present and
validates size and dimensions. Missing backgrounds use the application's corresponding
default.

## Validation and distribution preparation

The Python validator checks manifest structure, ZIP inventory, hashes, payload records,
relations, cover declarations and attribution. `validate_packs_with_kotlin.sh` also
parses generated packs with the actual Kotlin manifest and record parsers and records
the result in `validation-report.json`. Neither tool proves that a source's licence or
trademark use has been cleared.

To stage release assets without publishing them:

```bash
python3 scripts/prepare_pack_release.py \
  --source build/pack-output \
  --output build/release/candidate \
  --base-url https://example.org/releases/candidate \
  --apk app/build/outputs/apk/release/app-release.apk \
  --apk-name RankOffline.apk
```

The staging script revalidates each archive, copies it, and writes an index whose
archive URLs point to the supplied HTTPS base URL. Check filenames, sizes and SHA-256
against the exact files to be uploaded. The release index must not be published before
its referenced archives are available at those URLs. No generation script uploads files
or creates a release.

The 0.2.3 official definitions currently build `animes-v8`, `mangas-v4`,
`movies-v4`, `series-v4` and `books-v4`. Anime first used `v6` in a debug build;
`v7` corrected a duplicate provider group identity in a reviewed split, and `v8`
changed the default Merge setting. Rebuilding the same definitions does not consume
another revision. Books retains its `v4` content revision after normalizing the
version spelling and minimum app version.

The published content fingerprint excludes the spelling and minimum app version, but
includes payload hashes and presentation. A local build never consumes
a revision. Release preparation rejects an older revision, a skipped revision or reuse
of the last published revision with changed manifest content. After an approved
publication, the shared registry must record the new revision and fingerprint. A
restored older dataset still receives a new, higher revision.

For end-to-end confidence, install prepared packs through the app or an isolated test
harness and verify READY, search, filters, Merge, ratings, covers, sharing and restart
recovery. Copying a `.pack` directly into app-private storage bypasses installation
checks.
