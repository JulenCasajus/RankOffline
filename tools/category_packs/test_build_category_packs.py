import hashlib
import json
import unittest
import zipfile
import struct
import tempfile
from pathlib import Path

import build_category_packs as packs


class CategoryPackBuildTest(unittest.TestCase):
    def test_optional_share_background_is_declared_and_hashed_as_metadata(self):
        previous = packs.SHARE_BACKGROUNDS
        try:
            with tempfile.TemporaryDirectory() as temp:
                packs.SHARE_BACKGROUNDS = Path(temp)
                payload = b"\x89PNG\r\n\x1a\n" + b"\x00\x00\x00\rIHDR" + struct.pack(">II", 1080, 1920)
                (packs.SHARE_BACKGROUNDS / "movies.png").write_bytes(payload)
                prepared = packs.optional_share_background("movies")
                self.assertEqual([("metadata/share_background.png", payload)], prepared)
                specs = [{"path": prepared[0][0], "bytes": len(payload), "sha256": packs.bytes_sha256(payload)}]
                manifest = packs.pack_manifest("movies", specs)
                self.assertEqual("metadata/share_background.png", manifest["presentation"]["shareBackground"])
        finally:
            packs.SHARE_BACKGROUNDS = previous

    def test_share_background_uses_its_eight_megabyte_limit(self):
        previous = packs.SHARE_BACKGROUNDS
        try:
            with tempfile.TemporaryDirectory() as temp:
                packs.SHARE_BACKGROUNDS = Path(temp)
                source = packs.SHARE_BACKGROUNDS / "anime.png"
                header = b"\x89PNG\r\n\x1a\n" + b"\x00\x00\x00\rIHDR" + struct.pack(">II", 1080, 1920)
                source.write_bytes(header)
                with source.open("ab") as target:
                    target.truncate(3 * 1024 * 1024)
                self.assertEqual(3 * 1024 * 1024, len(packs.optional_share_background("anime")[0][1]))

                with source.open("ab") as target:
                    target.truncate(8 * 1024 * 1024 + 1)
                with self.assertRaises(ValueError):
                    packs.optional_share_background("anime")
        finally:
            packs.SHARE_BACKGROUNDS = previous

    def test_official_pack_defaults_match_runtime_policy(self):
        manifests = {
            pack_id: packs.pack_manifest(pack_id, [])
            for pack_id in packs.FILES
        }

        for pack_id, manifest in manifests.items():
            defaults = manifest["defaults"]
            self.assertTrue(defaults["stickyScoreHeader"])
            self.assertEqual(pack_id != "books", defaults["showImages"])
            self.assertEqual(pack_id == "series", defaults["mergeEnabled"])
        self.assertTrue(manifests["series"]["defaults"]["oneCoverPerGroup"])

    def test_build_is_deterministic_and_index_matches_archives(self):
        first = packs.build()
        first_hashes = {
            entry["packId"]: hashlib.sha256(
                (packs.OUTPUT / entry["archive"]).read_bytes()
            ).hexdigest()
            for entry in first["packs"]
        }
        second = packs.build()

        self.assertEqual(first, second)
        self.assertEqual(set(packs.FILES), {entry["packId"] for entry in second["packs"]})
        for entry in second["packs"]:
            archive = packs.OUTPUT / entry["archive"]
            self.assertEqual(entry["downloadBytes"], archive.stat().st_size)
            self.assertEqual(entry["archiveSha256"], first_hashes[entry["packId"]])
            self.assertEqual(13, entry["minimumDatabaseVersion"])
            self.assertEqual(1, entry["manifest"]["schemaVersion"])
            with zipfile.ZipFile(archive) as zipped:
                self.assertEqual(
                    {"manifest.json"} | {
                        item["path"] for item in entry["manifest"]["integrity"]["files"]
                    },
                    set(zipped.namelist()),
                )
                self.assertEqual(entry["manifest"], json.loads(zipped.read("manifest.json")))

        self.assertEqual(
            json.loads(packs.INDEX_ASSET.read_text()),
            json.loads((packs.OUTPUT / "category_pack_index.json").read_text()),
        )


if __name__ == "__main__":
    unittest.main()
