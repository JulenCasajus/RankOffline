#!/usr/bin/env python3
"""Materialize group summaries in explicit group artifacts before publication.

This does not discover relationships. Provider generators remain responsible for the explicit
member graph; this release-time step validates it and writes the summary Android queries directly.
It is intentionally idempotent so it can also upgrade cached format-v1 production artifacts.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
from typing import Any, Iterable

GROUP_FORMAT_VERSION = 2


def _records(path: Path) -> Iterable[dict[str, Any]]:
    with gzip.open(path, "rt", encoding="utf-8") as source:
        for line_number, line in enumerate(source, 1):
            if not line.strip():
                continue
            value = json.loads(line)
            if not isinstance(value, dict):
                raise ValueError(f"Expected object at {path}:{line_number}")
            yield value


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def precompute(catalog_path: Path, groups_path: Path, manifest_path: Path) -> dict[str, Any]:
    items = {record["id"]: record for record in _records(catalog_path)}
    groups: list[dict[str, Any]] = []
    for group in _records(groups_path):
        members = sorted(group["members"], key=lambda value: (value["position"], value["mediaId"]))
        if len(members) < 2 or len({member["mediaId"] for member in members}) != len(members):
            raise ValueError(f"Invalid explicit membership for {group.get('id')}")
        missing = [member["mediaId"] for member in members if member["mediaId"] not in items]
        if missing:
            raise ValueError(f"Unknown member {missing[0]} in {group.get('id')}")
        member_items = [items[member["mediaId"]] for member in members]
        popularity = [item["popularity"] for item in member_items if item.get("popularity") is not None]
        ranks = [item["popularityRank"] for item in member_items if item.get("popularityRank") is not None]
        group["representativeMediaId"] = members[0]["mediaId"]
        group["memberCount"] = len(members)
        group["popularity"] = max(popularity) if popularity else None
        group["popularityRank"] = min(ranks) if ranks else None
        group["members"] = members
        groups.append(group)

    temporary = groups_path.with_name(groups_path.name + ".tmp")
    try:
        with temporary.open("wb") as raw:
            with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
                for group in groups:
                    compressed.write(
                        json.dumps(
                            group, ensure_ascii=False, separators=(",", ":"), allow_nan=False
                        ).encode("utf-8") + b"\n"
                    )
        os.replace(temporary, groups_path)
    finally:
        if temporary.exists():
            temporary.unlink()

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["formatVersion"] = GROUP_FORMAT_VERSION
    manifest["groupCount"] = len(groups)
    manifest["membershipCount"] = sum(group["memberCount"] for group in groups)
    manifest["sha256"] = _sha256(groups_path)
    encoded = json.dumps(manifest, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n"
    temporary_manifest = manifest_path.with_name(manifest_path.name + ".tmp")
    try:
        temporary_manifest.write_text(encoded, encoding="utf-8")
        os.replace(temporary_manifest, manifest_path)
    finally:
        if temporary_manifest.exists():
            temporary_manifest.unlink()
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--groups", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    manifest = precompute(args.catalog, args.groups, args.manifest)
    print(f"Materialized {manifest['groupCount']} groups at format {manifest['formatVersion']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
