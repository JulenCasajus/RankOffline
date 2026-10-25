import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from tools.anime_aliases.generate_anime_aliases import (
    ALIAS_KIND,
    LICENSE,
    MAX_ALIASES,
    SOURCE,
    SOURCE_ASSET_SHA256,
    SOURCE_ORIGINAL_PROJECT,
    SOURCE_RELEASE,
    SourceEntry,
    build_source_index,
    generate,
    generate_records,
    normalize_aliases,
    write_outputs,
)


def source_line(title, sources, synonyms=()):
    return json.dumps({"sources": list(sources), "title": title, "synonyms": list(synonyms)})


class AnimeAliasGeneratorTest(unittest.TestCase):
    def test_mal_exact_id_match_without_title_matching(self):
        index = build_source_index([source_line("Different", ["https://myanimelist.net/anime/1"], ["Alias"])])
        records, audit = generate_records(
            [
                {"id": "mal:1", "title": "Canonical", "malId": 1},
                {"id": "mal:2", "title": "Different", "malId": 2},
            ],
            index,
        )
        self.assertEqual(["mal:1"], [record["animeId"] for record in records])
        self.assertEqual(1, audit.unmatchedAnimeCount)

    def test_anilist_exact_id_fallback(self):
        index = build_source_index([source_line("Source", ["https://anilist.co/anime/22"], ["Alt"])])
        records, _ = generate_records([{"id": "anilist:22", "title": "Canonical", "anilistId": 22}], index)
        self.assertEqual("anilist:22", records[0]["animeId"])

    def test_both_provider_ids_agree(self):
        index = build_source_index(
            [source_line("Source", ["https://myanimelist.net/anime/3", "https://anilist.co/anime/4"])]
        )
        records, audit = generate_records(
            [{"id": "mal:3", "title": "Canonical", "malId": 3, "anilistId": 4}], index
        )
        self.assertEqual(1, len(records))
        self.assertEqual(0, audit.conflictCount)

    def test_conflicting_provider_ids_reject_match(self):
        index = build_source_index(
            [
                source_line("MAL", ["https://myanimelist.net/anime/5"]),
                source_line("AniList", ["https://anilist.co/anime/6"]),
            ]
        )
        records, audit = generate_records(
            [{"id": "mal:5", "title": "Canonical", "malId": 5, "anilistId": 6}], index
        )
        self.assertEqual([], records)
        self.assertEqual(1, audit.conflictCount)

    def test_duplicate_provider_id_is_ambiguous(self):
        index = build_source_index(
            [
                source_line("One", ["https://myanimelist.net/anime/7"]),
                source_line("Two", ["https://myanimelist.net/anime/7"]),
            ]
        )
        records, _ = generate_records([{"id": "mal:7", "title": "Canonical", "malId": 7}], index)
        self.assertEqual([], records)

    def test_source_title_and_synonyms_normalize_dedupe_and_preserve_unicode(self):
        aliases, truncated = normalize_aliases(
            "Shingeki no Kyojin",
            SourceEntry(1, " Attack on Titan ", ("attack on titan", "進撃の巨人", " ", "Shingeki no Kyojin")),
        )
        self.assertFalse(truncated)
        self.assertEqual(
            [
                {"title": "Attack on Titan", "language": None, "kind": ALIAS_KIND},
                {"title": "進撃の巨人", "language": None, "kind": ALIAS_KIND},
            ],
            aliases,
        )

    def test_alias_cap_is_deterministic_and_keeps_source_title_first(self):
        synonyms = tuple(f"Alias {number}" for number in range(60))
        aliases, truncated = normalize_aliases("Canonical", SourceEntry(1, "Source title", synonyms))
        self.assertTrue(truncated)
        self.assertEqual(MAX_ALIASES, len(aliases))
        self.assertEqual("Source title", aliases[0]["title"])
        self.assertEqual("Alias 48", aliases[-1]["title"])

    def test_output_gzip_hash_and_manifest_are_deterministic(self):
        records = [{"animeId": "mal:1", "aliases": [{"title": "別名", "language": None, "kind": ALIAS_KIND}]}]
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            one = write_outputs(Path(first), records, "asset.jsonl.zst", SOURCE_RELEASE, SOURCE_ASSET_SHA256)
            two = write_outputs(Path(second), records, "asset.jsonl.zst", SOURCE_RELEASE, SOURCE_ASSET_SHA256)
            one_bytes = (Path(first) / "anime_aliases.jsonl.gz").read_bytes()
            two_bytes = (Path(second) / "anime_aliases.jsonl.gz").read_bytes()
            self.assertEqual(one_bytes, two_bytes)
            self.assertEqual(hashlib.sha256(one_bytes).hexdigest(), one["sha256"])
            self.assertEqual(one, two)
            with gzip.open(Path(first) / "anime_aliases.jsonl.gz", "rt", encoding="utf-8") as stream:
                self.assertEqual(records[0], json.loads(stream.readline()))

    def test_manifest_contains_pinned_source_and_license_provenance(self):
        with tempfile.TemporaryDirectory() as output:
            manifest = write_outputs(Path(output), [], "anime-offline-database.jsonl.zst", SOURCE_RELEASE, SOURCE_ASSET_SHA256)
        self.assertEqual(SOURCE, manifest["source"])
        self.assertEqual(SOURCE_ORIGINAL_PROJECT, manifest["sourceOriginalProject"])
        self.assertEqual(SOURCE_RELEASE, manifest["sourceRelease"])
        self.assertEqual(SOURCE_ASSET_SHA256, manifest["sourceAssetSha256"])
        self.assertEqual(LICENSE, manifest["license"])

    def test_generate_verifies_source_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            catalog = root / "catalog.json"
            source = root / "source.jsonl"
            catalog.write_text("[]", encoding="utf-8")
            source.write_text(source_line("Title", []) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Source SHA-256 mismatch"):
                generate(catalog, source, root / "output", SOURCE_RELEASE, "0" * 64)


if __name__ == "__main__":
    unittest.main()
