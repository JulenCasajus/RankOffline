from __future__ import annotations

import copy
import gzip
from pathlib import Path
import sys

SCRIPT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from packlib import PackValidationError, validate_pack  # noqa: E402
from pack_fixtures import PackFixtureTestCase, base_manifest, encoded, make_pack


class PackValidationTests(PackFixtureTestCase):
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

    def test_declarative_metadata_cover_source_is_accepted(self) -> None:
        item = encoded({
            "id": "item:1", "title": "Item", "provider": "fixture_provider",
            "remoteCoverRef": "cover:v1|remote_id=42",
        }) + b"\n"
        payloads = {"catalog/items.jsonl.gz": gzip.compress(item, compresslevel=9, mtime=0)}
        manifest = base_manifest(payloads)
        manifest["capabilities"]["covers"] = True
        manifest["attribution"]["sources"] = [{
            "id": "fixture_source", "name": "Fixture source", "url": "https://example.test",
            "notices": [],
        }]
        manifest["coverSources"] = [{
            "id": "fixture_metadata", "sourceId": "fixture_source",
            "providerId": "fixture_provider", "urlTemplates": [],
            "allowedHosts": ["images.example.test"],
            "metadataRequest": {
                "referenceKey": "remote_id", "method": "GET",
                "urlTemplate": "https://api.example.test/items/{reference}",
                "imageJsonPaths": ["data.image"], "requestIntervalMillis": 1000,
            },
        }]
        make_pack(self.path, manifest, payloads)
        self.assertEqual(1, validate_pack(self.path)["items"])

    def test_dynamic_metadata_host_is_rejected(self) -> None:
        manifest = copy.deepcopy(self.manifest)
        manifest["capabilities"]["covers"] = True
        manifest["attribution"]["sources"] = [{
            "id": "fixture_source", "name": "Fixture source", "url": "https://example.test",
            "notices": [],
        }]
        manifest["coverSources"] = [{
            "id": "fixture_metadata", "sourceId": "fixture_source",
            "providerId": "fixture_provider", "urlTemplates": [],
            "allowedHosts": ["images.example.test"],
            "metadataRequest": {
                "referenceKey": "remote_id", "method": "GET",
                "urlTemplate": "https://{reference}.example.test/items",
                "imageJsonPaths": ["data.image"], "requestIntervalMillis": 1000,
            },
        }]
        make_pack(self.path, manifest, self.payloads)
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
