from __future__ import annotations

import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

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

class PackFixtureTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.path = Path(self.temporary.name) / "fixture.pack"
        item = encoded({"id": "item:1", "title": "Item"}) + b"\n"
        self.payloads = {"catalog/items.jsonl.gz": gzip.compress(item, compresslevel=9, mtime=0)}
        self.manifest = base_manifest(self.payloads)

    def tearDown(self) -> None:
        self.temporary.cleanup()
