import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

import generate_manga_catalog as tool


MANGA_A = "11111111-1111-4111-8111-111111111111"
MANGA_B = "22222222-2222-4222-8222-222222222222"
MANGA_C = "33333333-3333-4333-8333-333333333333"


def manga(
    manga_id=MANGA_A,
    title=None,
    year=2020,
    cover="cover-a.png",
):
    return {
        "id": manga_id,
        "type": "manga",
        "attributes": {
            "title": title or {"en": "English Title", "ja": "原作", "ja-ro": "Gensaku"},
            "altTitles": [
                {"es": "Otro título"},
                {"fr": "Otro título"},
                {"en": "English Title"},
                {"ja": "原作"},
            ],
            "year": year,
            "status": "completed",
            "originalLanguage": "ja",
            "publicationDemographic": "seinen",
            "tags": [
                {"id": "tag-2", "attributes": {"name": {"en": "Drama"}}},
                {"id": "tag-1", "attributes": {"name": {"ja": "アクション", "en": "Action"}}},
                {"id": "tag-3", "attributes": {"name": {"fr": "Mystère"}}},
                {"id": "tag-4", "attributes": {"name": {"en": "Drama"}}},
            ],
            # Tooling must ignore subordinate reading structures if a fixture contains them.
            "chapters": [{"id": "chapter-1"}],
            "volumes": {"1": {"count": 2}},
        },
        "relationships": [
            {"id": "cover-id", "type": "cover_art", "attributes": {"fileName": cover}},
            {"id": "author-id", "type": "author", "attributes": {"name": "Writer"}},
            {"id": "artist-id", "type": "artist", "attributes": {"name": "Artist"}},
            {"id": "artist-id-2", "type": "artist", "attributes": {"name": "Artist"}},
            {"id": "group-id", "type": "scanlation_group", "attributes": {"name": "Ignored"}},
        ],
    }


def relation(target_id, relation_type):
    return {
        "id": f"relation-{target_id}",
        "type": "manga_relation",
        "attributes": {"relation": relation_type},
        "relationships": [{"id": target_id, "type": "manga", "attributes": {}}],
    }


def relations(*entries):
    return {"result": "ok", "response": "collection", "data": list(entries)}


class GenerateMangaCatalogTest(unittest.TestCase):
    def test_stable_id_and_provider_id(self):
        record = tool.map_manga(manga())

        self.assertEqual(f"manga:mangadex:{MANGA_A}", record["id"])
        self.assertEqual(MANGA_A, record["providerId"])
        self.assertEqual("manga", record["mediaType"])
        self.assertEqual("mangadex", record["provider"])

    def test_canonical_title_prefers_english(self):
        attributes = manga()["attributes"]

        self.assertEqual("English Title", tool.resolve_canonical_title(attributes))

    def test_title_fallback_prefers_romanized_then_original_then_language_key(self):
        self.assertEqual(
            "Romanized",
            tool.resolve_canonical_title(
                {"title": {"ja": "原作", "ja-ro": "Romanized"}, "originalLanguage": "ja"}
            ),
        )
        self.assertEqual(
            "原作",
            tool.resolve_canonical_title({"title": {"ja": "原作"}, "originalLanguage": "ja"}),
        )
        self.assertEqual(
            "Deutsch",
            tool.resolve_canonical_title({"title": {"fr": "Français", "de": "Deutsch"}}),
        )

    def test_aliases_preserve_language_and_deduplicate_titles(self):
        record = tool.map_manga(manga())

        self.assertEqual(
            [{"title": "Otro título", "language": "es", "kind": None}],
            record["aliases"],
        )
        self.assertEqual("原作", record["originalTitle"])

    def test_tags_map_to_deterministic_visible_genres(self):
        record = tool.map_manga(manga())

        self.assertEqual(["Action", "Drama", "Mystère"], record["genres"])

    def test_author_artist_and_cover_relationships_are_mapped(self):
        record = tool.map_manga(manga())

        self.assertEqual(
            [
                {"role": "ARTIST", "name": "Artist"},
                {"role": "AUTHOR", "name": "Writer"},
            ],
            record["credits"],
        )
        self.assertEqual("cover-a.png", record["remoteCoverRef"])

    def test_metadata_maps_without_chapter_or_volume_items(self):
        record = tool.map_manga(manga())

        self.assertEqual(2020, record["year"])
        self.assertEqual("completed", record["status"])
        self.assertEqual("ja", record["originalLanguage"])
        self.assertEqual("seinen", record["demographic"])
        self.assertEqual("MANGA", record["format"])
        self.assertIsNone(record["startDate"])
        self.assertNotIn("chapters", record)
        self.assertNotIn("volumes", record)

    def test_relation_allowlist_matches_verified_official_enum_subset(self):
        self.assertEqual(
            {
                "alternate_version",
                "colored",
                "monochrome",
                "preserialization",
                "serialization",
            },
            set(tool.VARIANT_RELATION_ALLOWLIST),
        )

    def test_variant_edges_form_deterministic_connected_component(self):
        resources = {
            MANGA_A: manga(MANGA_A, {"en": "Alpha"}, year=2020),
            MANGA_B: manga(MANGA_B, {"en": "Beta"}, year=2018),
            MANGA_C: manga(MANGA_C, {"en": "Gamma"}, year=None),
        }
        records = [tool.map_manga(resources[manga_id]) for manga_id in sorted(resources)]
        groups = tool.build_variant_groups(
            records,
            {
                MANGA_A: relations(relation(MANGA_B, "colored")),
                MANGA_B: relations(relation(MANGA_C, "serialization")),
            },
        )

        self.assertEqual(1, len(groups))
        group = groups[0]
        digest = hashlib.sha256(
            ("mangadex-variants-v1\n" + "\n".join([MANGA_A, MANGA_B, MANGA_C])).encode()
        ).hexdigest()
        self.assertEqual(f"manga:mangadex:variants:{digest}", group["id"])
        self.assertEqual(digest, group["providerGroupId"])
        self.assertEqual("Alpha", group["displayTitle"])
        self.assertEqual("VARIANTS", group["groupType"])
        self.assertIsNone(group["remoteCoverRef"])
        self.assertEqual(
            [
                f"manga:mangadex:{MANGA_B}",
                f"manga:mangadex:{MANGA_A}",
                f"manga:mangadex:{MANGA_C}",
            ],
            [member["mediaId"] for member in group["members"]],
        )
        self.assertEqual([0, 1, 2], [member["position"] for member in group["members"]])

    def test_broader_relations_and_unselected_targets_do_not_create_groups(self):
        records = [tool.map_manga(manga(MANGA_A)), tool.map_manga(manga(MANGA_B))]
        broad = [
            "prequel", "sequel", "main_story", "side_story", "spin_off",
            "same_franchise", "shared_universe", "adapted_from", "based_on",
            "doujinshi", "alternate_story",
        ]
        response = relations(*(relation(MANGA_B, value) for value in broad))
        response["data"].append(relation(MANGA_C, "monochrome"))

        self.assertEqual([], tool.build_variant_groups(records, {MANGA_A: response}))

    def test_artifacts_are_deterministic_and_block8_compatible(self):
        resources = {MANGA_A: manga(), MANGA_B: manga(MANGA_B, {"en": "Second"}, 2021)}
        relation_map = {MANGA_A: relations(relation(MANGA_B, "alternate_version"))}
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            first_result = tool.generate_artifacts(resources, relation_map, Path(first), "fixture-v1")
            second_result = tool.generate_artifacts(resources, relation_map, Path(second), "fixture-v1")

            self.assertEqual(
                (Path(first) / "manga_catalog.jsonl.gz").read_bytes(),
                (Path(second) / "manga_catalog.jsonl.gz").read_bytes(),
            )
            self.assertEqual(
                (Path(first) / "manga_groups.jsonl.gz").read_bytes(),
                (Path(second) / "manga_groups.jsonl.gz").read_bytes(),
            )
            self.assertEqual(first_result["catalogManifest"], second_result["catalogManifest"])
            self.assertEqual(2, first_result["catalogManifest"]["itemCount"])
            self.assertEqual(1, first_result["groupManifest"]["groupCount"])
            with gzip.open(Path(first) / "manga_catalog.jsonl.gz", "rt", encoding="utf-8") as source:
                record = json.loads(source.readline())

        self.assertEqual(
            {
                "id", "mediaType", "provider", "providerId", "title", "originalTitle",
                "year", "startDate", "format", "status", "originalLanguage", "demographic",
                "partNumber", "popularity", "popularityRank", "remoteCoverRef", "genres",
                "credits", "aliases",
            },
            set(record),
        )

    def test_discovery_cli_requires_a_positive_explicit_limit(self):
        with self.assertRaises(SystemExit):
            tool.parse_args(["--discover", "--output-dir", "out", "--catalog-version", "v1"])
        args = tool.parse_args(
            ["--discover", "--limit", "25", "--output-dir", "out", "--catalog-version", "v1"]
        )
        self.assertEqual(25, args.limit)


if __name__ == "__main__":
    unittest.main()
