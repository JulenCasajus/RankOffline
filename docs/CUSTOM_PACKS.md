# Custom packs

RankOffline v0.2.2 includes a reusable schema-2 builder and validator. The tool
can accept a standalone declarative JSON configuration through:

```bash
python3 scripts/build_packs.py --config /path/to/pack-config.json \
  --output build/pack-output
```

The included legacy adapter is specifically for the five audited v0.2.1 source
archives. A new category should provide its own input adapter and reuse the
generic manifest, integrity, deterministic ZIP and validation layers;
provider/category algorithms do not belong in the generic builder.

A valid custom pack must:

- use a stable positive numeric `packId`, never zero;
- contain only data/resources declared and hashed by `manifest.json`;
- use generic catalog/group format 2 records without a repeated `packId`;
- declare cover hosts/templates and all provider/dataset attribution itself;
- keep optional Top 7 and Top 10 backgrounds separate;
- include no code, nested archives, unsafe paths or cleartext URLs;
- satisfy the parser limits and cross-reference checks.

Passing the Python validator is necessary but is not a publication certificate.
Run the real Kotlin parser/importer on the complete Android project before
calling a package installable. Public local import, publisher signatures and a
remote registry remain future work.

See [PACK_GENERATION.md](PACK_GENERATION.md) for commands, asset names and the
official conversion audit.
