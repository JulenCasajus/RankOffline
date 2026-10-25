import hashlib
import json
import unittest
import zipfile

import build_category_packs as packs


class CategoryPackBuildTest(unittest.TestCase):
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
