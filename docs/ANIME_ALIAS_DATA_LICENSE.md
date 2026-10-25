# Anime alias data provenance

`app/src/main/assets/anime_aliases.jsonl.gz` is a derived title-alias database.
It is deliberately separate from RankOffline's application source code and
from the existing AnimeAPI catalog.

- Dataset: `anime-offline-database`
- Current continuation: [cedya77/anime-offline-database](https://github.com/cedya77/anime-offline-database)
- Original project: [manami-project/anime-offline-database](https://github.com/manami-project/anime-offline-database)
- Pinned release: `2026-42`
- Source asset: `anime-offline-database.jsonl.zst`
- Verified source SHA-256: `5d6fc7ed463c616f4efe6d35344d019f30fe0614fe183c4668a9dd4fe14d6728`
- Database license: [Open Database License 1.0 (ODbL)](https://opendatacommons.org/licenses/odbl/1-0/)
- Database contents license: [Database Contents License 1.0 (DbCL)](https://opendatacommons.org/licenses/dbcl/1-0/)

RankOffline uses only the source URLs, preferred title, and synonyms to derive
the alias database. Provider scores, tags, pictures, studios, popularity, and
other source metadata are excluded. This data notice does not change the
license of RankOffline application source code. Block 18 will expose the same
provenance in the application's attribution interface.
