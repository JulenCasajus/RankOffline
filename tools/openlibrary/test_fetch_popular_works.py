import io
import json
from pathlib import Path
import tempfile
import unittest
import urllib.parse

import fetch_popular_works as tool


class PopularWorksTest(unittest.TestCase):
    def test_maps_only_work_ids_and_nonnegative_counts(self):
        self.assertEqual(
            {"workId": "OL1W", "readinglogCount": 12},
            tool.map_search_result({"key": "/works/OL1W", "readinglog_count": 12}),
        )
        self.assertEqual(
            {"workId": "OL2W", "readinglogCount": 0},
            tool.map_search_result({"key": "/works/OL2W", "readinglog_count": None}),
        )
        self.assertIsNone(tool.map_search_result({"key": "/books/OL1M"}))

    def test_preserves_precomputed_cover_identifiers(self):
        self.assertEqual(
            {
                "workId": "OL1W",
                "readinglogCount": 12,
                "coverId": "42",
                "isbn13": "9780140328721",
                "isbn10": "0140328726",
                "editionOlid": "OL7353617M",
            },
            tool.map_search_result(
                {
                    "key": "/works/OL1W",
                    "readinglog_count": 12,
                    "cover_i": 42,
                    "isbn": ["978-0-14-032872-1", "0140328726"],
                    "edition_key": ["invalid", "OL7353617M"],
                }
            ),
        )

    def test_cached_pages_produce_deterministic_ranked_selection(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            payload = {
                "numFound": 3,
                "docs": [
                    {"key": "/works/OL2W", "readinglog_count": 5},
                    {"key": "/works/OL1W", "readinglog_count": 5},
                    {"key": "/works/OL3W", "readinglog_count": 1},
                ]
            }
            (cache / "readinglog-cover-identifiers-v1-000000-1000.json").write_text(
                json.dumps(payload)
            )
            result = tool.fetch_popular_works(3, cache, progress_stream=io.StringIO())
            self.assertEqual(["OL1W", "OL2W", "OL3W"], [row["workId"] for row in result])
            self.assertEqual([1, 2, 3], [row["popularityRank"] for row in result])

    def test_live_request_shape_uses_supported_readinglog_sort_alias(self):
        requested = []

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def read(self, _):
                return b'{"docs":[]}'

        def opener(request, timeout):
            requested.append((request.full_url, timeout))
            return Response()

        tool._request_page(0, 2, opener, lambda _: None)

        query = urllib.parse.parse_qs(urllib.parse.urlparse(requested[0][0]).query)
        self.assertEqual(["*:*"], query["q"])
        self.assertEqual(["readinglog"], query["sort"])
        self.assertEqual(
            ["key,readinglog_count,cover_i,isbn,edition_key"],
            query["fields"],
        )

    def test_deep_selection_continues_by_complete_readinglog_count_buckets(self):
        records = [
            {"key": "/works/OL1W", "readinglog_count": 10},
            {"key": "/works/OL2W", "readinglog_count": 9},
            {"key": "/works/OL4W", "readinglog_count": 8},
            {"key": "/works/OL3W", "readinglog_count": 8},
            {"key": "/works/OL5W", "readinglog_count": 7},
            {"key": "/works/OL6W", "readinglog_count": 6},
        ]
        requested_queries = []

        class Response:
            def __init__(self, payload):
                self.payload = payload

            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def read(self, _):
                return json.dumps(self.payload).encode()

        def opener(request, timeout):
            self.assertEqual(30, timeout)
            query = urllib.parse.parse_qs(urllib.parse.urlparse(request.full_url).query)
            expression = query["q"][0]
            requested_queries.append(expression)
            if expression == "*:*":
                matching = records
            elif expression.startswith("readinglog_count:[1 TO "):
                upper = int(expression.removeprefix("readinglog_count:[1 TO ").removesuffix("]"))
                matching = [row for row in records if row["readinglog_count"] <= upper]
            else:
                exact = int(expression.removeprefix("readinglog_count:"))
                matching = [row for row in records if row["readinglog_count"] == exact]
            matching = sorted(
                matching, key=lambda row: (-row["readinglog_count"], row["key"])
            )
            offset = int(query["offset"][0])
            limit = int(query["limit"][0])
            return Response({"numFound": len(matching), "docs": matching[offset:offset + limit]})

        with tempfile.TemporaryDirectory() as directory:
            original_page_size = tool.PAGE_SIZE
            original_window = tool.SEARCH_WINDOW
            try:
                tool.PAGE_SIZE = 2
                tool.SEARCH_WINDOW = 4
                result = tool.fetch_popular_works(
                    5,
                    Path(directory),
                    opener=opener,
                    sleep=lambda _: None,
                    progress_stream=io.StringIO(),
                )
            finally:
                tool.PAGE_SIZE = original_page_size
                tool.SEARCH_WINDOW = original_window

        self.assertEqual(["OL1W", "OL2W", "OL3W", "OL4W", "OL5W"], [
            row["workId"] for row in result
        ])
        self.assertEqual([1, 2, 3, 4, 5], [row["popularityRank"] for row in result])
        self.assertIn("readinglog_count:8", requested_queries)
        self.assertIn("readinglog_count:[1 TO 7]", requested_queries)


if __name__ == "__main__":
    unittest.main()
