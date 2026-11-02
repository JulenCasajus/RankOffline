from __future__ import annotations

import gzip
import hashlib
import io
import json
import math
from pathlib import PurePosixPath, Path
import re
import struct
from urllib.parse import urlparse
import zipfile

from .common import FIELD_ID, ITEM_ID, sha256_file


class PackValidationError(ValueError):
    pass


ROOT_KEYS = {
    "schemaVersion", "packId", "version", "minRankOfflineVersion", "displayName",
    "presentation", "capabilities", "fields", "filters", "subtitle", "merge", "defaults",
    "rating", "coverSources", "catalog", "groups", "covers", "attribution", "integrity",
}
RECORD_KEYS = {
    "id", "title", "provider", "providerId", "popularity", "popularityRank",
    "remoteCoverRef", "attributes", "aliases",
}
GROUP_KEYS = {
    "id", "members", "displayTitle", "groupType", "representativeMediaId", "provider",
    "providerGroupId", "popularity", "popularityRank", "remoteCoverRef",
}
FORBIDDEN_SUFFIXES = (".dex", ".jar", ".class", ".so", ".apk", ".js", ".html", ".htm", ".svg", ".sh")
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
LOCALE = re.compile(r"[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8})*\Z")
ISO_DATE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}\Z")
INTEGER = re.compile(r"-?(?:0|[1-9][0-9]*)\Z")
MAX_SAFE_INTEGER = 9_007_199_254_740_991


def _fail(message: str) -> None:
    raise PackValidationError(message)


def _exact_keys(value: dict, allowed: set[str], context: str) -> None:
    unknown = set(value) - allowed
    if unknown:
        _fail(f"{context} has unsupported properties: {sorted(unknown)}")


def _required_keys(value: dict, required: set[str], context: str) -> None:
    missing = required - set(value)
    if missing:
        _fail(f"{context} is missing properties: {sorted(missing)}")


def _text(value: object, limit: int, context: str) -> str:
    if (not isinstance(value, str) or not value.strip() or len(value) > limit or
            any(ord(ch) < 32 or 127 <= ord(ch) <= 159 for ch in value)):
        _fail(f"Invalid {context}")
    return value


def _notice(value: object, context: str) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > 4096:
        _fail(f"Invalid {context}")
    if any((ord(ch) < 32 and ch not in "\n\r\t") or 127 <= ord(ch) <= 159 for ch in value):
        _fail(f"Unsafe control character in {context}")
    return value


def _safe_path(value: object) -> str:
    if (not isinstance(value, str) or not 1 <= len(value) <= 256 or value.startswith("/") or
            "\\" in value or ":" in value or any(ord(ch) < 32 or 127 <= ord(ch) <= 159 for ch in value)):
        _fail(f"Invalid payload path: {value!r}")
    parts = value.split("/")
    if len(parts) < 2 or any(not part or part in (".", "..") for part in parts):
        _fail(f"Unsafe payload path: {value}")
    if parts[0] not in ("catalog", "groups", "covers", "metadata"):
        _fail(f"Unsupported payload directory: {value}")
    if value.lower().endswith(FORBIDDEN_SUFFIXES):
        _fail(f"Executable or active payload is forbidden: {value}")
    return value


def _https_url(value: object, context: str) -> str:
    text = _text(value, 2048, context)
    parsed = urlparse(text)
    if parsed.scheme.lower() != "https" or not parsed.hostname or parsed.username or parsed.password:
        _fail(f"Unsafe HTTPS URL in {context}")
    if parsed.port not in (None, 443) or "." not in parsed.hostname or parsed.hostname.endswith("."):
        _fail(f"Unsafe HTTPS host in {context}")
    if parsed.hostname.replace(".", "").isdigit() or parsed.hostname.endswith(".local"):
        _fail(f"Non-public HTTPS host in {context}")
    return text


def _image_dimensions(data: bytes) -> tuple[int, int] | None:
    if data.startswith(b"\x89PNG\r\n\x1a\n") and len(data) >= 24:
        return struct.unpack(">II", data[16:24])
    if data.startswith((b"II*\x00", b"MM\x00*")):
        return None
    if data.startswith(b"RIFF") and data[8:12] == b"WEBP" and len(data) >= 30:
        kind = data[12:16]
        if kind == b"VP8X":
            width = 1 + int.from_bytes(data[24:27], "little")
            height = 1 + int.from_bytes(data[27:30], "little")
            return width, height
        if kind == b"VP8 " and len(data) >= 30 and data[23:26] == b"\x9d\x01\x2a":
            return int.from_bytes(data[26:28], "little") & 0x3FFF, int.from_bytes(data[28:30], "little") & 0x3FFF
        if kind == b"VP8L" and len(data) >= 25 and data[20] == 0x2F:
            bits = int.from_bytes(data[21:25], "little")
            return (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1
    if data.startswith(b"\xff\xd8"):
        pos = 2
        while pos + 9 < len(data):
            if data[pos] != 0xFF:
                pos += 1
                continue
            marker = data[pos + 1]
            pos += 2
            if marker in (0xD8, 0xD9):
                continue
            if pos + 2 > len(data):
                break
            length = int.from_bytes(data[pos:pos + 2], "big")
            if marker in set(range(0xC0, 0xC4)) | set(range(0xC5, 0xC8)) | set(range(0xC9, 0xCC)) | set(range(0xCD, 0xD0)):
                return int.from_bytes(data[pos + 5:pos + 7], "big"), int.from_bytes(data[pos + 3:pos + 5], "big")
            pos += length
    return None


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


def _validate_manifest(manifest: dict, archive: zipfile.ZipFile) -> tuple[dict[str, dict], dict[str, dict]]:
    _exact_keys(manifest, ROOT_KEYS, "manifest")
    _required_keys(manifest, ROOT_KEYS, "manifest")
    if (manifest["schemaVersion"] != 2 or type(manifest["packId"]) is not int or
            not 1 <= manifest["packId"] <= 9_223_372_036_854_775_807):
        _fail("schemaVersion must be 2 and packId a positive JSON integer")
    for key, limit in (("version", 80), ("minRankOfflineVersion", 80), ("displayName", 160)):
        _text(manifest[key], limit, key)
    integrity = manifest["integrity"]
    _exact_keys(integrity, {"installedBytes", "files"}, "integrity")
    if not isinstance(integrity.get("files"), list) or not 1 <= len(integrity["files"]) <= 100_000:
        _fail("Invalid integrity file count")
    declared: dict[str, dict] = {}
    lower_paths: set[str] = set()
    total = 0
    for entry in integrity["files"]:
        _exact_keys(entry, {"path", "bytes", "sha256"}, "integrity file")
        path = _safe_path(entry.get("path"))
        if path in declared or path.lower() in lower_paths:
            _fail(f"Duplicate or case-colliding path: {path}")
        if (type(entry.get("bytes")) is not int or not 0 <= entry["bytes"] <= 9_223_372_036_854_775_807 or
                not isinstance(entry.get("sha256"), str) or not SHA256.fullmatch(entry["sha256"])):
            _fail(f"Invalid integrity metadata for {path}")
        declared[path] = entry
        lower_paths.add(path.lower())
        total += entry["bytes"]
    if integrity.get("installedBytes") != total:
        _fail("integrity.installedBytes does not equal the payload sum")
    capabilities = manifest["capabilities"]
    _exact_keys(capabilities, {"search", "filters", "covers", "popularity", "merge", "ratings"}, "capabilities")
    _required_keys(capabilities, {"search", "filters", "covers", "popularity", "merge", "ratings"}, "capabilities")
    if any(type(capabilities.get(key)) is not bool for key in capabilities):
        _fail("All capabilities must be booleans")
    fields: dict[str, dict] = {}
    if len(manifest["fields"]) > 128:
        _fail("Too many fields")
    for field in manifest["fields"]:
        _exact_keys(field, {"id", "type", "multiple", "labels", "options"}, "field")
        field_id = field.get("id")
        if not isinstance(field_id, str) or not FIELD_ID.fullmatch(field_id) or field_id in fields:
            _fail(f"Invalid or duplicate field ID: {field_id}")
        if field.get("type") not in ("text", "integer", "decimal", "boolean", "date", "choice") or type(field.get("multiple")) is not bool:
            _fail(f"Invalid field definition: {field_id}")
        labels = field.get("labels", {})
        if not isinstance(labels, dict) or len(labels) > 64:
            _fail(f"Invalid labels for {field_id}")
        for locale, label in labels.items():
            if not LOCALE.fullmatch(locale):
                _fail(f"Invalid locale tag: {locale}")
            _text(label, 160, f"label for {field_id}")
        options = field.get("options", [])
        if len(options) > 256 or len(options) != len(set(options)) or (options and field["type"] != "choice"):
            _fail(f"Invalid options for {field_id}")
        for option in options:
            _text(option, 4096, f"option for {field_id}")
        fields[field_id] = field
    filters: set[str] = set()
    if not isinstance(manifest["filters"], list) or len(manifest["filters"]) > 128:
        _fail("Too many filters")
    for item in manifest["filters"]:
        _exact_keys(item, {"id", "field", "type", "order", "labels"}, "filter")
        filter_id, field_id = item.get("id"), item.get("field")
        if not isinstance(filter_id, str) or not FIELD_ID.fullmatch(filter_id) or filter_id in filters or field_id not in fields:
            _fail(f"Invalid filter: {filter_id}")
        filters.add(filter_id)
        field_type = fields[field_id]["type"]
        if item.get("type") == "range":
            valid = field_type in ("integer", "decimal", "date")
        elif item.get("type") == "multi_select":
            valid = field_type in ("text", "choice", "boolean")
        else:
            valid = False
        if not valid or type(item.get("order")) is not int or item["order"] < 0:
            _fail(f"Incompatible filter: {filter_id}")
    if manifest["filters"] and not capabilities["filters"]:
        _fail("Filters declared while filter capability is disabled")
    subtitle = manifest["subtitle"]
    if len(subtitle) > 16 or len(subtitle) != len(set(subtitle)) or set(subtitle) - set(fields):
        _fail("Invalid subtitle fields")
    merge = manifest["merge"]
    _exact_keys(merge, {"enabled", "strategy", "groupType", "labels"}, "merge")
    if type(merge.get("enabled")) is not bool or merge.get("strategy") not in ("NONE", "GROUP_ID", "COLLECTION_ID", "RELATION_GRAPH"):
        _fail("Invalid merge settings")
    if merge["enabled"] and (not capabilities["merge"] or merge["strategy"] == "NONE" or not isinstance(merge.get("groupType"), str) or not FIELD_ID.fullmatch(merge["groupType"])):
        _fail("Enabled merge lacks a valid strategy/groupType/capability")
    defaults = manifest["defaults"]
    _exact_keys(defaults, {"showImages", "stickyScoreHeader", "mergeEnabled", "ratingMode", "oneCoverPerGroup"}, "defaults")
    _required_keys(defaults, {"showImages", "stickyScoreHeader", "mergeEnabled", "ratingMode"}, "defaults")
    if any(type(defaults[key]) is not bool for key in ("showImages", "stickyScoreHeader", "mergeEnabled")) or defaults["ratingMode"] not in ("simple", "elaborated"):
        _fail("Invalid defaults")
    if defaults["mergeEnabled"] and not merge["enabled"]:
        _fail("Default merge requires enabled merge")
    _validate_rating(manifest["rating"], capabilities["ratings"])
    sections: dict[str, list[str]] = {}
    for section in ("catalog", "groups", "covers"):
        values = manifest[section]
        if not isinstance(values, list) or len(values) != len(set(values)):
            _fail(f"Invalid {section} section")
        for path in values:
            path = _safe_path(path)
            if not path.startswith(section + "/") or path not in declared:
                _fail(f"Undeclared {section} payload: {path}")
        sections[section] = values
    if not sections["catalog"] or (merge["enabled"] and not sections["groups"]):
        _fail("Catalog is required and enabled merge requires groups")
    if sections["covers"] and not capabilities["covers"]:
        _fail("Local covers declared with cover capability disabled")
    source_ids = _validate_attribution(manifest["attribution"], declared, archive)
    cover_source_ids: set[str] = set()
    if not isinstance(manifest["coverSources"], list) or len(manifest["coverSources"]) > 64:
        _fail("Too many cover sources")
    for source in manifest["coverSources"]:
        _exact_keys(source, {"id", "sourceId", "providerId", "urlTemplates", "allowedHosts"}, "cover source")
        source_id = source.get("id")
        if not isinstance(source_id, str) or not FIELD_ID.fullmatch(source_id) or source_id in cover_source_ids or source.get("sourceId") not in source_ids:
            _fail(f"Invalid cover source: {source_id}")
        cover_source_ids.add(source_id)
        provider = source.get("providerId")
        if provider is not None and (not isinstance(provider, str) or not FIELD_ID.fullmatch(provider)):
            _fail(f"Invalid cover source provider: {provider}")
        hosts = source.get("allowedHosts", [])
        if not 1 <= len(hosts) <= 16 or len(hosts) != len(set(host.lower() for host in hosts)):
            _fail(f"Invalid host list for cover source {source_id}")
        normalized_hosts = {urlparse(_https_url(f"https://{host}/", "cover host")).hostname for host in hosts}
        templates = source.get("urlTemplates", [])
        if not 1 <= len(templates) <= 8:
            _fail(f"Invalid URL templates for {source_id}")
        for template in templates:
            substituted = template.replace("{reference}", "fixture").replace("{providerId}", "fixture")
            if "{" in substituted or "}" in substituted:
                _fail(f"Unsupported URL placeholder in {source_id}")
            parsed = urlparse(_https_url(substituted, "cover URL template"))
            if parsed.hostname not in normalized_hosts or parsed.fragment:
                _fail(f"Unapproved cover URL host in {source_id}")
    if manifest["coverSources"] and not capabilities["covers"]:
        _fail("Remote covers declared with cover capability disabled")
    presentation = manifest["presentation"]
    _exact_keys(presentation, {"names", "accentColor", "shareBackgrounds"}, "presentation")
    if not re.fullmatch(r"#[0-9A-Fa-f]{6}", presentation.get("accentColor", "")):
        _fail("Invalid presentation accent color")
    if not isinstance(presentation.get("names", {}), dict) or len(presentation.get("names", {})) > 64:
        _fail("Invalid localized presentation names")
    for locale, name in presentation.get("names", {}).items():
        if not LOCALE.fullmatch(locale):
            _fail(f"Invalid presentation locale: {locale}")
        _text(name, 160, "localized pack name")
    backgrounds = presentation.get("shareBackgrounds", {})
    _exact_keys(backgrounds, {"topSeven", "topTen"}, "share backgrounds")
    for key, expected in (("topSeven", (1080, 1920)), ("topTen", (1920, 1080))):
        if key not in backgrounds:
            continue
        path = _safe_path(backgrounds[key])
        if (not path.startswith("metadata/") or not path.lower().endswith((".png", ".jpg", ".jpeg", ".webp")) or
                path not in declared or not 1 <= declared[path]["bytes"] <= 8 * 1024 * 1024):
            _fail(f"Invalid share background: {path}")
        dimensions = _image_dimensions(archive.read(path))
        if dimensions != expected:
            _fail(f"{key} must be {expected[0]}x{expected[1]}, got {dimensions}")
    return declared, fields


def _validate_rating(value: object, enabled: bool) -> None:
    if not enabled:
        if value is not None:
            _fail("Rating data declared while ratings are disabled")
        return
    if not isinstance(value, dict):
        _fail("Ratings-enabled pack requires rating configuration")
    _exact_keys(value, {"categories", "profiles"}, "rating")
    categories = value.get("categories", [])
    profiles = value.get("profiles", [])
    if not 1 <= len(categories) <= 48 or not 1 <= len(profiles) <= 32:
        _fail("Invalid rating category/profile count")
    ids: set[str] = set()
    orders: set[int] = set()
    active_positive = False
    for category in categories:
        _exact_keys(category, {"id", "name", "description", "weight", "color", "order", "active"}, "rating category")
        category_id = category.get("id")
        if not isinstance(category_id, str) or not FIELD_ID.fullmatch(category_id) or category_id in ids:
            _fail(f"Invalid rating category ID: {category_id}")
        ids.add(category_id)
        _text(category.get("name"), 160, "rating category name")
        description = category.get("description", "")
        if (not isinstance(description, str) or len(description) > 1024 or
                any(ord(ch) < 32 or 127 <= ord(ch) <= 159 for ch in description)):
            _fail("Invalid rating category description")
        weight = category.get("weight")
        order = category.get("order")
        if type(weight) not in (int, float) or not math.isfinite(weight) or not 0 <= weight <= 100 or type(order) is not int or order < 0 or order in orders:
            _fail(f"Invalid rating category values: {category_id}")
        orders.add(order)
        if not re.fullmatch(r"#[0-9A-Fa-f]{6}", category.get("color", "")) or type(category.get("active", True)) is not bool:
            _fail(f"Invalid rating category presentation: {category_id}")
        active_positive |= category.get("active", True) and weight > 0
    if not active_positive:
        _fail("At least one active rating category must have positive weight")
    profile_ids: set[str] = set()
    for profile in profiles:
        _exact_keys(profile, {"id", "name", "weights"}, "rating profile")
        profile_id = profile.get("id")
        if not isinstance(profile_id, str) or not FIELD_ID.fullmatch(profile_id) or profile_id in profile_ids:
            _fail(f"Invalid rating profile ID: {profile_id}")
        profile_ids.add(profile_id)
        _text(profile.get("name"), 160, "rating profile name")
        weights = profile.get("weights", {})
        if set(weights) != ids or not all(type(weight) in (int, float) and math.isfinite(weight) and 0 <= weight <= 100 for weight in weights.values()) or sum(weights.values()) <= 0:
            _fail(f"Invalid rating profile weights: {profile_id}")


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
        matches = [source for source in sources if source.get("providerId") == provider]
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
