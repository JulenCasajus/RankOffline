import gzip
import json
from pathlib import Path
import tempfile
import unittest

import generate_series_catalog as tool


def series(seasons=None):
    return {
        "id": 1396,
        "name": "Breaking Bad",
        "original_name": "Original Breaking Bad",
        "status": "Ended",
        "original_language": "en",
        "popularity": 91.25,
        "poster_path": "/series.jpg",
        "genres": [{"name": "Drama"}, {"name": "Crime"}, {"name": "Drama"}],
        "production_companies": [{"name": "Sony Pictures Television"}],
        "networks": [{"name": "AMC"}, {"name": "AMC"}],
        "created_by": [{"name": "Vince Gilligan"}],
        "alternative_titles": {
            "results": [
                {"iso_3166_1": "ES", "title": "Breaking Bad: Química pura"},
                {"iso_3166_1": "MX", "title": "Breaking Bad: Química pura"},
                {"iso_3166_1": "US", "title": "Breaking Bad"},
            ]
        },
        "seasons": seasons
        if seasons is not None
        else [
            {
                "id": 900,
                "season_number": 0,
                "name": "Specials",
                "air_date": "2007-01-01",
                "poster_path": "/specials.jpg",
                "episode_count": 5,
            },
            {
                "id": 902,
                "season_number": 2,
                "name": "Season 2",
                "air_date": "2009-03-08",
                "poster_path": None,
                "episode_count": 13,
            },
            {
                "id": 901,
                "season_number": 1,
                "name": "Season 1",
                "air_date": "2008-01-20",
                "poster_path": "/season-1.jpg",
                "episode_count": 7,
            },
        ],
    }


class GenerateSeriesCatalogTest(unittest.TestCase):
    def test_stable_season_and_provider_ids(self):
        records, _ = tool.map_series_details(series())

        self.assertEqual("series:tmdb:1396:season:1", records[0]["id"])
        self.assertEqual("1396:season:1", records[0]["providerId"])
        self.assertEqual("series", records[0]["mediaType"])
        self.assertEqual("tmdb", records[0]["provider"])

    def test_season_zero_is_excluded(self):
        records, _ = tool.map_series_details(series())

        self.assertEqual([1, 2], [record["partNumber"] for record in records])
        self.assertNotIn("season:0", " ".join(record["id"] for record in records))

    def test_numbered_seasons_create_independent_catalog_records(self):
        records, _ = tool.map_series_details(series())

        self.assertEqual(2, len(records))
        self.assertEqual("Breaking Bad — Season 1", records[0]["title"])
        self.assertEqual("Breaking Bad — Season 2", records[1]["title"])
        self.assertEqual("Original Breaking Bad", records[0]["originalTitle"])
        self.assertEqual("TV_SEASON", records[0]["format"])

    def test_single_numbered_season_has_no_group(self):
        details = series(seasons=[{"season_number": 1, "name": "Season 1"}])

        records, group = tool.map_series_details(details)

        self.assertEqual(1, len(records))
        self.assertIsNone(group)

    def test_two_numbered_seasons_create_seasons_group(self):
        _, group = tool.map_series_details(series())

        self.assertEqual("series:tmdb:1396", group["id"])
        self.assertEqual("SEASONS", group["groupType"])
        self.assertEqual("1396", group["providerGroupId"])
        self.assertEqual("Breaking Bad", group["displayTitle"])
        self.assertEqual("/series.jpg", group["remoteCoverRef"])

    def test_group_members_are_ordered_and_positioned_by_season_number(self):
        _, group = tool.map_series_details(series())

        self.assertEqual(
            ["series:tmdb:1396:season:1", "series:tmdb:1396:season:2"],
            [member["mediaId"] for member in group["members"]],
        )
        self.assertEqual([1, 2], [member["position"] for member in group["members"]])

    def test_season_poster_falls_back_to_parent_and_missing_date_stays_null(self):
        details = series()
        details["seasons"][1]["air_date"] = None

        records, _ = tool.map_series_details(details)

        self.assertEqual("/season-1.jpg", records[0]["remoteCoverRef"])
        self.assertEqual("/series.jpg", records[1]["remoteCoverRef"])
        self.assertIsNone(records[1]["year"])
        self.assertIsNone(records[1]["startDate"])

    def test_parent_genres_credits_and_aliases_are_small_and_deterministic(self):
        records, _ = tool.map_series_details(series())

        self.assertEqual(["Drama", "Crime"], records[0]["genres"])
        self.assertEqual(
            [
                {"role": "CREATOR", "name": "Vince Gilligan"},
                {"role": "NETWORK", "name": "AMC"},
                {"role": "PRODUCTION_COMPANY", "name": "Sony Pictures Television"},
            ],
            records[0]["credits"],
        )
        self.assertEqual(
            [
                {
                    "title": "Breaking Bad: Química pura — Season 1",
                    "language": None,
                    "kind": "ES",
                }
            ],
            records[0]["aliases"],
        )

    def test_output_gzip_and_hashes_are_deterministic(self):
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            details = {1396: series()}
            first_result = tool.generate_artifacts(details, Path(first), "fixture-v1")
            second_result = tool.generate_artifacts(details, Path(second), "fixture-v1")

            self.assertEqual(
                (Path(first) / "series_catalog.jsonl.gz").read_bytes(),
                (Path(second) / "series_catalog.jsonl.gz").read_bytes(),
            )
            self.assertEqual(
                first_result["catalogManifest"]["sha256"],
                second_result["catalogManifest"]["sha256"],
            )
            self.assertEqual(2, first_result["catalogManifest"]["itemCount"])
            self.assertEqual(1, first_result["groupManifest"]["groupCount"])

    def test_catalog_record_fields_match_block8_format(self):
        with tempfile.TemporaryDirectory() as directory:
            result = tool.generate_artifacts({1396: series()}, Path(directory), "fixture-v1")
            with gzip.open(
                Path(directory) / "series_catalog.jsonl.gz", "rt", encoding="utf-8"
            ) as source:
                record = json.loads(source.readline())

        self.assertEqual(
            {
                "id", "mediaType", "provider", "providerId", "title", "originalTitle",
                "year", "startDate", "format", "status", "originalLanguage",
                "demographic", "partNumber", "popularity", "popularityRank",
                "remoteCoverRef", "genres", "credits", "aliases",
            },
            set(record),
        )
        self.assertEqual("series", result["catalogManifest"]["mediaType"])
        self.assertEqual("tmdb", result["catalogManifest"]["provider"])


if __name__ == "__main__":
    unittest.main()
