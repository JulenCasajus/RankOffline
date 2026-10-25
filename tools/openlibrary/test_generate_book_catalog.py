import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

import generate_book_catalog as tool


WORK_ID = "OL45804W"
SECOND_WORK_ID = "OL999W"
AUTHOR_ID = "OL23919A"


def work(work_id=WORK_ID, **overrides):
    value = {
        "key": f"/works/{work_id}",
        "type": {"key": "/type/work"},
        "title": "Fantastic Mr Fox",
        "subtitle": "A novel",
        "first_publish_date": "1970-06-01",
        "covers": [-1, 0, 12345, 99999],
        "subjects": ["Foxes", "Children's fiction", "foxes", "  Adventure  "],
        "authors": [
            {"author": {"key": f"/authors/{AUTHOR_ID}"}},
            {"author": {"key": "/authors/OL404A"}},
        ],
    }
    value.update(overrides)
    return value


def author(author_id=AUTHOR_ID, name="Roald Dahl"):
    return {
        "key": f"/authors/{author_id}",
        "type": {"key": "/type/author"},
        "name": name,
    }


def write_dump(path, rows):
    with gzip.open(path, "wt", encoding="utf-8", newline="") as output:
        for dump_type, key, payload in rows:
            if isinstance(payload, str):
                encoded = payload
            else:
                encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
            output.write(f"{dump_type}\t{key}\t1\t2026-01-01T00:00:00.000000\t{encoded}\n")


def read_records(path):
    with gzip.open(path, "rt", encoding="utf-8") as source:
        return [json.loads(line) for line in source]


class GenerateBookCatalogTest(unittest.TestCase):
    def test_work_maps_to_stable_book_identity_and_title(self):
        record = tool.map_work(work())

        self.assertEqual("book:openlibrary:OL45804W", record["id"])
        self.assertEqual(WORK_ID, record["providerId"])
        self.assertEqual("book", record["mediaType"])
        self.assertEqual("openlibrary", record["provider"])
        self.assertEqual("Fantastic Mr Fox", record["title"])
        self.assertEqual("BOOK", record["format"])
        self.assertEqual(
            "openlibrary:v1|id=12345|work=OL45804W",
            record["remoteCoverRef"],
        )
        self.assertIsNone(record["originalTitle"])
        self.assertIsNone(record["originalLanguage"])
        self.assertIsNone(record["popularity"])

    def test_subtitle_creates_only_a_conservative_combined_alias(self):
        record = tool.map_work(work())
        repeated = tool.map_work(work(title="A Novel Adventure", subtitle="novel"))

        self.assertEqual(
            [{"title": "Fantastic Mr Fox: A novel", "language": None, "kind": "SUBTITLE"}],
            record["aliases"],
        )
        self.assertEqual([], repeated["aliases"])

    def test_publication_date_parses_exact_iso_and_free_form_conservatively(self):
        self.assertEqual((1970, "1970-06-01"), tool.parse_first_publish_date("1970-06-01"))
        self.assertEqual((1950, None), tool.parse_first_publish_date("circa 1950"))
        self.assertEqual((2001, None), tool.parse_first_publish_date("Spring 2001"))
        self.assertEqual((1954, None), tool.parse_first_publish_date("1954?"))
        self.assertEqual((None, None), tool.parse_first_publish_date("unknown"))
        self.assertEqual((2020, None), tool.parse_first_publish_date("2020-99-99"))

    def test_first_positive_integer_cover_is_selected(self):
        self.assertEqual("12345", tool.select_cover_id([None, 0, -9, "44", True, 12345, 9]))
        self.assertIsNone(tool.select_cover_id([None, 0, -1, "44", False]))

    def test_cover_reference_preserves_runtime_candidates_in_priority_order(self):
        reference = tool.build_cover_reference(
            WORK_ID,
            [12345],
            {
                "coverId": "99999",
                "isbn13": "978-0-14-032872-1",
                "isbn10": "0140328726",
                "editionOlid": "OL7353617M",
            },
        )

        self.assertEqual(
            "openlibrary:v1|id=12345|isbn13=9780140328721|isbn10=0140328726|"
            "edition=OL7353617M|work=OL45804W",
            reference,
        )

    def test_popularity_selection_preserves_cover_metadata_for_generation(self):
        with tempfile.TemporaryDirectory() as directory:
            selection = Path(directory) / "selection.jsonl"
            selection.write_text(
                json.dumps(
                    {
                        "workId": WORK_ID,
                        "readinglogCount": 20,
                        "popularityRank": 1,
                        "coverId": "7",
                        "isbn13": "9780140328721",
                        "isbn10": "0140328726",
                        "editionOlid": "OL7353617M",
                    }
                )
                + "\n",
                encoding="utf-8",
            )

            self.assertEqual(
                {
                    WORK_ID: {
                        "coverId": "7",
                        "isbn13": "9780140328721",
                        "isbn10": "0140328726",
                        "editionOlid": "OL7353617M",
                    }
                },
                tool.read_cover_metadata_selection(selection),
            )

    def test_subjects_deduplicate_case_insensitively_and_cap_in_source_order(self):
        subjects = [" Drama ", "drama", "", None] + [f"Subject {index}" for index in range(60)]

        result = tool.normalize_subjects(subjects)

        self.assertEqual(50, len(result))
        self.assertEqual("Drama", result[0])
        self.assertEqual("Subject 48", result[-1])

    def test_author_dump_builds_reusable_disk_index_and_unknown_authors_are_omitted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            authors_dump = root / "authors.txt.gz"
            index_path = root / "authors.sqlite"
            write_dump(authors_dump, [("/type/author", f"/authors/{AUTHOR_ID}", author())])

            count = tool.build_author_index(authors_dump, index_path, batch_size=1)
            with tool.AuthorIndex(index_path) as index:
                record = tool.map_work(work(), author_name=index.name)

            self.assertEqual(1, count)
            self.assertEqual([{"role": "AUTHOR", "name": "Roald Dahl"}], record["credits"])

    def test_generation_without_author_enrichment_omits_credits_safely(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dump = root / "works.txt.gz"
            output = root / "out"
            write_dump(dump, [("/type/work", f"/works/{WORK_ID}", work())])

            result = tool.generate_catalog(dump, output, "fixture-v1", limit=1)
            record = read_records(output / "book_catalog.jsonl.gz")[0]

            self.assertFalse(result["authorEnriched"])
            self.assertEqual([], record["credits"])

    def test_strict_invalid_work_fails_atomically_while_skip_mode_continues(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dump = root / "works.txt.gz"
            strict_output = root / "strict"
            skip_output = root / "skip"
            invalid = work(title="   ")
            valid = work(SECOND_WORK_ID, title="Valid Work", subtitle=None)
            write_dump(
                dump,
                [
                    ("/type/work", f"/works/{WORK_ID}", invalid),
                    ("/type/work", f"/works/{SECOND_WORK_ID}", valid),
                ],
            )

            with self.assertRaises(tool.OpenLibraryToolError):
                tool.generate_catalog(dump, strict_output, "fixture-v1", limit=1)
            result = tool.generate_catalog(
                dump, skip_output, "fixture-v1", limit=1, skip_malformed=True
            )

            self.assertFalse((strict_output / "book_catalog.jsonl.gz").exists())
            self.assertEqual(1, result["itemCount"])
            self.assertEqual(1, result["skippedCount"])
            self.assertEqual("book:openlibrary:OL999W", read_records(
                skip_output / "book_catalog.jsonl.gz"
            )[0]["id"])

    def test_artifacts_are_deterministic_block8_compatible_and_have_no_groups(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dump = root / "works.txt.gz"
            first = root / "first"
            second = root / "second"
            write_dump(
                dump,
                [
                    ("/type/work", f"/works/{WORK_ID}", work()),
                    (
                        "/type/work",
                        f"/works/{SECOND_WORK_ID}",
                        work(SECOND_WORK_ID, title="Second", subtitle=None),
                    ),
                ],
            )

            first_result = tool.generate_catalog(dump, first, "fixture-v1", limit=2)
            second_result = tool.generate_catalog(dump, second, "fixture-v1", limit=2)
            catalog_bytes = (first / "book_catalog.jsonl.gz").read_bytes()
            manifest = json.loads((first / "book_catalog.manifest.json").read_text())
            record = read_records(first / "book_catalog.jsonl.gz")[0]

            self.assertEqual(catalog_bytes, (second / "book_catalog.jsonl.gz").read_bytes())
            self.assertEqual(first_result["catalogManifest"], second_result["catalogManifest"])
            self.assertEqual(hashlib.sha256(catalog_bytes).hexdigest(), manifest["sha256"])
            self.assertEqual(2, manifest["itemCount"])
            self.assertEqual("book", manifest["mediaType"])
            self.assertEqual("openlibrary", manifest["provider"])
            self.assertEqual(
                {
                    "id", "mediaType", "provider", "providerId", "title", "originalTitle",
                    "year", "startDate", "format", "status", "originalLanguage",
                    "demographic", "partNumber", "popularity", "popularityRank",
                    "remoteCoverRef", "genres", "credits", "aliases",
                },
                set(record),
            )
            self.assertFalse(any(first.glob("book_groups*")))

    def test_whitelist_accepts_olid_or_work_path_and_requires_all_selected_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            keys = root / "keys.txt"
            dump = root / "works.txt.gz"
            keys.write_text(f"{WORK_ID}\n/works/{SECOND_WORK_ID}\n", encoding="utf-8")
            write_dump(dump, [("/type/work", f"/works/{WORK_ID}", work())])

            selected = tool.read_work_keys(keys)

            self.assertEqual({WORK_ID, SECOND_WORK_ID}, selected)
            with self.assertRaisesRegex(tool.OpenLibraryToolError, "not found"):
                tool.generate_catalog(
                    dump,
                    root / "out",
                    "fixture-v1",
                    selected_work_ids=selected,
                )

    def test_unavailable_popular_works_use_ranked_candidates_and_contiguous_ranks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dump = root / "works.txt.gz"
            write_dump(
                dump,
                [
                    ("/type/work", "/works/OL1W", work("OL1W", title="One")),
                    ("/type/work", "/works/OL3W", work("OL3W", title="Three")),
                    ("/type/work", "/works/OL4W", work("OL4W", title="Four")),
                ],
            )
            popularity = {
                "OL1W": (100, 1),
                "OL2W": (90, 2),
                "OL3W": (80, 3),
                "OL4W": (70, 4),
            }

            selected, stats = tool.select_available_popularity(dump, popularity, 3)

            self.assertEqual(
                {"OL1W": (100, 1), "OL3W": (80, 2), "OL4W": (70, 3)},
                selected,
            )
            self.assertEqual(["OL2W"], stats["missingTopWorkIds"])
            self.assertEqual(["OL4W"], stats["replacementWorkIds"])

    def test_cli_is_bounded_by_default_and_full_mode_needs_two_explicit_flags(self):
        common = [
            "--works-dump", "works.gz", "--output-dir", "out", "--catalog-version", "v1"
        ]
        with self.assertRaises(SystemExit):
            tool.parse_args(common)
        with self.assertRaises(SystemExit):
            tool.parse_args(common + ["--all-from-dump"])
        args = tool.parse_args(common + ["--limit", "3"])
        full = tool.parse_args(common + ["--all-from-dump", "--confirm-large-run"])

        self.assertEqual(3, args.limit)
        self.assertTrue(full.all_from_dump)
        self.assertTrue(full.confirm_large_run)

    def test_irrelevant_rows_are_ignored_and_malformed_work_json_is_strict(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dump = root / "works.txt.gz"
            write_dump(
                dump,
                [
                    ("/type/author", f"/authors/{AUTHOR_ID}", author()),
                    ("/type/work", f"/works/{WORK_ID}", "{not-json"),
                ],
            )

            with self.assertRaisesRegex(tool.OpenLibraryToolError, "Malformed /type/work JSON"):
                list(tool.iter_dump_records(dump, "/type/work"))
            self.assertEqual([], list(tool.iter_dump_records(dump, "/type/work", True)))


if __name__ == "__main__":
    unittest.main()
