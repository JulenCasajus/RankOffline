from __future__ import annotations

import re
import zipfile

from .validator_common import (
    _exact_keys, _fail, _https_url, _image_dimensions, _notice,
    _required_keys, _safe_path, _text,
)

def _validate_attribution(value: dict, declared: dict[str, dict], archive: zipfile.ZipFile) -> set[str]:
    _exact_keys(value, {"publisher", "sources", "licences", "datasets", "notices", "legalFiles"}, "attribution")
    _required_keys(value, {"publisher", "sources", "licences", "datasets", "notices", "legalFiles"}, "attribution")
    publisher = value["publisher"]
    _exact_keys(publisher, {"name", "url"}, "attribution.publisher")
    _text(publisher.get("name"), 160, "publisher name")
    if publisher.get("url") is not None:
        _https_url(publisher["url"], "publisher URL")
    if any(not isinstance(value[key], list) for key in ("sources", "licences", "datasets", "notices", "legalFiles")):
        _fail("Attribution collections must be arrays")
    if any(len(value[key]) > 64 for key in ("sources", "licences", "datasets", "legalFiles")) or len(value["notices"]) > 32:
        _fail("Too many attribution entries")
    source_ids: set[str] = set()
    for source in value["sources"]:
        _exact_keys(source, {"id", "name", "url", "notices", "logoPath"}, "attribution source")
        source_id = _text(source.get("id"), 64, "source ID")
        if not re.fullmatch(r"[a-z][a-z0-9_-]{0,63}", source_id) or source_id in source_ids:
            _fail(f"Invalid or duplicate attribution source ID: {source_id}")
        source_ids.add(source_id)
        _text(source.get("name"), 160, "source name")
        if source.get("url") is not None:
            _https_url(source["url"], "source URL")
        for notice in source.get("notices", []):
            _notice(notice, "source notice")
        logo = source.get("logoPath")
        if logo is not None:
            path = _safe_path(logo)
            if not path.startswith("metadata/attribution/") or not path.lower().endswith((".png", ".jpg", ".jpeg", ".webp")):
                _fail(f"Invalid attribution logo path: {path}")
            if path not in declared or not 1 <= declared[path]["bytes"] <= 2 * 1024 * 1024:
                _fail(f"Undeclared or oversized attribution logo: {path}")
            if _image_dimensions(archive.read(path)) is None:
                _fail(f"Unsupported or malformed attribution image: {path}")
    licence_ids: set[str] = set()
    for licence in value["licences"]:
        _exact_keys(licence, {"id", "name", "url", "textPath"}, "attribution licence")
        licence_id = _text(licence.get("id"), 64, "licence ID")
        if not re.fullmatch(r"[a-z][a-z0-9_-]{0,63}", licence_id) or licence_id in licence_ids:
            _fail(f"Invalid or duplicate licence ID: {licence_id}")
        licence_ids.add(licence_id)
        _text(licence.get("name"), 160, "licence name")
        if licence.get("url") is not None:
            _https_url(licence["url"], "licence URL")
        if licence.get("textPath") is not None:
            path = _legal_path(licence["textPath"], declared)
            _validate_plain_text(archive.read(path), path)
    for dataset in value["datasets"]:
        _exact_keys(dataset, {"name", "sourceIds", "licenceIds", "notices"}, "attribution dataset")
        _text(dataset.get("name"), 160, "dataset name")
        if len(dataset.get("sourceIds", [])) > 64 or set(dataset.get("sourceIds", [])) - source_ids:
            _fail("Dataset references an unknown source")
        if len(dataset.get("licenceIds", [])) > 64 or set(dataset.get("licenceIds", [])) - licence_ids:
            _fail("Dataset references an unknown licence")
        for notice in dataset.get("notices", []):
            _notice(notice, "dataset notice")
    for notice in value["notices"]:
        _notice(notice, "pack notice")
    legal = value["legalFiles"]
    if len(legal) != len(set(legal)):
        _fail("Duplicate legal file")
    for path in legal:
        validated = _legal_path(path, declared)
        _validate_plain_text(archive.read(validated), validated)
    return source_ids

def _legal_path(value: object, declared: dict[str, dict]) -> str:
    path = _safe_path(value)
    if not path.startswith("metadata/legal/") or not path.lower().endswith(".txt"):
        _fail(f"Invalid legal document path: {path}")
    if path not in declared or not 1 <= declared[path]["bytes"] <= 512 * 1024:
        _fail(f"Undeclared or oversized legal document: {path}")
    return path

def _validate_plain_text(data: bytes, path: str) -> None:
    try:
        data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise PackValidationError(f"Legal document is not UTF-8 text: {path}") from error
