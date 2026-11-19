"""Canonical package identity and publication revision rules."""

from __future__ import annotations

import re
import hashlib
import json

SLUG = re.compile(r"[a-z0-9][a-z0-9_-]{0,63}\Z")
REVISION = re.compile(r"[1-9][0-9]*\Z")


def parse_version(version: str) -> tuple[str, int]:
    if not isinstance(version, str) or len(version) > 80:
        raise ValueError("Invalid pack version")
    slug, separator, number = version.rpartition("-v")
    if not separator or not SLUG.fullmatch(slug) or not REVISION.fullmatch(number):
        raise ValueError("Pack version must be slug-vN")
    return slug, int(number)


def validate_identity(pack_id: int, slug: str, version: str) -> int:
    if type(pack_id) is not int or not 1 <= pack_id <= 9_223_372_036_854_775_807:
        raise ValueError("Invalid packId")
    parsed_slug, revision = parse_version(version)
    if slug != parsed_slug:
        raise ValueError("Pack slug and version disagree")
    return revision


def next_pack_id(registry: list[dict]) -> int:
    """The shared registry, never a device-local counter, allocates IDs."""
    validate_registry(registry)
    return max((entry["packId"] for entry in registry), default=0) + 1


def next_publish_version(slug: str, published_revision: int | None = None) -> str:
    """Publication tooling may propose this value; ordinary builds never call it."""
    validate_identity(1, slug, f"{slug}-v1")
    if published_revision is not None and (type(published_revision) is not int or published_revision < 1):
        raise ValueError("Invalid published revision")
    return f"{slug}-v{1 if published_revision is None else published_revision + 1}"


def validate_registry(registry: list[dict]) -> None:
    ids: set[int] = set()
    slugs: set[str] = set()
    for entry in registry:
        pack_id, slug = entry["packId"], entry["slug"]
        validate_identity(pack_id, slug, f"{slug}-v1")
        if pack_id in ids or slug in slugs:
            raise ValueError("Duplicate packId or slug in registry")
        ids.add(pack_id)
        slugs.add(slug)


def content_fingerprint(manifest: dict) -> str:
    """Ignore identity spelling and app compatibility, never catalog or presentation data."""
    content = {key: value for key, value in manifest.items()
               if key not in ("slug", "version", "minRankOfflineVersion")}
    encoded = json.dumps(content, ensure_ascii=False, sort_keys=True,
                         separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def check_published_revision(manifest: dict, registry: list[dict]) -> None:
    validate_registry(registry)
    pack_id = manifest["packId"]
    record = next((entry for entry in registry if entry["packId"] == pack_id), None)
    if record is None or record["slug"] != manifest.get("slug"):
        raise ValueError("Pack identity is not registered")
    revision = validate_identity(pack_id, record["slug"], manifest["version"])
    published = record["publishedRevision"]
    digest = record["publishedContentSha256"]
    if (type(published) is not int or published < 1 or not isinstance(digest, str) or
            not re.fullmatch(r"[0-9a-f]{64}", digest)):
        raise ValueError("Invalid published registry record")
    if revision < published:
        raise ValueError("Pack revision is older than the published revision")
    if revision == published and content_fingerprint(manifest) != record["publishedContentSha256"]:
        raise ValueError("Published revision cannot be reused for changed content")
    if revision > published + 1:
        raise ValueError("Pack revision skips an unpublished number")
