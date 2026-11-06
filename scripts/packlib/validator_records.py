from __future__ import annotations

import gzip
import io
import json
import math
import re
import zipfile
from urllib.parse import urlparse

from .common import FIELD_ID, ITEM_ID
from .validator_common import (
    GROUP_KEYS, ISO_DATE, MAX_SAFE_INTEGER, RECORD_KEYS, PackValidationError,
    _exact_keys, _fail, _https_url, _required_keys, _text,
)

def _attribute_value(value: object, field: dict, context: str) -> None:
    field_type = field["type"]
    if field_type in ("text", "choice", "date"):
        text = _text(value, 4096, context)
        if field_type == "date":
            import datetime
            if not ISO_DATE.fullmatch(text):
                _fail(f"Invalid date for {context}")
            try:
                datetime.date.fromisoformat(text)
            except ValueError as error:
                raise PackValidationError(f"Invalid date for {context}") from error
        if field_type == "choice" and field.get("options") and text not in field["options"]:
            _fail(f"Unknown choice for {context}")
    elif field_type == "integer":
        if type(value) is not int or abs(value) > MAX_SAFE_INTEGER:
            _fail(f"Invalid integer for {context}")
    elif field_type == "decimal":
        if type(value) not in (int, float) or not math.isfinite(value):
            _fail(f"Invalid decimal for {context}")
    elif field_type == "boolean" and type(value) is not bool:
        _fail(f"Invalid boolean for {context}")

def _validate_cover_ref(record: dict, manifest: dict, context: str) -> None:
    reference = record.get("remoteCoverRef")
    if reference is None or reference.startswith("covers/"):
        return
    sources = manifest["coverSources"]
    if reference.startswith("{"):
        try:
            explicit = json.loads(reference)
        except json.JSONDecodeError as error:
            raise PackValidationError(f"Malformed structured cover reference in {context}") from error
        _exact_keys(explicit, {"schemaVersion", "sourceId", "identity", "urls"}, "structured cover reference")
        if explicit.get("schemaVersion") != 1 or not isinstance(explicit.get("identity"), str) or not explicit["identity"].strip() or len(explicit["identity"]) > 512:
            _fail(f"Invalid structured cover reference in {context}")
        matches = [source for source in sources if source["id"] == explicit.get("sourceId")]
        if len(matches) != 1:
            _fail(f"Unknown structured cover source in {context}")
        allowed = set(matches[0]["allowedHosts"])
        if len(explicit.get("urls", [])) > 8:
            _fail(f"Too many cover fallback URLs in {context}")
        for url in explicit.get("urls", []):
            parsed = urlparse(_https_url(url, "explicit cover URL"))
            if parsed.hostname not in allowed or parsed.fragment:
                _fail(f"Unapproved explicit cover URL in {context}")
    else:
        provider = record.get("provider")
        if reference.startswith("cover:v1|"):
            values: dict[str, str] = {}
            for token in reference.removeprefix("cover:v1|").split("|"):
                key, separator, value = token.partition("=")
                if (not separator or not FIELD_ID.fullmatch(key) or
                        not re.fullmatch(r"[1-9][0-9]*", value) or key in values):
                    _fail(f"Invalid metadata cover reference in {context}")
                values[key] = value
            matches = [source for source in sources if source.get("providerId") == provider and
                       isinstance(source.get("metadataRequest"), dict) and
                       source["metadataRequest"].get("referenceKey") in values]
            if not matches:
                _fail(f"Metadata cover reference has no declared source in {context}")
            return
        matches = [source for source in sources if source.get("providerId") == provider and
                   source.get("metadataRequest") is None]
        if len(matches) != 1:
            _fail(f"Plain cover reference has no unique source in {context}")
        if "/" in reference or "\\" in reference:
            _fail(f"Plain cover reference cannot be encoded safely in {context}")

def _read_jsonl(archive: zipfile.ZipFile, path: str):
    encoded = archive.read(path)
    stream = gzip.GzipFile(fileobj=io.BytesIO(encoded)) if path.endswith(".jsonl.gz") else io.BytesIO(encoded)
    expanded = 0
    with stream:
        for line_number, raw in enumerate(stream, 1):
            expanded += len(raw)
            if expanded > 2 * 1024 * 1024 * 1024 or len(raw) > 256 * 1024:
                _fail(f"Oversized JSONL data at {path}:{line_number}")
            if not raw.rstrip(b"\r\n"):
                _fail(f"Empty JSONL line at {path}:{line_number}")
            try:
                yield line_number, json.loads(raw.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise PackValidationError(f"Invalid JSONL at {path}:{line_number}") from error

def _validate_records(archive: zipfile.ZipFile, manifest: dict, fields: dict[str, dict]) -> dict:
    item_ids: set[str] = set()
    alias_count = 0
    for path in manifest["catalog"]:
        if not path.endswith((".jsonl", ".jsonl.gz")):
            _fail(f"Unsupported catalog payload format: {path}")
        file_records = 0
        for line_number, record in _read_jsonl(archive, path):
            file_records += 1
            if not isinstance(record, dict):
                _fail(f"Catalog record must be an object at {path}:{line_number}")
            _exact_keys(record, RECORD_KEYS, f"catalog record {path}:{line_number}")
            _required_keys(record, {"id", "title"}, f"catalog record {path}:{line_number}")
            item_id = record["id"]
            if not isinstance(item_id, str) or not ITEM_ID.fullmatch(item_id) or item_id in item_ids:
                _fail(f"Invalid or duplicate item ID at {path}:{line_number}: {item_id}")
            item_ids.add(item_id)
            _text(record["title"], 500, "catalog title")
            provider = record.get("provider")
            if provider is not None and (not isinstance(provider, str) or not FIELD_ID.fullmatch(provider)):
                _fail(f"Invalid provider at {path}:{line_number}")
            if record.get("providerId") is not None:
                if provider is None:
                    _fail(f"providerId without provider at {path}:{line_number}")
                _text(record["providerId"], 1024, "providerId")
            popularity = record.get("popularity")
            if popularity is not None and (type(popularity) not in (int, float) or not math.isfinite(popularity) or popularity < 0):
                _fail(f"Invalid popularity at {path}:{line_number}")
            rank = record.get("popularityRank")
            if rank is not None and (type(rank) is not int or rank < 0):
                _fail(f"Invalid popularityRank at {path}:{line_number}")
            ref = record.get("remoteCoverRef")
            if ref is not None:
                _text(ref, 1024, "remote cover reference")
            aliases = record.get("aliases", [])
            if not isinstance(aliases, list) or len(aliases) > 64:
                _fail(f"Too many aliases at {path}:{line_number}")
            titles: set[str] = set()
            for alias in aliases:
                _exact_keys(alias, {"title", "language", "kind"}, "catalog alias")
                title = _text(alias.get("title"), 500, "alias title")
                if title in titles:
                    _fail(f"Duplicate alias title at {path}:{line_number}")
                titles.add(title)
                if alias.get("language") is not None:
                    _text(alias["language"], 35, "alias language")
                if alias.get("kind") is not None:
                    _text(alias["kind"], 64, "alias kind")
            alias_count += len(aliases)
            attributes = record.get("attributes", {})
            if not isinstance(attributes, dict) or len(attributes) > 128:
                _fail(f"Invalid attributes at {path}:{line_number}")
            for field_id, raw in attributes.items():
                if field_id not in fields:
                    _fail(f"Unknown field {field_id} at {path}:{line_number}")
                values = raw if isinstance(raw, list) else [raw]
                if isinstance(raw, list) and not fields[field_id]["multiple"]:
                    _fail(f"Scalar field {field_id} received an array at {path}:{line_number}")
                serialized = [json.dumps(value, sort_keys=True, ensure_ascii=False) for value in values]
                if not 1 <= len(values) <= 64 or len(serialized) != len(set(serialized)):
                    _fail(f"Invalid values for {field_id} at {path}:{line_number}")
                for value in values:
                    _attribute_value(value, fields[field_id], field_id)
            _validate_cover_ref(record, manifest, f"{path}:{line_number}")
        if file_records == 0:
            _fail(f"Empty catalog payload: {path}")
    if len(item_ids) > 2_000_000:
        _fail("Catalog exceeds the engine item limit")
    group_ids: set[str] = set()
    relationships = 0
    for path in manifest["groups"]:
        if not path.endswith((".jsonl", ".jsonl.gz")):
            _fail(f"Unsupported group payload format: {path}")
        for line_number, record in _read_jsonl(archive, path):
            if not isinstance(record, dict):
                _fail(f"Group record must be an object at {path}:{line_number}")
            _exact_keys(record, GROUP_KEYS, f"group record {path}:{line_number}")
            _required_keys(record, {"id", "members"}, f"group record {path}:{line_number}")
            group_id = record["id"]
            if not isinstance(group_id, str) or not ITEM_ID.fullmatch(group_id) or group_id in group_ids:
                _fail(f"Invalid or duplicate group ID at {path}:{line_number}")
            group_ids.add(group_id)
            for key in ("displayTitle", "providerGroupId", "remoteCoverRef"):
                if record.get(key) is not None:
                    _text(record[key], 500 if key == "displayTitle" else 1024, key)
            for key in ("groupType", "provider"):
                if record.get(key) is not None and (not isinstance(record[key], str) or not FIELD_ID.fullmatch(record[key])):
                    _fail(f"Invalid {key} at {path}:{line_number}")
            if record.get("providerGroupId") is not None and record.get("provider") is None:
                _fail(f"providerGroupId without provider at {path}:{line_number}")
            popularity = record.get("popularity")
            if popularity is not None and (type(popularity) not in (int, float) or not math.isfinite(popularity) or popularity < 0):
                _fail(f"Invalid group popularity at {path}:{line_number}")
            rank = record.get("popularityRank")
            if rank is not None and (type(rank) is not int or rank < 0):
                _fail(f"Invalid group popularityRank at {path}:{line_number}")
            members = record["members"]
            if not isinstance(members, list) or not 1 <= len(members) <= 10_000:
                _fail(f"Invalid group members at {path}:{line_number}")
            member_ids: set[str] = set()
            positions: set[int] = set()
            explicit = 0
            for index, member in enumerate(members):
                _exact_keys(member, {"mediaId", "position"}, "group member")
                media_id = member.get("mediaId")
                if not isinstance(media_id, str) or not ITEM_ID.fullmatch(media_id) or media_id in member_ids or media_id not in item_ids:
                    _fail(f"Missing, cross-pack or duplicate member at {path}:{line_number}: {media_id}")
                member_ids.add(media_id)
                position = member.get("position")
                if position is not None:
                    explicit += 1
                    if type(position) is not int or position not in range(len(members)) or position in positions:
                        _fail(f"Invalid group position at {path}:{line_number}")
                    positions.add(position)
            if explicit not in (0, len(members)) or (explicit and positions != set(range(len(members)))):
                _fail(f"Group positions must form 0..n-1 at {path}:{line_number}")
            representative = record.get("representativeMediaId")
            if representative is not None and representative not in item_ids:
                _fail(f"Missing group representative at {path}:{line_number}: {representative}")
            _validate_cover_ref(record, manifest, f"{path}:{line_number}")
            relationships += len(members)
    return {
        "items": len(item_ids),
        "groups": len(group_ids),
        "relationships": relationships,
        "aliases": alias_count,
    }
