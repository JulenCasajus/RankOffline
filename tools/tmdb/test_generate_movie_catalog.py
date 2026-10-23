import contextlib
import gzip
import io
import json
from pathlib import Path
import tempfile
import unittest

import generate_movie_catalog as tool


def movie(movie_id=550, release_date="1999-10-15", collection=None):
    return {
        "id": movie_id,
        "title": "Fight Club",
        "original_title": "Fight Club Original",
        "release_date": release_date,
        "status": "Released",
        "original_language": "en",
        "popularity": 42.5,
        "poster_path": "/poster.jpg",
        "genres": [{"name": "Drama"}, {"name": "Drama"}, {"name": "Thriller"}],
        "production_companies": [{"name": "Fox 2000 Pictures"}, {"name": ""}],
        "credits": {
            "crew": [
                {"job": "Director", "name": "David Fincher"},
                {"job": "Producer", "name": "Other"},
                {"job": "Director", "name": "David Fincher"},
            ]
        },
        "alternative_titles": {
            "titles": [
                {"iso_3166_1": "ES", "title": "El club de la lucha"},
                {"iso_3166_1": "MX", "title": "El club de la lucha"},
                {"iso_3166_1": "US", "title": "Fight Club"},
            ]
        },
        "belongs_to_collection": collection,
    }


class GenerateMovieCatalogTest(unittest.TestCase):
    def test_movie_details_map_to_stable_id_and_block8_fields(self):
        record, _ = tool.map_movie_details(movie())

        self.assertEqual("movie:tmdb:550", record["id"])
        self.assertEqual("movie", record["mediaType"])
        self.assertEqual("tmdb", record["provider"])
        self.assertEqual("550", record["providerId"])
        self.assertEqual(1999, record["year"])
        self.assertEqual("1999-10-15", record["startDate"])
        self.assertEqual("MOVIE", record["format"])
        self.assertEqual(42.5, record["popularity"])
        self.assertIsNone(record["popularityRank"])
        self.assertEqual("/poster.jpg", record["remoteCoverRef"])
        self.assertEqual(
            {
                "id", "mediaType", "provider", "providerId", "title", "originalTitle",
                "year", "startDate", "format", "status", "originalLanguage",
                "demographic", "partNumber", "popularity", "popularityRank",
                "remoteCoverRef", "genres", "credits", "aliases",
            },
            set(record),
        )

    def test_genres_and_small_credit_set_are_deterministic(self):
        record, _ = tool.map_movie_details(movie())

        self.assertEqual(["Drama", "Thriller"], record["genres"])
        self.assertEqual(
            [
                {"role": "DIRECTOR", "name": "David Fincher"},
                {"role": "PRODUCTION_COMPANY", "name": "Fox 2000 Pictures"},
            ],
            record["credits"],
        )

    def test_alternative_titles_are_unique_and_exclude_canonical_titles(self):
        record, _ = tool.map_movie_details(movie())

        self.assertEqual(
            [{"title": "El club de la lucha", "language": None, "kind": "ES"}],
            record["aliases"],
        )

    def test_collection_membership_uses_only_canonical_tmdb_metadata(self):
        collection = {"id": 123, "name": "Collection", "poster_path": "/collection.jpg"}
        _, membership = tool.map_movie_details(movie(collection=collection))

        self.assertEqual(123, membership.collection_id)
        self.assertEqual("movie:tmdb:550", membership.media_id)
        self.assertEqual("/collection.jpg", membership.collection_poster_path)

    def test_collection_groups_require_two_selected_members(self):
        one = tool.CollectionMembership(1, "movie:tmdb:1", "2000-01-01", 10, "Saga", "/saga.jpg")
        two = tool.CollectionMembership(2, "movie:tmdb:2", "2001-01-01", 10, "Saga", "/saga.jpg")
        singleton = tool.CollectionMembership(3, "movie:tmdb:3", "1990-01-01", 20, "Solo", None)

        groups = tool.build_collection_groups([one, two, singleton])

        self.assertEqual(1, len(groups))
        self.assertEqual("movie:tmdb:collection:10", groups[0]["id"])
        self.assertEqual("COLLECTION", groups[0]["groupType"])
        self.assertEqual("movie:tmdb:1", groups[0]["representativeMediaId"])
        self.assertEqual(2, groups[0]["memberCount"])

    def test_collection_members_sort_by_release_date_then_id_with_null_last(self):
        members = [
            tool.CollectionMembership(9, "movie:tmdb:9", None, 10, "Saga", None),
            tool.CollectionMembership(3, "movie:tmdb:3", "2000-01-01", 10, "Saga", None),
            tool.CollectionMembership(2, "movie:tmdb:2", "2000-01-01", 10, "Saga", None),
        ]

        group = tool.build_collection_groups(members)[0]

        self.assertEqual(
            ["movie:tmdb:2", "movie:tmdb:3", "movie:tmdb:9"],
            [member["mediaId"] for member in group["members"]],
        )
        self.assertEqual([0, 1, 2], [member["position"] for member in group["members"]])

    def test_output_gzip_and_hashes_are_deterministic(self):
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            details = {550: movie()}
            first_result = tool.generate_artifacts(details, Path(first), "fixture-v1")
            second_result = tool.generate_artifacts(details, Path(second), "fixture-v1")

            first_bytes = (Path(first) / "movie_catalog.jsonl.gz").read_bytes()
            second_bytes = (Path(second) / "movie_catalog.jsonl.gz").read_bytes()
            self.assertEqual(first_bytes, second_bytes)
            self.assertEqual(
                first_result["catalogManifest"]["sha256"],
                second_result["catalogManifest"]["sha256"],
            )
            with gzip.open(Path(first) / "movie_catalog.jsonl.gz", "rt", encoding="utf-8") as source:
                parsed = json.loads(source.readline())
            self.assertEqual("movie:tmdb:550", parsed["id"])
            self.assertEqual(1, first_result["catalogManifest"]["itemCount"])

    def test_generated_output_and_progress_never_contain_token(self):
        token = "secret-token-that-must-not-appear"
        stream = io.StringIO()

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def read(self, _):
                return json.dumps(movie()).encode()

        requests = []

        def opener(request, timeout):
            requests.append((request, timeout))
            return Response()

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            client = tool.TmdbClient(token, root / "cache", opener=opener, sleep=lambda _: None)
            details, _ = tool.enrich_movies([550], client, workers=1, progress_stream=stream)
            tool.generate_artifacts(details, root / "output", "v1")
            combined = stream.getvalue() + "".join(
                path.read_bytes().decode("latin1") for path in (root / "output").iterdir()
            )
        self.assertEqual(f"Bearer {token}", requests[0][0].get_header("Authorization"))
        self.assertNotIn(token, combined)

    def test_daily_export_orders_popularity_and_excludes_adult_and_video(self):
        rows = [
            {"id": 1, "adult": True, "video": False},
            {"id": 2, "adult": False, "video": True},
            {"id": 3, "adult": False, "video": False, "popularity": 2.0},
            {"id": 4, "popularity": 9.0},
            {"id": 5, "popularity": 9.0},
        ]
        with tempfile.TemporaryDirectory() as directory:
            export = Path(directory) / "movie_ids.json.gz"
            with gzip.open(export, "wt", encoding="utf-8") as target:
                for row in rows:
                    target.write(json.dumps(row) + "\n")
            selected = list(tool.read_daily_export(export, limit=2))

        self.assertEqual([4, 5], selected)

    def test_generation_assigns_snapshot_rank_without_changing_provider_popularity(self):
        details = {2: movie(2), 1: movie(1)}
        with tempfile.TemporaryDirectory() as directory:
            result = tool.generate_artifacts(details, Path(directory), "v1", [2, 1])
        self.assertEqual([2, 1], [int(row["providerId"]) for row in result["records"]])
        self.assertEqual([1, 2], [row["popularityRank"] for row in result["records"]])

    def test_unavailable_export_ids_are_backfilled_with_contiguous_snapshot_ranks(self):
        popularity_order = [1, 2, 3, 4]
        details = {1: movie(1), 3: movie(3), 4: movie(4)}

        selected = tool._available_target_ids(popularity_order, details, target_count=3)
        with tempfile.TemporaryDirectory() as directory:
            result = tool.generate_artifacts(
                {movie_id: details[movie_id] for movie_id in selected},
                Path(directory),
                "v1",
                selected,
            )

        self.assertEqual([1, 3, 4], selected)
        self.assertEqual([1, 2, 3], [row["popularityRank"] for row in result["records"]])

    def test_insufficient_availability_buffer_fails_instead_of_emitting_short_catalog(self):
        with self.assertRaisesRegex(tool.TmdbToolError, "increase --availability-buffer"):
            tool._available_target_ids([1, 2], {1: movie(1)}, target_count=2)

    def test_successful_cache_is_reused_and_corrupt_cache_is_refetched(self):
        class Response:
            def __init__(self, payload):
                self.payload = payload

            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def read(self, _):
                return self.payload

        calls = []

        def opener(request, timeout):
            calls.append((request.full_url, timeout, request.headers))
            return Response(json.dumps(movie()).encode())

        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            client = tool.TmdbClient("test-token", cache, opener=opener, sleep=lambda _: None)
            self.assertFalse(client.movie_details(550).cached)
            self.assertTrue(client.movie_details(550).cached)
            (cache / "movie" / "550.json").write_text("broken", encoding="utf-8")
            self.assertFalse(client.movie_details(550).cached)

        self.assertEqual(2, len(calls))
        self.assertTrue(all(call[0].startswith(tool.API_BASE + "/3/movie/550?") for call in calls))


if __name__ == "__main__":
    unittest.main()
