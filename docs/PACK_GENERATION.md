# Pack generation and validation

The tools under `scripts/` build deterministic schema-2 data archives. The five official
pack definitions live in `scripts/packs/official-packs.json`; pack-specific conversion
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

For end-to-end confidence, install prepared packs through the app or an isolated test
harness and verify READY, search, filters, Merge, ratings, covers, sharing and restart
recovery. Copying a `.pack` directly into app-private storage bypasses installation
checks.
