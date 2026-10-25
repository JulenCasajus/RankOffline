import argparse
import gzip
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

import prepare_pack_covers as covers


def write_jsonl_gzip(path: Path, values: list[dict]) -> None:
    with gzip.open(path, "wt", encoding="utf-8") as output:
        for value in values:
            output.write(json.dumps(value) + "\n")


def fake_png(path: Path, color: tuple[int, int, int] = (50, 100, 150)) -> None:
    def chunk(kind: bytes, value: bytes) -> bytes:
        return struct.pack(">I", len(value)) + kind + value + struct.pack(">I", zlib.crc32(kind + value))

    row = b"\x00" + bytes(color)
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(row))
        + chunk(b"IEND", b"")
    )


class PreparePackCoversTest(unittest.TestCase):
    def arguments(self, root: Path, catalog: Path, sources: Path, **overrides):
        values = dict(
            pack_id="movies",
            catalog=catalog,
            groups=None,
            sources=sources,
            output=root / "output",
            cache=root / "cache",
            limit=2,
            one_cover_per_group=False,
        )
        values.update(overrides)
        return argparse.Namespace(**values)

    def test_grouping_happens_before_limit_and_uses_first_ordered_member(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            catalog = root / "catalog.gz"
            groups = root / "groups.gz"
            source_file = root / "cover.png"
            sources = root / "sources.json"
            fake_png(source_file)
            write_jsonl_gzip(catalog, [
                {"id": "member:second", "title": "Second", "popularityRank": 1},
                {"id": "member:first", "title": "First", "popularityRank": 2},
                {"id": "ungrouped", "title": "Ungrouped", "popularityRank": 3},
            ])
            write_jsonl_gzip(groups, [{
                "id": "group:one",
                "displayTitle": "Group",
                "popularityRank": 1,
                "members": [
                    {"mediaId": "member:second", "position": 4},
                    {"mediaId": "member:first", "position": 0},
                ],
            }])
            sources.write_text(json.dumps({"covers": [
                {
                    "canonicalId": identity,
                    "localPath": str(source_file),
                    "redistributionAllowed": True,
                    "licenseEvidence": "fixture-license",
                }
                for identity in ("member:first", "ungrouped")
            ]}))

            report = covers.prepare(self.arguments(
                root, catalog, sources, groups=groups, one_cover_per_group=True
            ))

            self.assertEqual(
                ["member:first", "ungrouped"],
                [entry["canonicalId"] for entry in report["selected"]],
            )
            self.assertEqual(2, len(list((root / "output/covers").iterdir())))

    def test_unavailable_higher_rank_is_reported_and_replaced_deterministically(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            catalog = root / "catalog.gz"
            source_file = root / "cover.png"
            sources = root / "sources.json"
            fake_png(source_file)
            write_jsonl_gzip(catalog, [
                {"id": f"item:{index}", "title": f"Item {index}", "popularityRank": index}
                for index in range(1, 4)
            ])
            sources.write_text(json.dumps({"covers": [
                {
                    "canonicalId": f"item:{index}",
                    "localPath": str(source_file),
                    "redistributionAllowed": True,
                    "licenseEvidence": "fixture-license",
                }
                for index in (2, 3)
            ]}))

            report = covers.prepare(self.arguments(root, catalog, sources))

            self.assertEqual(["item:2", "item:3"], [x["canonicalId"] for x in report["selected"]])
            self.assertEqual(
                [{"canonicalId": "item:1", "reason": "no licensed source"}],
                report["skipped"],
            )

    def test_redistribution_evidence_is_mandatory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            catalog = root / "catalog.gz"
            sources = root / "sources.json"
            write_jsonl_gzip(catalog, [{"id": "item:1", "title": "One", "popularityRank": 1}])
            sources.write_text(json.dumps({"covers": [{
                "canonicalId": "item:1",
                "localPath": str(root / "missing.png"),
                "redistributionAllowed": False,
            }]}))

            with self.assertRaisesRegex(ValueError, "redistribution evidence"):
                covers.prepare(self.arguments(root, catalog, sources, limit=1))

    def test_one_hundred_licensed_synthetic_images_produce_one_hundred_entries(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            catalog = root / "catalog.gz"
            sources = root / "sources.json"
            rows = []
            source_rows = []
            for index in range(100):
                identity = f"item:{index:03d}"
                image = root / f"cover-{index:03d}.png"
                fake_png(image, (index, 100, 200))
                rows.append({"id": identity, "title": identity, "popularityRank": index + 1})
                source_rows.append({
                    "canonicalId": identity,
                    "localPath": str(image),
                    "redistributionAllowed": True,
                    "licenseEvidence": "synthetic-test-fixture",
                })
            write_jsonl_gzip(catalog, rows)
            sources.write_text(json.dumps({"covers": source_rows}))

            report = covers.prepare(self.arguments(root, catalog, sources, limit=100))

            self.assertEqual(100, len(report["selected"]))
            self.assertEqual(100, len(list((root / "output/covers").iterdir())))


if __name__ == "__main__":
    unittest.main()
