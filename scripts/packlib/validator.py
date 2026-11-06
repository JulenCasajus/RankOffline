from __future__ import annotations

import hashlib
import json
from pathlib import Path
import zipfile

from .common import sha256_file
from .validator_common import PackValidationError, _fail
from .validator_manifest import _validate_manifest
from .validator_records import _validate_records

def validate_pack(path: Path, *, expected_pack_id: int | None = None) -> dict:
    if not path.is_file() or not 1 <= path.stat().st_size <= 2 * 1024 * 1024 * 1024:
        _fail(f"Invalid pack archive length: {path}")
    try:
        archive = zipfile.ZipFile(path)
    except zipfile.BadZipFile as error:
        raise PackValidationError(f"Invalid ZIP archive: {path}") from error
    with archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        if len(entries) > 40_000 or len(names) != len(set(names)) or "manifest.json" not in names:
            _fail("ZIP has too many, duplicate or missing entries")
        if any(entry.is_dir() for entry in entries):
            _fail("Generated packs must not contain explicit directory entries")
        manifest_info = archive.getinfo("manifest.json")
        if not 1 <= manifest_info.file_size <= 2 * 1024 * 1024:
            _fail("Invalid manifest size")
        try:
            manifest = json.loads(archive.read("manifest.json").decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise PackValidationError("manifest.json is not strict UTF-8 JSON") from error
        if not isinstance(manifest, dict):
            _fail("manifest.json root must be an object")
        declared, fields = _validate_manifest(manifest, archive)
        if expected_pack_id is not None and manifest["packId"] != expected_pack_id:
            _fail(f"Expected packId {expected_pack_id}, found {manifest['packId']}")
        if set(names) != {"manifest.json", *declared.keys()}:
            _fail("ZIP entries differ from integrity.files")
        for name, spec in declared.items():
            info = archive.getinfo(name)
            if info.file_size != spec["bytes"]:
                _fail(f"ZIP size differs from manifest for {name}")
            data = archive.read(name)
            if hashlib.sha256(data).hexdigest() != spec["sha256"]:
                _fail(f"SHA-256 mismatch for {name}")
            if data.startswith((b"dex\n", b"\x7fELF", b"PK\x03\x04", b"\xca\xfe\xba\xbe")):
                _fail(f"Executable or nested archive payload detected: {name}")
        counts = _validate_records(archive, manifest, fields)
    return {
        "status": "valid",
        "validator": "python-schema2-contract",
        "packId": manifest["packId"],
        "displayName": manifest["displayName"],
        "version": manifest["version"],
        "archive": path.name,
        "archiveBytes": path.stat().st_size,
        "archiveSha256": sha256_file(path),
        "installedBytes": manifest["integrity"]["installedBytes"],
        **counts,
    }
