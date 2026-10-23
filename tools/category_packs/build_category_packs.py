#!/usr/bin/env python3
"""Build deterministic RankOffline schema-v1 DATA-ONLY packs.

Outputs remain below tools/.cache. A future GitHub release uploads the five
`.pack` files plus category_pack_index.json; this script never publishes them.
"""

from __future__ import annotations

import hashlib
import gzip
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets"
OUTPUT = ROOT / "tools/.cache/category-packs/output"
INDEX_ASSET = ASSETS / "category_pack_index.json"
FIXED_ZIP_TIME = (2026, 1, 1, 0, 0, 0)
APP_VERSION = "0.2.0"

# source asset -> final pack-relative path
FILES = {
    "animes": {
        "anime_catalog.jsonl.gz": "catalog/anime_catalog.jsonl.gzip",
        "anime_catalog.manifest.json": "catalog/anime_catalog.manifest.json",
        "anime_groups.jsonl.gz": "groups/anime_groups.jsonl.gzip",
        "anime_groups.manifest.json": "groups/anime_groups.manifest.json",
    },
    "mangas": {
        "manga_catalog.jsonl.gz": "catalog/manga_catalog.jsonl.gzip",
        "manga_catalog.manifest.json": "catalog/manga_catalog.manifest.json",
        "manga_groups.jsonl.gz": "groups/manga_groups.jsonl.gzip",
        "manga_groups.manifest.json": "groups/manga_groups.manifest.json",
    },
    "movies": {
        "movie_catalog.jsonl.gz": "catalog/movie_catalog.jsonl.gzip",
        "movie_catalog.manifest.json": "catalog/movie_catalog.manifest.json",
        "movie_groups.jsonl.gz": "groups/movie_groups.jsonl.gzip",
        "movie_groups.manifest.json": "groups/movie_groups.manifest.json",
    },
    "series": {
        "series_catalog.jsonl.gz": "catalog/series_catalog.jsonl.gzip",
        "series_catalog.manifest.json": "catalog/series_catalog.manifest.json",
        "series_groups.jsonl.gz": "groups/series_groups.jsonl.gzip",
        "series_groups.manifest.json": "groups/series_groups.manifest.json",
    },
    "books": {
        "book_catalog.jsonl.gz": "catalog/book_catalog.jsonl.gzip",
        "book_catalog.manifest.json": "catalog/book_catalog.manifest.json",
    },
}

MEDIA_STEM = {"animes": "anime", "mangas": "manga", "movies": "movie", "series": "series", "books": "book"}
DISPLAY = {"animes": "Anime", "mangas": "Manga", "movies": "Movies", "series": "Series", "books": "Books",}
PROVIDERS = {
    "animes": ("anime_offline_database", "anime-offline-database"),
    "mangas": ("mangadex", "MangaDex"),
    "movies": ("tmdb", "TMDB"),
    "series": ("tmdb", "TMDB"),
    "books": ("openlibrary", "Open Library"),
}
FILTERS = {
    "animes": [("genre", "genre", "multi_select"), ("format", "format", "multi_select"), ("year", "year", "range"), ("studio", "studio", "multi_select"),],
    "mangas": [("genre", "genre", "multi_select"), ("year", "year", "range"), ("author", "author", "multi_select")],
    "movies": [("genre", "genre", "multi_select"), ("year", "year", "range"), ("production_company", "production_company", "multi_select")],
    "series": [("genre", "genre", "multi_select"), ("year", "year", "range"), ("network", "network", "multi_select")],
    "books": [("subject", "subject", "multi_select"), ("year", "year", "range"), ("author", "author", "multi_select")],
}
MERGE = {
    "animes": (True, "RELATION_GRAPH", "RELATIONS"),
    "mangas": (True, "RELATION_GRAPH", "VARIANTS"),
    "movies": (True, "COLLECTION_ID", "COLLECTION"),
    "series": (True, "GROUP_ID", "SEASONS"),
    "books": (False, "NONE", None),
}


def validate_precomputed_groups(pack_id: str) -> None:
    """Fail publication if Android would have to derive group structure or summaries."""
    stem = MEDIA_STEM[pack_id]
    groups_path = ASSETS / f"{stem}_groups.jsonl.gz"
    manifest_path = ASSETS / f"{stem}_groups.manifest.json"
    if not groups_path.exists() and not manifest_path.exists():
        if MERGE[pack_id][0]:
            raise ValueError(f"Merge-capable pack {pack_id} requires precomputed groups")
        return
    if not groups_path.is_file() or not manifest_path.is_file():
        raise ValueError(f"Incomplete group artifacts for {pack_id}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    expected_format = 2
    if manifest.get("formatVersion") != expected_format:
        raise ValueError(f"{pack_id} groups must use precomputed format {expected_format}")
    count = 0
    membership_count = 0
    with gzip.open(groups_path, "rt", encoding="utf-8") as source:
        for line_number, line in enumerate(source, 1):
            if not line.strip():
                continue
            group = json.loads(line)
            members = group.get("members")
            member_key = "mediaId"
            representative_key = "representativeMediaId"
            member_ids = [member.get(member_key) for member in members or []]
            if (
                not isinstance(members, list)
                or len(members) < 2
                or group.get("memberCount") != len(members)
                or group.get(representative_key) not in member_ids
            ):
                raise ValueError(f"Incomplete precomputed group at {groups_path}:{line_number}")
            count += 1
            membership_count += len(members)
    if count != manifest.get("groupCount"):
        raise ValueError(f"Group count mismatch for {pack_id}")
    if membership_count != manifest.get("membershipCount"):
        raise ValueError(f"Group membership count mismatch for {pack_id}")


def _write_deterministic_gzip(path: Path, encoded: bytes) -> None:
    with path.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=9) as zipped:
            zipped.write(encoded)


def _anime_start_date(item: dict) -> str | None:
    date = item.get("startDate")
    if not isinstance(date, dict):
        return None
    year, month, day = date.get("year"), date.get("month"), date.get("day")
    if not isinstance(year, int) or not 1 <= year <= 9999:
        return None
    if not isinstance(month, int) or not 1 <= month <= 12:
        return f"{year:04d}"
    if not isinstance(day, int) or not 1 <= day <= 31:
        return f"{year:04d}-{month:02d}"
    return f"{year:04d}-{month:02d}-{day:02d}"


def _normalized_anime_format(value: str | None) -> str | None:
    normalized = (value or "").upper()
    if normalized in {"TV", "TV_SHORT", "ONA", "SERIES"}:
        return "SERIES"
    if normalized in {"MOVIE", "MOVIES"}:
        return "MOVIES"
    if normalized in {"OVA", "SPECIAL"}:
        return "SPECIAL"
    if normalized == "MUSIC":
        return "MUSIC"
    return None


def generate_normalized_anime_catalog() -> None:
    """Convert provider-specific Anime input to the common on-device catalog contract."""
    catalog = json.loads((ASSETS / "anime_catalog.json").read_text(encoding="utf-8"))
    aliases: dict[str, list[dict]] = {}
    with gzip.open(ASSETS / "anime_aliases.jsonl.gz", "rt", encoding="utf-8") as source:
        for line in source:
            if not line.strip():
                continue
            record = json.loads(line)
            aliases[record["animeId"]] = record.get("aliases", [])

    records = []
    for item in catalog:
        canonical_id = item["id"]
        records.append({
            "id": canonical_id,
            "mediaType": "anime",
            "provider": "anime_offline_database",
            "providerId": canonical_id,
            "title": item["title"],
            "year": item.get("seasonYear"),
            "startDate": _anime_start_date(item),
            "format": _normalized_anime_format(item.get("format")),
            "status": item.get("status"),
            "originalLanguage": item.get("countryOfOrigin"),
            "popularity": item.get("popularity"),
            "popularityRank": item.get("popularityRank"),
            "genres": item.get("genres") or [],
            "credits": [
                {"role": "STUDIO", "name": studio}
                for studio in dict.fromkeys(item.get("studios") or []) if studio
            ],
            "aliases": [
                {
                    "title": alias["title"],
                    "language": alias.get("language"),
                    "kind": alias.get("kind"),
                }
                for alias in aliases.get(canonical_id, [])
                if alias.get("title") and alias["title"] != item["title"]
            ],
        })

    encoded = "".join(
        json.dumps(record, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n"
        for record in records
    ).encode("utf-8")
    target = ASSETS / "anime_catalog.jsonl.gz"
    _write_deterministic_gzip(target, encoded)
    version = "anime-" + (ASSETS / "anime_catalog.version").read_text().strip()
    manifest = {
        "formatVersion": 1,
        "mediaType": "anime",
        "provider": "anime_offline_database",
        "catalogVersion": version,
        "sha256": sha256(target),
        "itemCount": len(records),
    }
    (ASSETS / "anime_catalog.manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n",
        encoding="utf-8",
    )


def generate_anime_groups() -> None:
    """Materialize Anime relationships in the shared group-v2 contract."""
    catalog = json.loads((ASSETS / "anime_catalog.json").read_text(encoding="utf-8"))
    candidates: dict[str, list[dict]] = {}
    for item in catalog:
        group_id = item.get("groupId")
        if group_id:
            candidates.setdefault(group_id, []).append(item)

    records = []
    for group_id, members in sorted(candidates.items()):
        first = members[0]
        title = first.get("groupDisplayTitle")
        root_id = first.get("groupCoverAnimeId")
        if len(members) < 2 or not all((title, root_id)):
            continue
        if any(
            member.get("groupDisplayTitle") != title
            or member.get("groupCoverAnimeId") != root_id
            for member in members
        ) or root_id not in {member["id"] for member in members}:
            continue
        def order_key(item: dict) -> tuple:
            date = item.get("startDate")
            year = date.get("year") if isinstance(date, dict) else None
            month = date.get("month") if isinstance(date, dict) else None
            day = date.get("day") if isinstance(date, dict) else None
            normalized_date = if_valid = ""
            if isinstance(year, int) and 1 <= year <= 9999 and isinstance(month, int) \
                    and 1 <= month <= 12 and isinstance(day, int) and 1 <= day <= 31:
                if_valid = f"{year:04d}-{month:02d}-{day:02d}"
            normalized_date = if_valid
            return (
                not normalized_date,
                normalized_date,
                item.get("seasonYear") if item.get("seasonYear") is not None else 2**31 - 1,
                item.get("title", "").casefold(),
                item["id"],
            )

        ordered = sorted(
            members,
            key=order_key,
        )
        records.append({
            "id": group_id,
            "mediaType": "anime",
            "groupType": "RELATIONS",
            "provider": "anime_offline_database",
            "providerGroupId": group_id,
            "displayTitle": title,
            "representativeMediaId": root_id,
            "memberCount": len(ordered),
            "popularity": max(
                (float(member["popularity"]) for member in ordered if member.get("popularity") is not None),
                default=None,
            ),
            "popularityRank": min(
                (int(member["popularityRank"]) for member in ordered if member.get("popularityRank") is not None),
                default=None,
            ),
            "members": [
                {"mediaId": member["id"], "position": position}
                for position, member in enumerate(ordered)
            ],
        })

    encoded = "".join(
        json.dumps(record, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n"
        for record in records
    ).encode("utf-8")
    target = ASSETS / "anime_groups.jsonl.gz"
    _write_deterministic_gzip(target, encoded)
    compressed_sha = sha256(target)
    manifest = {
        "formatVersion": 2,
        "mediaType": "anime",
        "provider": "anime_offline_database",
        "catalogVersion": snapshot("animes"),
        "sha256": compressed_sha,
        "groupCount": len(records),
        "membershipCount": sum(len(record["members"]) for record in records),
    }
    (ASSETS / "anime_groups.manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n",
        encoding="utf-8",
    )


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def bytes_sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def snapshot(pack_id: str) -> str:
    stem = MEDIA_STEM[pack_id]
    if pack_id == "animes":
        return json.loads((ASSETS / "anime_catalog.manifest.json").read_text())["catalogVersion"]
    return json.loads((ASSETS / f"{stem}_catalog.manifest.json").read_text())["catalogVersion"]


def pack_manifest(pack_id: str, file_specs: list[dict]) -> dict:
    provider_id, provider_name = PROVIDERS[pack_id]
    merge_enabled, merge_strategy, group_type = MERGE[pack_id]
    filters = [
        {"id": field_id, "field": field, "type": kind, "order": order}
        for order, (field_id, field, kind) in enumerate(FILTERS[pack_id], 1)
    ]
    return {
        "schemaVersion": 1,
        "packId": pack_id,
        "displayName": DISPLAY[pack_id],
        "version": snapshot(pack_id),
        "minRankOfflineVersion": APP_VERSION,
        "provider": {"id": provider_id, "name": provider_name},
        "catalog": [item["path"] for item in file_specs if item["path"].startswith("catalog/")],
        "groups": [item["path"] for item in file_specs if item["path"].startswith("groups/")],
        "covers": [item["path"] for item in file_specs if item["path"].startswith("covers/")],
        "capabilities": {
            "search": True,
            "filters": True,
            "covers": True,
            "popularity": True,
            "merge": merge_enabled,
            "ratings": True,
        },
        "filters": filters,
        "subtitle": [item["field"] for item in filters],
        "merge": {"enabled": merge_enabled, "strategy": merge_strategy, "groupType": group_type},
        "defaults": {
            "showImages": True,
            "stickyScoreHeader": False,
            "mergeEnabled": False,
            "ratingMode": "simple",
            **({"oneCoverPerGroup": True} if pack_id == "series" else {}),
        },
        "integrity": {"installedBytes": sum(item["bytes"] for item in file_specs), "files": file_specs},
    }


def _write_entry(target: zipfile.ZipFile, path: str, contents: bytes) -> None:
    info = zipfile.ZipInfo(path, FIXED_ZIP_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    target.writestr(info, contents, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def build() -> dict:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    generate_normalized_anime_catalog()
    generate_anime_groups()
    packs = []
    for pack_id, sources in FILES.items():
        validate_precomputed_groups(pack_id)
        archive_name = f"RankOffline-{DISPLAY[pack_id]}-v{APP_VERSION}.pack"
        archive = OUTPUT / archive_name
        file_specs = []
        payloads = []
        for source_name, installed_path in sources.items():
            source = ASSETS / source_name
            if not source.is_file():
                raise FileNotFoundError(source)
            payload = source.read_bytes()
            payloads.append((installed_path, payload))
            file_specs.append({"path": installed_path, "bytes": len(payload), "sha256": bytes_sha256(payload)})
        manifest = pack_manifest(pack_id, file_specs)
        encoded_manifest = (json.dumps(manifest, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n").encode()
        with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as target:
            _write_entry(target, "manifest.json", encoded_manifest)
            for path, payload in payloads:
                _write_entry(target, path, payload)
        packs.append({
            "packId": pack_id,
            "manifest": manifest,
            "formatVersion": 1,
            "minimumDatabaseVersion": 13,
            "archive": archive_name,
            "downloadBytes": archive.stat().st_size,
            "archiveSha256": sha256(archive),
            "installedBytes": sum(item["bytes"] for item in file_specs),
        })
    index = {"schemaVersion": 1, "packs": packs}
    encoded = json.dumps(index, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n"
    (OUTPUT / "category_pack_index.json").write_text(encoded)
    INDEX_ASSET.write_text(encoded)
    return index


if __name__ == "__main__":
    result = build()
    for pack in result["packs"]:
        print(f"{pack['packId']}: {pack['downloadBytes']} bytes -> {pack['installedBytes']} bytes")
