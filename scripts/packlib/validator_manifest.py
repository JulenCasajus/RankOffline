from __future__ import annotations

import json
import math
import re
import zipfile
from urllib.parse import urlparse

from .common import FIELD_ID
from .validator_attribution import _validate_attribution
from .validator_common import (
    LOCALE, ROOT_KEYS, SHA256, PackValidationError, _exact_keys, _fail,
    _https_url, _image_dimensions, _required_keys, _safe_path, _text,
)

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
        _exact_keys(source, {"id", "sourceId", "providerId", "urlTemplates", "allowedHosts", "metadataRequest"}, "cover source")
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
        metadata = source.get("metadataRequest")
        if metadata is not None:
            if not isinstance(metadata, dict):
                _fail(f"Invalid metadata request for {source_id}")
            _exact_keys(metadata, {"referenceKey", "method", "urlTemplate", "bodyTemplate",
                                   "imageJsonPaths", "requestIntervalMillis"}, "metadata request")
            reference_key = metadata.get("referenceKey")
            method = metadata.get("method")
            url_template = metadata.get("urlTemplate")
            body_template = metadata.get("bodyTemplate")
            paths = metadata.get("imageJsonPaths")
            interval = metadata.get("requestIntervalMillis")
            if not isinstance(reference_key, str) or not FIELD_ID.fullmatch(reference_key):
                _fail(f"Invalid metadata reference key for {source_id}")
            if method not in ("GET", "POST_JSON") or not isinstance(url_template, str):
                _fail(f"Invalid metadata request method or URL for {source_id}")
            substituted_url = url_template.replace("{reference}", "1")
            if "{" in substituted_url or "}" in substituted_url:
                _fail(f"Unsupported metadata URL placeholder for {source_id}")
            authority = url_template.removeprefix("https://").split("/", 1)[0].split("?", 1)[0]
            if "{" in authority or "}" in authority:
                _fail(f"Dynamic metadata host for {source_id}")
            parsed_metadata_url = urlparse(_https_url(substituted_url, "metadata request URL"))
            if parsed_metadata_url.fragment:
                _fail(f"Metadata request URL has a fragment for {source_id}")
            if (method == "POST_JSON") != isinstance(body_template, str):
                _fail(f"Invalid metadata request body for {source_id}")
            if isinstance(body_template, str):
                if "{reference}" not in body_template or len(body_template) > 8192:
                    _fail(f"Invalid metadata JSON body template for {source_id}")
                try:
                    json.loads(body_template.replace("{reference}", "1"))
                except json.JSONDecodeError as error:
                    raise PackValidationError(f"Invalid metadata JSON body for {source_id}") from error
            if "{reference}" not in url_template and not isinstance(body_template, str):
                _fail(f"Metadata request does not use its reference for {source_id}")
            if (not isinstance(paths, list) or not 1 <= len(paths) <= 8 or
                    len(paths) != len(set(paths)) or
                    not all(isinstance(path, str) and re.fullmatch(
                        r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*", path
                    ) for path in paths)):
                _fail(f"Invalid metadata image paths for {source_id}")
            if type(interval) is not int or not 250 <= interval <= 60_000:
                _fail(f"Invalid metadata request interval for {source_id}")
        templates = source.get("urlTemplates", [])
        if not isinstance(templates, list) or len(templates) > 8 or (not templates) == (metadata is None):
            _fail(f"Invalid URL templates or metadata request for {source_id}")
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
