from __future__ import annotations

import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import zipfile

FIXED_ZIP_TIME = (1980, 1, 1, 0, 0, 0)
ITEM_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,191}\Z")
FIELD_ID = re.compile(r"[a-z][a-z0-9_]{0,63}\Z")


def canonical_json(value: object, *, newline: bool = False) -> bytes:
    encoded = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False
    ).encode("utf-8")
    return encoded + (b"\n" if newline else b"")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def deterministic_gzip_writer(path: Path):
    raw = path.open("wb")
    zipped = gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0)

    class Writer:
        def write(self, value: object) -> None:
            zipped.write(canonical_json(value, newline=True))

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            try:
                zipped.close()
            finally:
                raw.close()

    return Writer()


def write_deterministic_zip(output: Path, files: dict[str, Path], manifest: bytes) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        entries = {"manifest.json": manifest, **files}
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, FIXED_ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            payload = entries[name]
            if isinstance(payload, Path):
                with payload.open("rb") as source, archive.open(info, "w", force_zip64=True) as target:
                    for chunk in iter(lambda: source.read(1024 * 1024), b""):
                        target.write(chunk)
            else:
                archive.writestr(info, payload, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    os.replace(temporary, output)


def temporary_directory(prefix: str = "rankoffline-pack-"):
    return tempfile.TemporaryDirectory(prefix=prefix)


def chunks(values: list[object], size: int) -> list[list[object]]:
    return [values[index:index + size] for index in range(0, len(values), size)]


def clean_text(value: str) -> tuple[str, bool]:
    """Replace source controls/irregular whitespace without changing printable text."""
    separated = "".join(
        " " if character.isspace() or ord(character) < 32 or 127 <= ord(character) <= 159
        else character
        for character in value
    )
    cleaned = " ".join(separated.split())
    return cleaned, cleaned != value
