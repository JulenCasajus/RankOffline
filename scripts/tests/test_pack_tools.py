from __future__ import annotations

import copy
import gzip
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

SCRIPT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from packlib import PackValidationError, build_pack, validate_pack  # noqa: E402


def encoded(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def base_manifest(payloads: dict[str, bytes]) -> dict:
    files = [
        {"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        for name, data in sorted(payloads.items())
    ]
    return {
        "schemaVersion": 2,
        "packId": 99,
        "version": "fixture-1",
        "minRankOfflineVersion": "0.2.2",
        "displayName": "Fixture",
        "presentation": {"names": {"en": "Fixture"}, "accentColor": "#123456", "shareBackgrounds": {}},
        "capabilities": {"search": True, "filters": False, "covers": False, "popularity": False, "merge": False, "ratings": True},
        "fields": [],
        "filters": [],
        "subtitle": [],
        "merge": {"enabled": False, "strategy": "NONE", "groupType": None, "labels": {}},
        "defaults": {"showImages": False, "stickyScoreHeader": True, "mergeEnabled": False, "ratingMode": "simple"},
        "rating": {
            "categories": [{"id": "overall", "name": "Overall", "description": "", "weight": 100.0, "color": "#123456", "order": 0, "active": True}],
            "profiles": [{"id": "general", "name": "General", "weights": {"overall": 100.0}}],
        },
        "coverSources": [],
        "catalog": ["catalog/items.jsonl.gz"],
        "groups": [],
        "covers": [],
        "attribution": {
            "publisher": {"name": "Fixture publisher"},
            "sources": [], "licences": [], "datasets": [], "notices": [], "legalFiles": [],
        },
        "integrity": {"installedBytes": sum(len(data) for data in payloads.values()), "files": files},
    }


def make_pack(path: Path, manifest: dict, payloads: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("manifest.json", encoded(manifest))
        for name, data in payloads.items():
            archive.writestr(name, data)


class PackToolTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.path = Path(self.temporary.name) / "fixture.pack"
        item = encoded({"id": "item:1", "title": "Item"}) + b"\n"
        self.payloads = {"catalog/items.jsonl.gz": gzip.compress(item, compresslevel=9, mtime=0)}
        self.manifest = base_manifest(self.payloads)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_valid_minimal_pack_is_accepted(self) -> None:
        make_pack(self.path, self.manifest, self.payloads)
        self.assertEqual(1, validate_pack(self.path, expected_pack_id=99)["items"])

    def test_schema_one_is_rejected(self) -> None:
        manifest = copy.deepcopy(self.manifest)
        manifest["schemaVersion"] = 1
        make_pack(self.path, manifest, self.payloads)
        with self.assertRaises(PackValidationError):
            validate_pack(self.path)

    def test_tampered_payload_hash_is_rejected(self) -> None:
        manifest = copy.deepcopy(self.manifest)
        manifest["integrity"]["files"][0]["sha256"] = "0" * 64
        make_pack(self.path, manifest, self.payloads)
        with self.assertRaises(PackValidationError):
            validate_pack(self.path)

    def test_executable_suffix_is_rejected(self) -> None:
        payloads = dict(self.payloads)
        payloads["metadata/payload.jar"] = b"PK\x03\x04"
        manifest = base_manifest(payloads)
        make_pack(self.path, manifest, payloads)
        with self.assertRaises(PackValidationError):
            validate_pack(self.path)

    def test_missing_group_member_is_rejected(self) -> None:
        group = encoded({
            "id": "group:1",
            "members": [{"mediaId": "item:missing", "position": 0}],
            "groupType": "fixture",
        }) + b"\n"
        payloads = dict(self.payloads)
        payloads["groups/groups.jsonl.gz"] = gzip.compress(group, compresslevel=9, mtime=0)
        manifest = base_manifest(payloads)
        manifest["groups"] = ["groups/groups.jsonl.gz"]
        make_pack(self.path, manifest, payloads)
        with self.assertRaises(PackValidationError):
            validate_pack(self.path)

    def test_generic_schema2_jsonl_adapter_builds_a_pack(self) -> None:
        source = Path(self.temporary.name) / "items.jsonl.gz"
        source.write_bytes(self.payloads["catalog/items.jsonl.gz"])
        config = {
            "packId": 99, "displayName": "Fixture", "version": "fixture-1",
            "minRankOfflineVersion": "0.2.2",
            "presentation": {"names": {"en": "Fixture"}, "accentColor": "#123456"},
            "capabilities": {"search": True, "filters": False, "covers": False, "popularity": False, "merge": False, "ratings": True},
            "fields": [], "filters": [], "subtitle": [],
            "merge": {"enabled": False, "strategy": "NONE", "groupType": None, "labels": {}},
            "defaults": {"showImages": False, "stickyScoreHeader": True, "mergeEnabled": False, "ratingMode": "simple"},
            "rating": self.manifest["rating"], "coverSources": [],
            "attribution": self.manifest["attribution"],
            "adapter": {
                "type": "schema2_jsonl",
                "files": {str(source): "catalog/items.jsonl.gz"},
                "catalogTargets": ["catalog/items.jsonl.gz"],
                "groupTargets": [],
            },
        }
        build_pack(
            config, output=self.path, project_root=Path(self.temporary.name),
            legacy_root=Path(self.temporary.name),
        )
        self.assertEqual(1, validate_pack(self.path, expected_pack_id=99)["items"])


if __name__ == "__main__":
    unittest.main()
