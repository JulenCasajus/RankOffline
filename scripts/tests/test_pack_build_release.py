from __future__ import annotations

import json
from pathlib import Path
import sys

SCRIPT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from packlib import build_pack, validate_pack  # noqa: E402
from prepare_pack_release import ReleasePreparationError, prepare_release  # noqa: E402
from pack_fixtures import PackFixtureTestCase, make_pack


class PackBuildReleaseTests(PackFixtureTestCase):
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

    def test_release_index_uses_the_exact_validated_archive(self) -> None:
        make_pack(self.path, self.manifest, self.payloads)
        validation = validate_pack(self.path, expected_pack_id=99)
        source = Path(self.temporary.name) / "source"
        output = Path(self.temporary.name) / "release"
        source.mkdir()
        archive = source / "fixture.pack"
        self.path.replace(archive)
        source_index = {
            "schemaVersion": 1,
            "generatedBy": "test",
            "packs": [{
                "packId": 99, "version": "fixture-1", "displayName": "Fixture",
                "fileName": "fixture.pack", "archiveBytes": validation["archiveBytes"],
                "archiveSha256": validation["archiveSha256"], "registryId": "fixture-99",
                "archiveUrl": None, "manifest": self.manifest,
            }],
        }
        (source / "pack-index.json").write_text(json.dumps(source_index), encoding="utf-8")
        prepared = prepare_release(source, output, "https://example.test/releases/v1")
        self.assertEqual(
            "https://example.test/releases/v1/fixture.pack",
            prepared["packs"][0]["archiveUrl"],
        )
        self.assertEqual(archive.read_bytes(), (output / "fixture.pack").read_bytes())

    def test_release_preparation_rejects_stale_hash(self) -> None:
        make_pack(self.path, self.manifest, self.payloads)
        validation = validate_pack(self.path, expected_pack_id=99)
        source = Path(self.temporary.name) / "source"
        source.mkdir()
        archive = source / "fixture.pack"
        self.path.replace(archive)
        source_index = {
            "schemaVersion": 1, "generatedBy": "test", "packs": [{
                "packId": 99, "version": "fixture-1", "displayName": "Fixture",
                "fileName": "fixture.pack", "archiveBytes": validation["archiveBytes"],
                "archiveSha256": "0" * 64, "registryId": "fixture-99",
                "archiveUrl": None, "manifest": self.manifest,
            }],
        }
        (source / "pack-index.json").write_text(json.dumps(source_index), encoding="utf-8")
        with self.assertRaises(ReleasePreparationError):
            prepare_release(source, Path(self.temporary.name) / "release", "https://example.test/v1")
