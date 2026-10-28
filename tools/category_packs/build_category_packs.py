#!/usr/bin/env python3
"""Build deterministic RankOffline schema-v1 DATA-ONLY packs.

Outputs remain below tools/.cache. A future GitHub release uploads the five
`.pack` files plus category_pack_index.json; this script never publishes them.
"""

from __future__ import annotations

import hashlib
import argparse
import gzip
import json
from pathlib import Path
from urllib.parse import quote
import zipfile

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets"
OUTPUT = ROOT / "tools/.cache/category-packs/output"
PREPARED_COVERS = ROOT / "tools/.cache/category-packs/prepared-covers"
SHARE_BACKGROUNDS = ROOT / "tools/category_packs/share_backgrounds"
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
        "book_groups.jsonl.gz": "groups/book_groups.jsonl.gzip",
        "book_groups.manifest.json": "groups/book_groups.manifest.json",
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
COVER_SOURCES = {
    "animes": [],  # Legacy AniList/Jikan adapter until packs carry direct image references.
    "mangas": [{
        "id": "mangadex_cover",
        "providerId": "mangadex",
        "urlTemplates": ["https://uploads.mangadex.org/covers/{providerId}/{reference}.512.jpg"],
        "allowedHosts": ["uploads.mangadex.org"],
        "attributionName": "MangaDex",
        "attributionUrl": "https://mangadex.org",
    }],
    "movies": [{
        "id": "tmdb_poster",
        "providerId": "tmdb",
        "urlTemplates": ["https://image.tmdb.org/t/p/w500/{reference}"],
        "allowedHosts": ["image.tmdb.org"],
        "attributionName": "TMDB",
        "attributionUrl": "https://www.themoviedb.org",
    }],
    "series": [{
        "id": "tmdb_poster",
        "providerId": "tmdb",
        "urlTemplates": ["https://image.tmdb.org/t/p/w500/{reference}"],
        "allowedHosts": ["image.tmdb.org"],
        "attributionName": "TMDB",
        "attributionUrl": "https://www.themoviedb.org",
    }],
    "books": [{
        "id": "openlibrary_cover",
        "providerId": "openlibrary",
        "urlTemplates": ["https://covers.openlibrary.org/b/olid/{reference}-L.jpg?default=false"],
        "allowedHosts": ["covers.openlibrary.org"],
        "attributionName": "Open Library",
        "attributionUrl": "https://openlibrary.org",
    }],
}
FILTERS = {
    "animes": [("genre", "genre", "multi_select"), ("format", "format", "multi_select"), ("year", "year", "range"), ("studio", "studio", "multi_select"),],
    "mangas": [("genre", "genre", "multi_select"), ("year", "year", "range"), ("author", "author", "multi_select")],
    "movies": [("genre", "genre", "multi_select"), ("year", "year", "range"), ("production_company", "production_company", "multi_select")],
    "series": [("genre", "genre", "multi_select"), ("year", "year", "range"), ("network", "network", "multi_select")],
    "books": [("genre", "genre", "multi_select"), ("year", "year", "range"), ("author", "author", "multi_select")],
}
MERGE = {
    "animes": (True, "RELATION_GRAPH", "RELATIONS"),
    "mangas": (True, "RELATION_GRAPH", "VARIANTS"),
    "movies": (True, "COLLECTION_ID", "COLLECTION"),
    "series": (True, "GROUP_ID", "SEASONS"),
    "books": (True, "GROUP_ID", "BOOK_SERIES"),
}

BOOK_GENRES = {
    "Fantasy": ("fantasy", "magic", "wizard", "witch", "dragon", "fairy tale"),
    "Science Fiction": ("science fiction", "sci-fi", "dystopi", "space opera", "alien", "cyberpunk"),
    "Romance": ("romance", "love stor"),
    "Mystery & Thriller": ("mystery", "detective", "thriller", "suspense", "crime fiction"),
    "Horror": ("horror", "ghost stor", "vampire", "supernatural fiction"),
    "Adventure": ("adventure",),
    "Historical Fiction": ("historical fiction", "historical novel"),
    "Classics": ("classic literature", "classics"),
    "Biography": ("biograph", "autobiograph", "memoir"),
    "History": ("history",),
    "Science & Technology": ("science", "technology", "computer", "mathematics", "physics"),
    "Philosophy": ("philosophy", "ethics"),
}

# Explicit, reviewed Open Library Work relationships. Entries are never inferred from titles.
BOOK_SERIES = (
    ("harry-potter", "Harry Potter", (
        "OL82563W", "OL82537W", "OL82536W", "OL82560W", "OL82548W", "OL82565W", "OL82586W",
    )),
    ("the-lord-of-the-rings", "The Lord of the Rings", (
        "OL27513W", "OL27479W", "OL27455W",
    )),
)


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


def _anime_cover_reference(item: dict) -> str | None:
    candidates = []
    if isinstance(item.get("anilistId"), int) and item["anilistId"] > 0:
        candidates.append(f"anilist={item['anilistId']}")
    if isinstance(item.get("malId"), int) and item["malId"] > 0:
        candidates.append(f"mal={item['malId']}")
    return "|".join(["anime:v1", *candidates]) if candidates else None


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
            "remoteCoverRef": _anime_cover_reference(item),
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


def _normalized_book_genres(subjects: list[str]) -> list[str]:
    values = "\n".join(subject.casefold() for subject in subjects)
    genres = [
        genre for genre, markers in BOOK_GENRES.items()
        if any(marker in values for marker in markers)
    ]
    # Historical fiction is more specific than the broad History marker.
    if "Historical Fiction" in genres and "History" in genres:
        genres.remove("History")
    # The broad word "science" inside "science fiction" must not also classify fiction as
    # non-fiction Science & Technology unless another technical marker is present.
    if "Science Fiction" in genres and "Science & Technology" in genres:
        technical_markers = tuple(
            marker for marker in BOOK_GENRES["Science & Technology"] if marker != "science"
        )
        without_science_fiction = values.replace("science fiction", "").replace("science-fiction", "")
        if not any(marker in without_science_fiction for marker in technical_markers) and \
                "science" not in without_science_fiction:
            genres.remove("Science & Technology")
    return genres or ["Other / Unclassified"]


def _declarative_book_cover_reference(
    reference: str | None,
    identity: str,
    work_olid: str | None,
) -> str | None:
    urls = []
    if reference and reference.startswith("{"):
        existing = json.loads(reference)
        if existing.get("schemaVersion") == 1 and existing.get("sourceId") == "openlibrary_cover":
            urls.extend(url for url in existing.get("urls", []) if isinstance(url, str))
    elif reference and reference.isdigit():
        urls.append(
            f"https://covers.openlibrary.org/b/id/{quote(reference, safe='')}-L.jpg?default=false"
        )
    elif reference and reference.startswith("openlibrary:v1|"):
        for encoded in reference.split("|")[1:]:
            key, separator, value = encoded.partition("=")
            if not separator or not value:
                continue
            path_type = "id" if key == "id" else "isbn" if key in {"isbn13", "isbn10"} \
                else "olid" if key in {"edition", "work"} else None
            if path_type:
                urls.append(
                    f"https://covers.openlibrary.org/b/{path_type}/{quote(value, safe='')}-L.jpg?default=false"
                )
    if work_olid and work_olid.startswith("OL") and work_olid.endswith("W"):
        urls.append(
            f"https://covers.openlibrary.org/b/olid/{quote(work_olid, safe='')}-L.jpg?default=false"
        )
    urls = list(dict.fromkeys(urls))
    if not urls:
        return None
    return json.dumps({
        "schemaVersion": 1,
        "sourceId": "openlibrary_cover",
        "identity": identity,
        "urls": urls,
    }, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def generate_normalized_book_catalog() -> None:
    """Keep raw subjects searchable while exposing a small deterministic genre taxonomy."""
    target = ASSETS / "book_catalog.jsonl.gz"
    records = []
    with gzip.open(target, "rt", encoding="utf-8") as source:
        for line in source:
            if not line.strip():
                continue
            record = json.loads(line)
            subject_aliases = [
                alias["title"] for alias in record.get("aliases", [])
                if alias.get("kind") == "SUBJECT" and alias.get("title")
            ]
            raw_subjects = subject_aliases or record.get("genres") or []
            aliases = [alias for alias in record.get("aliases", []) if alias.get("kind") != "SUBJECT"]
            seen = {str(alias.get("title", "")).casefold() for alias in aliases}
            for subject in raw_subjects:
                if isinstance(subject, str) and subject.strip() and subject.casefold() not in seen:
                    aliases.append({"title": subject.strip(), "language": None, "kind": "SUBJECT"})
                    seen.add(subject.casefold())
            record["genres"] = _normalized_book_genres(raw_subjects)
            record["aliases"] = aliases
            record["remoteCoverRef"] = _declarative_book_cover_reference(
                record.get("remoteCoverRef"), record["id"], record.get("providerId")
            )
            records.append(record)
    encoded = "".join(
        json.dumps(record, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n"
        for record in records
    ).encode("utf-8")
    _write_deterministic_gzip(target, encoded)
    manifest_path = ASSETS / "book_catalog.manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest.update({"sha256": sha256(target), "itemCount": len(records)})
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n",
        encoding="utf-8",
    )


def generate_book_groups() -> None:
    catalog = {}
    with gzip.open(ASSETS / "book_catalog.jsonl.gz", "rt", encoding="utf-8") as source:
        for line in source:
            if line.strip():
                record = json.loads(line)
                catalog[record["providerId"]] = record
    groups = []
    for slug, title, work_ids in BOOK_SERIES:
        missing = [work_id for work_id in work_ids if work_id not in catalog]
        if missing:
            raise ValueError(f"Curated Book series {slug} is missing Works: {missing}")
        members = [catalog[work_id] for work_id in work_ids]
        groups.append({
            "id": f"book:openlibrary:series:{slug}",
            "mediaType": "book",
            "groupType": "BOOK_SERIES",
            "provider": "openlibrary",
            "providerGroupId": f"curated:{slug}",
            "displayTitle": title,
            "representativeMediaId": members[0]["id"],
            "memberCount": len(members),
            "popularity": max((item["popularity"] for item in members if item.get("popularity") is not None), default=None),
            "popularityRank": min((item["popularityRank"] for item in members if item.get("popularityRank") is not None), default=None),
            "members": [
                {"mediaId": item["id"], "position": position}
                for position, item in enumerate(members)
            ],
        })
    encoded = "".join(
        json.dumps(record, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n"
        for record in groups
    ).encode("utf-8")
    target = ASSETS / "book_groups.jsonl.gz"
    _write_deterministic_gzip(target, encoded)
    manifest = {
        "formatVersion": 2,
        "mediaType": "book",
        "provider": "openlibrary",
        "catalogVersion": snapshot("books"),
        "sha256": sha256(target),
        "groupCount": len(groups),
        "membershipCount": sum(group["memberCount"] for group in groups),
    }
    (ASSETS / "book_groups.manifest.json").write_text(
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
    manifest = {
        "schemaVersion": 1,
        "packId": pack_id,
        "displayName": DISPLAY[pack_id],
        "version": snapshot(pack_id),
        "minRankOfflineVersion": APP_VERSION,
        "provider": {"id": provider_id, "name": provider_name},
        "coverSources": COVER_SOURCES[pack_id],
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
            "showImages": pack_id != "books",
            "stickyScoreHeader": True,
            "mergeEnabled": pack_id == "series",
            "ratingMode": "simple",
            **({"oneCoverPerGroup": True} if pack_id == "series" else {}),
        },
        "integrity": {"installedBytes": sum(item["bytes"] for item in file_specs), "files": file_specs},
    }
    background_paths = [item["path"] for item in file_specs
                        if item["path"].startswith("metadata/share_background.")]
    if background_paths:
        if len(background_paths) != 1:
            raise ValueError(f"Expected one share background for {pack_id}")
        manifest["presentation"] = {"shareBackground": background_paths[0]}
    return manifest


def optional_share_background(pack_id: str) -> list[tuple[str, bytes]]:
    """Return validated optional artwork; absence keeps older/data-only packs fully compatible."""
    candidates = [SHARE_BACKGROUNDS / f"{pack_id}{suffix}" for suffix in (".webp", ".png", ".jpg", ".jpeg")]
    existing = [path for path in candidates if path.is_file()]
    if not existing:
        return []
    if len(existing) != 1:
        raise ValueError(f"Expected one share background for {pack_id}, found {existing}")
    from prepare_pack_covers import image_extension
    source = existing[0]
    extension = image_extension(source, max_bytes=8 * 1024 * 1024)
    if extension not in {".webp", ".png", ".jpg"}:
        raise ValueError(f"Unsupported share background format: {source}")
    if source.stat().st_size > 8 * 1024 * 1024:
        raise ValueError(f"Share background is too large: {source}")
    return [("metadata/share_background.webp" if extension == ".webp" else
             f"metadata/share_background{extension}", source.read_bytes())]


def _write_entry(target: zipfile.ZipFile, path: str, contents: bytes) -> None:
    info = zipfile.ZipInfo(path, FIXED_ZIP_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    target.writestr(info, contents, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def prepared_cover_payloads(pack_id: str, required: bool) -> list[tuple[str, bytes]]:
    root = PREPARED_COVERS / pack_id
    index = root / "metadata/cover_index.json"
    report_path = root / "cover_report.json"
    if not index.is_file():
        if required:
            raise FileNotFoundError(f"Missing prepared licensed covers for {pack_id}: {index}")
        return []
    if not report_path.is_file():
        raise FileNotFoundError(f"Missing cover provenance report for {pack_id}: {report_path}")
    value = json.loads(index.read_text(encoding="utf-8"))
    report = json.loads(report_path.read_text(encoding="utf-8"))
    covers = value.get("covers", [])
    if value.get("schemaVersion") != 1 or len(covers) != 100:
        raise ValueError(f"{pack_id} must contain exactly 100 indexed covers")
    reported = {entry["canonicalId"]: entry for entry in report.get("selected", [])}
    if report.get("packId") != pack_id or len(reported) != 100:
        raise ValueError(f"{pack_id} cover provenance report is incomplete")
    payloads = [("metadata/cover_index.json", index.read_bytes())]
    seen_paths, seen_ids = set(), set()
    for cover in covers:
        path = cover.get("path", "")
        canonical_id = cover.get("canonicalId", "")
        if not canonical_id or canonical_id in seen_ids or not path.startswith("covers/") or path in seen_paths:
            raise ValueError(f"Invalid or duplicate cover path for {pack_id}: {path}")
        source = root / path
        if not source.is_file() or source.stat().st_size <= 0:
            raise FileNotFoundError(source)
        evidence = reported.get(canonical_id)
        if evidence is None or evidence.get("path") != path or evidence.get("bytes") != source.stat().st_size \
                or evidence.get("sha256") != sha256(source) or not evidence.get("licenseEvidence"):
            raise ValueError(f"Cover payload does not match provenance report: {canonical_id}")
        seen_paths.add(path)
        seen_ids.add(canonical_id)
        payloads.append((path, source.read_bytes()))
    return payloads


def build(require_covers: bool = False) -> dict:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    generate_normalized_anime_catalog()
    generate_anime_groups()
    generate_normalized_book_catalog()
    generate_book_groups()
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
        for installed_path, payload in prepared_cover_payloads(pack_id, require_covers):
            payloads.append((installed_path, payload))
            file_specs.append({"path": installed_path, "bytes": len(payload), "sha256": bytes_sha256(payload)})
        for installed_path, payload in optional_share_background(pack_id):
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
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--allow-missing-covers",
        action="store_true",
        help="Development-only: build packs without the required licensed 100-cover payloads",
    )
    args = parser.parse_args()
    result = build(require_covers=not args.allow_missing_covers)
    for pack in result["packs"]:
        print(f"{pack['packId']}: {pack['downloadBytes']} bytes -> {pack['installedBytes']} bytes")
