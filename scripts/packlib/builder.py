from __future__ import annotations

import copy
import json
from pathlib import Path
import shutil

from .common import canonical_json, sha256_file, temporary_directory, write_deterministic_zip
from .legacy_adapter import convert_legacy_archive


def _resolve(path: str, *, project_root: Path, legacy_root: Path) -> Path:
    expanded = path.replace("${PROJECT_ROOT}", str(project_root)).replace(
        "${LEGACY_ROOT}", str(legacy_root)
    )
    return Path(expanded).expanduser().resolve()


def _copy_declared_assets(config: dict, staging: Path, project_root: Path,
                          legacy_root: Path) -> dict[str, Path]:
    result: dict[str, Path] = {}
    for entry in config.get("assets", []):
        source = _resolve(entry["source"], project_root=project_root, legacy_root=legacy_root)
        if not source.is_file():
            if entry.get("optional", False):
                continue
            raise FileNotFoundError(f"Required pack asset is missing: {source}")
        target = staging / ("asset-" + str(len(result)))
        shutil.copyfile(source, target)
        result[entry["target"]] = target
    return result


def _copy_schema2_inputs(config: dict, staging: Path, project_root: Path,
                         legacy_root: Path) -> tuple[dict[str, Path], dict]:
    result: dict[str, Path] = {}
    for source_name, target_name in config["adapter"]["files"].items():
        source = _resolve(source_name, project_root=project_root, legacy_root=legacy_root)
        if not source.is_file():
            raise FileNotFoundError(f"Schema-2 input is missing: {source}")
        target = staging / ("input-" + str(len(result)))
        shutil.copyfile(source, target)
        result[target_name] = target
    return result, {
        "sourceAdapter": "schema2_jsonl",
        "targetSchemaVersion": 2,
        "itemCount": None,
        "groupCount": None,
        "relationshipCount": None,
        "notes": ["Records copied without transformation; the independent validator supplies counts."],
    }


def _optional_share_assets(config: dict, staging: Path, project_root: Path) -> tuple[dict[str, Path], dict]:
    pack_id = config["packId"]
    asset_dir = project_root / "scripts" / "pack-assets" / f"pack-{pack_id}"
    result: dict[str, Path] = {}
    presentation: dict[str, str] = {}
    for key, filename in (("topSeven", "share-top-seven.webp"), ("topTen", "share-top-ten.webp")):
        source = asset_dir / filename
        if source.is_file():
            target_name = f"metadata/share/{filename}"
            target = staging / filename
            shutil.copyfile(source, target)
            result[target_name] = target
            presentation[key] = target_name
    return result, presentation


def _manifest(config: dict, files: dict[str, Path], share_backgrounds: dict[str, str]) -> dict:
    declared = [
        {"path": path, "bytes": file.stat().st_size, "sha256": sha256_file(file)}
        for path, file in sorted(files.items())
    ]
    presentation = copy.deepcopy(config["presentation"])
    presentation["shareBackgrounds"] = share_backgrounds
    adapter = config["adapter"]
    catalog = adapter.get("catalogTargets") or [adapter["catalogTarget"]]
    groups = adapter.get("groupTargets")
    if groups is None:
        groups = [adapter["groupsTarget"]] if adapter.get("groupsTarget") else []
    return {
        "schemaVersion": 2,
        "packId": config["packId"],
        "version": config["version"],
        "minRankOfflineVersion": config.get("minRankOfflineVersion", "0.2.2"),
        "displayName": config["displayName"],
        "presentation": presentation,
        "capabilities": config["capabilities"],
        "fields": config["fields"],
        "filters": config["filters"],
        "subtitle": config["subtitle"],
        "merge": config["merge"],
        "defaults": config["defaults"],
        "rating": config["rating"],
        "coverSources": config["coverSources"],
        "catalog": catalog,
        "groups": groups,
        "covers": [],
        "attribution": config["attribution"],
        "integrity": {
            "installedBytes": sum(entry["bytes"] for entry in declared),
            "files": declared,
        },
    }


def build_pack(config: dict, *, output: Path, project_root: Path,
               legacy_root: Path) -> dict:
    adapter_type = config["adapter"].get("type", "legacy_schema1")
    source_archive: Path | None = None
    if adapter_type == "legacy_schema1":
        source_archive = (legacy_root / config["sourceArchive"]).resolve()
        if not source_archive.is_file():
            raise FileNotFoundError(
                f"Legacy source archive not found: {source_archive}. "
                "Pass --legacy-root or set RANKOFFLINE_LEGACY_ROOT."
            )
    with temporary_directory() as temporary:
        staging = Path(temporary)
        if adapter_type == "legacy_schema1":
            files, conversion = convert_legacy_archive(require_not_none(source_archive), config, staging)
        elif adapter_type == "schema2_jsonl":
            files, conversion = _copy_schema2_inputs(config, staging, project_root, legacy_root)
        else:
            raise ValueError(f"Unsupported input adapter: {adapter_type}")
        files.update(_copy_declared_assets(config, staging, project_root, legacy_root))
        share_files, share_backgrounds = _optional_share_assets(config, staging, project_root)
        files.update(share_files)
        conversion_path = staging / "conversion-report.json"
        conversion_path.write_bytes(canonical_json(conversion, newline=True))
        files["metadata/conversion-report.json"] = conversion_path
        manifest = _manifest(config, files, share_backgrounds)
        write_deterministic_zip(output, files, canonical_json(manifest, newline=True))
    return {
        "packId": config["packId"],
        "displayName": config["displayName"],
        "version": config["version"],
        "fileName": output.name,
        "archiveBytes": output.stat().st_size,
        "archiveSha256": sha256_file(output),
        "manifest": manifest,
        "conversion": conversion,
    }


def require_not_none(value: Path | None) -> Path:
    if value is None:
        raise ValueError("Legacy adapter requires sourceArchive")
    return value
