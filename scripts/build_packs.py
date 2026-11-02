#!/usr/bin/env python3
from __future__ import annotations

import argparse
import copy
import json
import os
from pathlib import Path
import sys

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent
sys.path.insert(0, str(SCRIPT_DIR))

from packlib import build_pack, validate_pack  # noqa: E402
from packlib.common import canonical_json  # noqa: E402


def deep_merge(base: dict, override: dict) -> dict:
    result = copy.deepcopy(base)
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(result.get(key), dict):
            result[key] = deep_merge(result[key], value)
        else:
            result[key] = copy.deepcopy(value)
    return result


def load_official_configs(path: Path) -> list[dict]:
    root = json.loads(path.read_text(encoding="utf-8"))
    fields = root["fieldDefinitions"]
    configs = []
    for pack in root["packs"]:
        config = deep_merge(root["common"], pack)
        config["fields"] = [fields[field_id] for field_id in config.pop("fieldIds")]
        configs.append(config)
    return configs


def main() -> int:
    parser = argparse.ArgumentParser(description="Build deterministic RankOffline schema-2 packs")
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--pack", type=int, choices=range(1, 6), help="official numeric pack ID")
    selection.add_argument("--config", type=Path, help="standalone declarative pack configuration")
    parser.add_argument("--all", action="store_true", help="build all five official packs")
    parser.add_argument("--output", type=Path, default=PROJECT_ROOT / "build" / "pack-output")
    parser.add_argument(
        "--legacy-root", type=Path,
        default=Path(os.environ.get("RANKOFFLINE_LEGACY_ROOT", PROJECT_ROOT.parents[1] / "AnimeRankOffline")),
        help="root of the old project containing the source .pack files",
    )
    args = parser.parse_args()
    if args.config:
        configs = [json.loads(args.config.read_text(encoding="utf-8"))]
    else:
        configs = load_official_configs(SCRIPT_DIR / "packs" / "official-packs.json")
        if args.pack:
            configs = [config for config in configs if config["packId"] == args.pack]
        elif not args.all:
            parser.error("choose --pack N, --all, or --config FILE")

    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    validations = []
    builds = []
    for config in configs:
        destination = output / config.get("fileName", f"pack-{config['packId']}.pack")
        built = build_pack(
            config, output=destination, project_root=PROJECT_ROOT,
            legacy_root=args.legacy_root.resolve(),
        )
        validation = validate_pack(destination, expected_pack_id=config["packId"])
        builds.append(built)
        validations.append(validation)
        print(
            f"pack-{config['packId']}: {validation['items']} items, "
            f"{validation['groups']} groups, {validation['archiveBytes']} bytes, "
            f"sha256={validation['archiveSha256']}"
        )

    index_entries = []
    for validation in sorted(validations, key=lambda entry: entry["packId"]):
        built = next(item for item in builds if item["packId"] == validation["packId"])
        index_entries.append({
            "packId": validation["packId"],
            "version": validation["version"],
            "displayName": validation["displayName"],
            "fileName": validation["archive"],
            "archiveBytes": validation["archiveBytes"],
            "archiveSha256": validation["archiveSha256"],
            "registryId": f"rankoffline-official-{validation['packId']}",
            "archiveUrl": None,
            "manifest": built["manifest"],
        })
    if len(configs) == 5:
        (output / "pack-index.json").write_bytes(canonical_json({
            "schemaVersion": 1,
            "generatedBy": "RankOffline schema-2 pack tools",
            "packs": index_entries,
        }, newline=True))
    (output / "validation-report.json").write_bytes(canonical_json({
        "schemaVersion": 1,
        "kotlinContractValidation": "pending",
        "validator": "python-schema2-contract",
        "packs": validations,
        "conversions": [entry["conversion"] for entry in builds],
    }, newline=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
