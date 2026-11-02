#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sys
from urllib.parse import quote, urlsplit
import zipfile

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent
sys.path.insert(0, str(SCRIPT_DIR))

from packlib import validate_pack  # noqa: E402
from packlib.common import canonical_json  # noqa: E402


class ReleasePreparationError(ValueError):
    pass


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def prepare_release(source: Path, output: Path, base_url: str,
                    apk: Path | None = None, apk_name: str | None = None) -> dict:
    parsed = urlsplit(base_url.rstrip("/"))
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ReleasePreparationError("base URL must be anonymous HTTPS")
    if parsed.query or parsed.fragment or parsed.port not in (None, 443):
        raise ReleasePreparationError("base URL contains unsupported components")

    index_path = source / "pack-index.json"
    root = json.loads(index_path.read_text(encoding="utf-8"))
    if set(root) != {"schemaVersion", "generatedBy", "packs"} or root["schemaVersion"] != 1:
        raise ReleasePreparationError("unsupported source pack index")
    if not isinstance(root["packs"], list) or not root["packs"]:
        raise ReleasePreparationError("source pack index is empty")

    output.mkdir(parents=True, exist_ok=True)
    prepared = []
    expected_keys = {
        "packId", "version", "displayName", "fileName", "archiveBytes",
        "archiveSha256", "registryId", "archiveUrl", "manifest",
    }
    for entry in sorted(root["packs"], key=lambda value: value["packId"]):
        if set(entry) != expected_keys:
            raise ReleasePreparationError("unexpected pack-index descriptor fields")
        file_name = entry["fileName"]
        if not isinstance(file_name, str) or Path(file_name).name != file_name or not file_name.endswith(".pack"):
            raise ReleasePreparationError("unsafe pack file name")
        archive = source / file_name
        if not archive.is_file() or archive.is_symlink():
            raise ReleasePreparationError(f"missing regular archive: {file_name}")
        validation = validate_pack(archive, expected_pack_id=entry["packId"])
        with zipfile.ZipFile(archive) as package:
            embedded_manifest = json.loads(package.read("manifest.json"))
        actual_hash = file_sha256(archive)
        if (archive.stat().st_size != entry["archiveBytes"] or
                actual_hash != entry["archiveSha256"] or
                validation["version"] != entry["version"] or
                validation["displayName"] != entry["displayName"] or
                embedded_manifest != entry["manifest"]):
            raise ReleasePreparationError(f"stale descriptor for {file_name}")
        destination = output / file_name
        shutil.copyfile(archive, destination)
        if destination.stat().st_size != entry["archiveBytes"] or file_sha256(destination) != actual_hash:
            raise ReleasePreparationError(f"copied archive changed: {file_name}")
        descriptor = dict(entry)
        descriptor["archiveUrl"] = f"{base_url.rstrip('/')}/{quote(file_name)}"
        prepared.append(descriptor)

    release_index = {
        "schemaVersion": 1,
        "generatedBy": "RankOffline release preparation",
        "packs": prepared,
    }
    (output / "pack-index.json").write_bytes(canonical_json(release_index, newline=True))
    if apk is not None:
        if not apk.is_file() or apk.is_symlink() or not apk_name or Path(apk_name).name != apk_name:
            raise ReleasePreparationError("invalid APK input or output name")
        shutil.copyfile(apk, output / apk_name)
    return release_index


def main() -> int:
    parser = argparse.ArgumentParser(description="Prepare immutable pack assets and a remote HTTPS index")
    parser.add_argument("--source", type=Path, default=PROJECT_ROOT / "build" / "pack-output")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--apk-name")
    args = parser.parse_args()
    try:
        index = prepare_release(args.source.resolve(), args.output.resolve(), args.base_url,
                                args.apk.resolve() if args.apk else None, args.apk_name)
    except (OSError, KeyError, TypeError, json.JSONDecodeError, zipfile.BadZipFile,
            ReleasePreparationError) as error:
        print(f"RELEASE PREPARATION FAILED: {error}", file=sys.stderr)
        return 1
    for entry in index["packs"]:
        print(f"{entry['fileName']}: {entry['archiveBytes']} bytes {entry['archiveSha256']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
