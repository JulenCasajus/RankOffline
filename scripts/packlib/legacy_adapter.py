from __future__ import annotations

from collections import defaultdict
import gzip
import json
from pathlib import Path
import re
import zipfile

from .common import ITEM_ID, chunks, clean_text, deterministic_gzip_writer


class ConversionError(ValueError):
    pass


def _json_text(value: object) -> str:
    # JSON already escapes C0 controls. Escape C1/DEL explicitly while keeping
    # ordinary Unicode compact enough for the Kotlin 4,096-character text limit.
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return "".join(
        f"\\u{ord(character):04x}" if 127 <= ord(character) <= 159 else character
        for character in encoded
    )


def _canonical_id(value: str, replacements: dict[str, str], report: dict) -> str:
    result = value
    for old, new in replacements.items():
        result = result.replace(old, new)
    if result != value:
        report["idMappings"].append({"source": value, "schema2": result})
    if not ITEM_ID.fullmatch(result):
        raise ConversionError(f"ID cannot be represented by schema 2: {value!r}")
    return result


def _store_chunks(attributes: dict, prefix: str, values: list[str], field_limit: int) -> None:
    parts = chunks(values, 64)
    if len(parts) > field_limit:
        raise ConversionError(f"{prefix} needs {len(parts)} overflow fields; configured limit is {field_limit}")
    for index, part in enumerate(parts, 1):
        attributes[f"{prefix}_{index}"] = part


def _convert_aliases(source: list, attributes: dict, report: dict, field_limit: int) -> list[dict]:
    accepted: list[dict] = []
    seen: set[str] = set()
    overflow: list[str] = []
    for alias in source:
        if not isinstance(alias, dict) or not isinstance(alias.get("title"), str):
            raise ConversionError("Legacy alias is not an object with a title")
        title, changed = clean_text(alias["title"])
        if changed:
            report["normalizedAliasText"] += 1
        converted = {"title": title}
        for key in ("language", "kind"):
            if alias.get(key) is not None:
                text, metadata_changed = clean_text(str(alias[key]))
                converted[key] = text
                report["normalizedAliasMetadata"] += int(metadata_changed)
        valid = bool(title) and len(title) <= 500 and title not in seen
        if valid and len(accepted) < 64:
            accepted.append(converted)
            seen.add(title)
        else:
            overflow.append(_json_text(alias))
    if overflow:
        _store_chunks(attributes, "source_alias_overflow", overflow, field_limit)
    report["aliasOverflowEntries"] += len(overflow)
    return accepted


def _convert_credits(source: list, mappings: dict[str, str], attributes: dict,
                     report: dict, field_limit: int) -> None:
    by_role: dict[str, list[str]] = defaultdict(list)
    unknown: list[dict] = []
    for credit in source:
        if not isinstance(credit, dict) or not isinstance(credit.get("name"), str):
            raise ConversionError("Legacy credit is malformed")
        role = str(credit.get("role") or "").upper()
        field = mappings.get(role)
        if field is None:
            unknown.append(credit)
            continue
        name, changed = clean_text(credit["name"])
        report["normalizedCreditText"] += int(changed)
        if name and name not in by_role[field]:
            by_role[field].append(name)
        elif name:
            report["duplicateCreditsRemoved"] += 1
    overflow: list[str] = [_json_text(value) for value in unknown]
    for field, names in by_role.items():
        attributes[field] = names[:64]
        overflow.extend(_json_text({"role": field.upper(), "name": name}) for name in names[64:])
    if overflow:
        _store_chunks(attributes, "source_credit_overflow", overflow, field_limit)
    report["creditOverflowEntries"] += len(overflow)


def _cover_reference(value: object, mode: str, attributes: dict, report: dict,
                     replacement: dict | None = None) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise ConversionError("remoteCoverRef must be text")
    if mode == "preserve":
        return value
    if mode == "strip_leading_slash":
        converted = value[1:] if value.startswith("/") else value
        report["coverReferencesNormalized"] += int(converted != value)
        return converted
    if mode == "replace_prefix":
        if not isinstance(replacement, dict) or set(replacement) != {"from", "to"}:
            raise ConversionError("replace_prefix requires an exact from/to declaration")
        source = replacement["from"]
        target = replacement["to"]
        if not isinstance(source, str) or not source or not isinstance(target, str):
            raise ConversionError("Invalid cover reference prefix replacement")
        if not value.startswith(source):
            raise ConversionError(f"Cover reference does not start with {source!r}")
        converted = target + value[len(source):]
        report["coverReferencesNormalized"] += 1
        return converted
    if mode == "preserve_as_legacy_attribute":
        attributes["legacy_cover_reference"] = value
        report["coverReferencesPreservedAsData"] += 1
        return None
    raise ConversionError(f"Unknown cover conversion mode: {mode}")


def convert_legacy_archive(archive_path: Path, config: dict, staging: Path) -> tuple[dict[str, Path], dict]:
    adapter = config["adapter"]
    report = {
        "sourceArchive": archive_path.name,
        "sourceSchemaVersion": 1,
        "targetSchemaVersion": 2,
        "idMappings": [],
        "normalizedTitles": 0,
        "normalizedAliasText": 0,
        "normalizedAliasMetadata": 0,
        "normalizedCreditText": 0,
        "duplicateCreditsRemoved": 0,
        "aliasOverflowEntries": 0,
        "creditOverflowEntries": 0,
        "coverReferencesNormalized": 0,
        "coverReferencesPreservedAsData": 0,
        "groupPositionsReindexed": 0,
        "itemCount": 0,
        "groupCount": 0,
        "relationshipCount": 0,
    }
    replacements = adapter.get("idReplacements", {})
    scalar_fields = adapter["scalarFields"]
    list_fields = adapter["listFields"]
    credit_fields = adapter["creditRoles"]
    alias_overflow_limit = adapter.get("aliasOverflowFields", 3)
    credit_overflow_limit = adapter.get("creditOverflowFields", 4)
    id_map: dict[str, str] = {}
    output_files: dict[str, Path] = {}

    with zipfile.ZipFile(archive_path) as source_zip:
        source_manifest = json.loads(source_zip.read("manifest.json"))
        if source_manifest.get("schemaVersion") != 1:
            raise ConversionError("The configured legacy adapter requires schemaVersion 1")
        catalog_source = adapter["catalogPath"]
        group_source = adapter["groupsPath"]
        catalog_target = adapter["catalogTarget"]
        group_target = adapter["groupsTarget"]
        catalog_path = staging / Path(catalog_target).name
        group_path = staging / Path(group_target).name

        seen_ids: set[str] = set()
        with gzip.GzipFile(fileobj=source_zip.open(catalog_source)) as source, \
                deterministic_gzip_writer(catalog_path) as target:
            for line_number, encoded in enumerate(source, 1):
                record = json.loads(encoded)
                original_id = record["id"]
                item_id = _canonical_id(original_id, replacements, report)
                if item_id in seen_ids:
                    raise ConversionError(f"Duplicate converted item ID at line {line_number}: {item_id}")
                seen_ids.add(item_id)
                id_map[original_id] = item_id
                title, changed = clean_text(record["title"])
                report["normalizedTitles"] += int(changed)
                attributes: dict[str, object] = {}
                for source_key, field_id in scalar_fields.items():
                    value = record.get(source_key)
                    if value is not None:
                        attributes[field_id] = adapter.get("scalarValueMaps", {}).get(source_key, {}).get(value, value)
                for source_key, field_id in list_fields.items():
                    values = record.get(source_key) or []
                    if not isinstance(values, list):
                        raise ConversionError(f"{source_key} must be an array at catalog line {line_number}")
                    cleaned = []
                    for value in values:
                        text, text_changed = clean_text(str(value))
                        report["normalizedCreditText"] += int(text_changed)
                        if text and text not in cleaned:
                            cleaned.append(text)
                    if cleaned:
                        if len(cleaned) > 64:
                            raise ConversionError(f"{field_id} exceeds 64 values at catalog line {line_number}")
                        attributes[field_id] = cleaned
                _convert_credits(record.get("credits") or [], credit_fields, attributes,
                                 report, credit_overflow_limit)
                aliases = _convert_aliases(record.get("aliases") or [], attributes,
                                           report, alias_overflow_limit)
                remote_ref = _cover_reference(record.get("remoteCoverRef"),
                                              adapter["coverReferenceMode"], attributes, report,
                                              adapter.get("coverReferencePrefixReplacement"))
                converted = {
                    "id": item_id,
                    "title": title,
                    "provider": record.get("provider"),
                    "providerId": record.get("providerId"),
                    "popularity": record.get("popularity"),
                    "popularityRank": record.get("popularityRank"),
                    "remoteCoverRef": remote_ref,
                    "attributes": attributes,
                    "aliases": aliases,
                }
                target.write({key: value for key, value in converted.items() if value is not None})
                report["itemCount"] += 1

        seen_groups: set[str] = set()
        with gzip.GzipFile(fileobj=source_zip.open(group_source)) as source, \
                deterministic_gzip_writer(group_path) as target:
            for line_number, encoded in enumerate(source, 1):
                record = json.loads(encoded)
                group_id = _canonical_id(record["id"], replacements, report)
                if group_id in seen_groups:
                    raise ConversionError(f"Duplicate converted group ID at line {line_number}: {group_id}")
                seen_groups.add(group_id)
                members = []
                for position, member in enumerate(record["members"]):
                    member_id = id_map.get(member["mediaId"], member["mediaId"])
                    members.append({"mediaId": member_id, "position": position})
                    report["groupPositionsReindexed"] += int(member.get("position") != position)
                group_attributes: dict[str, object] = {}
                remote_ref = _cover_reference(record.get("remoteCoverRef"),
                                              adapter["coverReferenceMode"], group_attributes, report,
                                              adapter.get("coverReferencePrefixReplacement"))
                if group_attributes:
                    raise ConversionError("Current schema cannot preserve a legacy-only group cover reference")
                title = record.get("displayTitle")
                if title is not None:
                    title, changed = clean_text(title)
                    report["normalizedTitles"] += int(changed)
                representative = record.get("representativeMediaId")
                if representative is not None:
                    representative = id_map.get(representative, representative)
                converted = {
                    "id": group_id,
                    "members": members,
                    "displayTitle": title,
                    "groupType": adapter["groupType"],
                    "representativeMediaId": representative,
                    "provider": record.get("provider"),
                    "providerGroupId": record.get("providerGroupId"),
                    "popularity": record.get("popularity"),
                    "popularityRank": record.get("popularityRank"),
                    "remoteCoverRef": remote_ref,
                }
                target.write({key: value for key, value in converted.items() if value is not None})
                report["groupCount"] += 1
                report["relationshipCount"] += len(members)

        output_files[catalog_target] = catalog_path
        output_files[group_target] = group_path
        for source_name, target_name in adapter.get("sourceMetadata", {}).items():
            target_path = staging / Path(target_name).name
            target_path.write_bytes(source_zip.read(source_name))
            output_files[target_name] = target_path

    report["idMappings"] = sorted(report["idMappings"], key=lambda entry: entry["source"])
    return output_files, report
