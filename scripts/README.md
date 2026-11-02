# Project scripts

The schema-2 pack toolchain lives in `scripts/packlib/`. It is intentionally
independent from the Android runtime and uses only the Python standard library.
The Kotlin parser remains the executable authority; the Python validator mirrors
its current limits and adds archive, image-dimension and cross-reference checks.

```bash
# All five official packs
./scripts/build_5_packs.sh

# One official pack
python3 scripts/build_packs.py --pack 3

# Validate an existing artifact
python3 scripts/validate_pack.py build/pack-output/pack-3.pack

# Tool tests, including deliberately invalid packs
python3 -m unittest discover -s scripts/tests -v

# Compile and run the current Kotlin parsers against all generated packs
./scripts/validate_packs_with_kotlin.sh
```

Set `RANKOFFLINE_LEGACY_ROOT` or pass `--legacy-root` when the old project is not
located beside this repository. Generated archives and reports go to
`build/pack-output/` and are ignored by Git.

The old provider fetchers and schema-1 builder are not copied into this project.
The declarative configuration and conversion adapter read the already generated
source archives without downloading their datasets again. See
[`PACK_GENERATION.md`](../docs/PACK_GENERATION.md).

For new sources already expressed in the runtime JSONL contract, use an
`adapter` of type `schema2_jsonl` with a source-to-archive `files` map plus
`catalogTargets` and `groupTargets`. Provider-specific transformations stay in
separate adapters; hashing, deterministic ZIP creation and validation remain
shared.

Other scripts retain their existing purposes: Android resource migration,
project-tree documentation, Kotlin size checks and release bundle assembly.
