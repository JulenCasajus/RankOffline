import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from packlib.pack_identity import (  # noqa: E402
    check_published_revision, content_fingerprint, next_pack_id,
    next_publish_version, parse_version, validate_identity, validate_registry,
)


class PackIdentityTests(unittest.TestCase):
    def test_suffix_and_canonical_slug(self):
        self.assertEqual(("animes-v7", 1), parse_version("animes-v7-v1"))
        self.assertEqual(("animeeee------", 1), parse_version("animeeee-------v1"))
        self.assertEqual(("animes-2027-02-10", 2), parse_version("animes-2027-02-10-v2"))
        self.assertEqual(("animes_pablo", 124), parse_version("animes_pablo-v124"))
        for value in ("Animes-v1", "animes-v0", "animes-v01", "animes-v", "-v1"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                parse_version(value)
        with self.assertRaises(ValueError):
            validate_identity(6, "animes", "mangas-v1")

    def test_registry_reserves_ids_and_slugs_without_public_names(self):
        records = [{"packId": 1, "slug": "animes"},
                   {"packId": 2, "slug": "animes_pablo"}]
        validate_registry(records)
        self.assertEqual(3, next_pack_id(records))
        self.assertEqual("animes_pablo-v1", next_publish_version("animes_pablo"))
        self.assertEqual("animes-v9", next_publish_version("animes", 8))
        self.assertEqual(4, next_pack_id(records + [
            {"packId": 3, "slug": "withdrawn", "withdrawn": True}]))
        with self.assertRaises(ValueError):
            validate_registry(records + [{"packId": 3, "slug": "animes"}])
        with self.assertRaises(ValueError):
            validate_registry(records + [{"packId": 2, "slug": "third"}])

    def test_builds_do_not_consume_revision_and_same_revision_cannot_change_content(self):
        manifest = {"packId": 1, "slug": "animes", "version": "animes-v5",
                    "catalog": ["catalog/a"], "displayName": "Anime"}
        records = [{"packId": 1, "slug": "animes", "publishedRevision": 5,
                    "publishedContentSha256": content_fingerprint(manifest)}]
        check_published_revision(manifest, records)
        check_published_revision(manifest, records)
        renamed = dict(manifest, catalog=["catalog/b"])
        with self.assertRaises(ValueError):
            check_published_revision(renamed, records)
        check_published_revision(dict(renamed, version="animes-v6"), records)
        with self.assertRaises(ValueError):
            check_published_revision(dict(renamed, version="animes-v7"), records)

    def test_official_registry_reserves_five_ids(self):
        path = Path(__file__).resolve().parents[1] / "packs" / "pack-registry.json"
        records = json.loads(path.read_text(encoding="utf-8"))["packs"]
        validate_registry(records)
        self.assertEqual(6, next_pack_id(records))

    def test_animes_new_install_defaults_to_merge(self):
        path = Path(__file__).resolve().parents[1] / "packs" / "official-packs.json"
        config = json.loads(path.read_text(encoding="utf-8"))
        anime = next(pack for pack in config["packs"] if pack["packId"] == 1)
        self.assertTrue(anime["defaults"]["mergeEnabled"])


if __name__ == "__main__":
    unittest.main()
