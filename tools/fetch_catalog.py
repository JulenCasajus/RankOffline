#!/usr/bin/env python3
"""Build the offline catalog from AnimeAPI plus AniList metadata.

Usage:
    python tools/fetch_catalog.py

The resulting catalog JSON is bundled into the APK and needs no runtime network access.
Data sources: https://animeapi.my.id/animeApi.json and https://graphql.anilist.co
Database licensing: ODbL 1.0 + DbCL 1.0; retain attribution when distributing.
"""
from __future__ import annotations
import hashlib
import http.client
import json
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from group_catalog import assign_groups

ANIME_API_URL = "https://animeapi.my.id/animeApi.json"
ANILIST_URL = "https://graphql.anilist.co"
ANILIST_LIMIT = 5_000
ANILIST_PAGE_SIZE = 50
ANILIST_PAGE_DELAY_SECONDS = 0.2
ANILIST_METADATA_BATCH_SIZE = 50
EXPECTED_CATALOG_SIZE = 40_742
MAX_REQUEST_ATTEMPTS = 4
OUT = Path(__file__).resolve().parents[1] / "app/src/main/assets/anime_catalog.json"
VERSION_OUT = OUT.with_name("anime_catalog.version")
METADATA_CACHE = OUT.with_name("anilist_metadata.cache.json")
RELATIONS_CACHE = OUT.with_name("anilist_relations.cache.json")
RELATIONS_MAL_CACHE = OUT.with_name("anilist_relations_mal.cache.json")
USER_AGENT = "RankOffline/0.1 (+GitHub personal project)"
_anilist_connection: http.client.HTTPSConnection | None = None

ANILIST_QUERY = """
query ($page: Int!, $perPage: Int!) {
  Page(page: $page, perPage: $perPage) {
    media(type: ANIME, sort: POPULARITY_DESC) {
      id
      idMal
      popularity
    }
  }
}
"""

ANILIST_METADATA_BY_ID_QUERY = """
query ($ids: [Int]) {
  Page(page: 1, perPage: 50) {
    media(type: ANIME, id_in: $ids) {
      id idMal genres format seasonYear status source episodes countryOfOrigin averageScore
      startDate { year month day }
      relations { edges { relationType node { id } } }
      studios(isMain: true) { nodes { name } }
    }
  }
}
"""

ANILIST_METADATA_BY_MAL_QUERY = """
query ($ids: [Int]) {
  Page(page: 1, perPage: 50) {
    media(type: ANIME, idMal_in: $ids) {
      id idMal genres format seasonYear status source episodes countryOfOrigin averageScore
      startDate { year month day }
      relations { edges { relationType node { id } } }
      studios(isMain: true) { nodes { name } }
    }
  }
}
"""

ANILIST_RELATIONS_QUERY = """
query ($ids: [Int]) {
  Page(page: 1, perPage: 50) {
    media(type: ANIME, id_in: $ids) {
      id
      startDate { year month day }
      relations { edges { relationType node { id } } }
    }
  }
}
"""

ANILIST_RELATIONS_BY_MAL_QUERY = """
query ($ids: [Int]) {
  Page(page: 1, perPage: 50) {
    media(type: ANIME, idMal_in: $ids) {
      id idMal
      startDate { year month day }
      relations { edges { relationType node { id } } }
    }
  }
}
"""


def stable_id(item: dict) -> str:
    if item.get("myanimelist") is not None:
        return f"mal:{item['myanimelist']}"
    if item.get("anilist") is not None:
        return f"anilist:{item['anilist']}"
    if item.get("kitsu") is not None:
        return f"kitsu:{item['kitsu']}"
    # deterministic fallback from title; duplicate titles get source id if possible
    source = next((f"{k}:{v}" for k, v in sorted(item.items()) if k != "title" and v not in (None, "")), None)
    return source or f"title:{item.get('title','unknown')}"


def normalize_master_catalog(raw: list[dict]) -> list[dict]:
    fresh: dict[str, dict] = {}
    for item in raw:
        title = str(item.get("title") or "").strip()
        if not title:
            continue
        aid = stable_id(item)
        fresh.setdefault(aid, {
            "id": aid,
            "title": title,
            "malId": item.get("myanimelist"),
            "anilistId": item.get("anilist"),
            "popularity": None,
            "popularityRank": None,
        })

    # Catalog identity changes are reviewed separately. Once a stable snapshot
    # exists, enrich exactly those IDs instead of silently adding/removing rows
    # whenever AnimeAPI changes between explicit catalog releases.
    if OUT.is_file():
        bundled = json.loads(OUT.read_text(encoding="utf-8"))
        if isinstance(bundled, list) and len(bundled) == EXPECTED_CATALOG_SIZE:
            bundled_by_id = {item.get("id"): item for item in bundled if isinstance(item, dict)}
            if len(bundled_by_id) == EXPECTED_CATALOG_SIZE:
                added = sorted(set(fresh) - set(bundled_by_id))
                removed = sorted(set(bundled_by_id) - set(fresh))
                if added or removed:
                    print(
                        f"AnimeAPI drift: {len(added):,} unreviewed additions and "
                        f"{len(removed):,} missing stable IDs; retaining bundled identity set",
                        flush=True,
                    )
                normalized: list[dict] = []
                for aid, old in bundled_by_id.items():
                    current = fresh.get(aid)
                    normalized.append(current or {
                        "id": aid,
                        "title": old.get("title"),
                        "malId": old.get("malId"),
                        "anilistId": old.get("anilistId"),
                        "popularity": None,
                        "popularityRank": None,
                    })
                return normalized
    return list(fresh.values())


def fetch_json(request: Request, timeout: int = 120) -> object:
    last_error: HTTPError | URLError | None = None
    for attempt in range(MAX_REQUEST_ATTEMPTS):
        try:
            with urlopen(request, timeout=timeout) as response:
                return json.load(response)
        except HTTPError as error:
            if error.code != 429 and error.code < 500:
                raise
            last_error = error
            retry_after = error.headers.get("Retry-After")
            delay = float(retry_after) if retry_after else 2 ** attempt
        except URLError as error:
            last_error = error
            delay = 2 ** attempt
        if attempt + 1 == MAX_REQUEST_ATTEMPTS:
            assert last_error is not None
            raise last_error
        time.sleep(delay)
    raise RuntimeError("unreachable")


def fetch_anime_api() -> list[dict]:
    request = Request(ANIME_API_URL, headers={"User-Agent": USER_AGENT})
    raw = fetch_json(request)
    if isinstance(raw, dict) and "data" in raw:
        raw = raw["data"]
    if not isinstance(raw, list):
        raise SystemExit("Unexpected AnimeAPI response: expected an array")
    return raw


def fetch_anilist_page(query: str, variables: dict, label: str) -> list[dict]:
    body = json.dumps({"query": query, "variables": variables}).encode("utf-8")
    headers = {
            "Content-Type": "application/json",
            "Accept": "application/json",
            "User-Agent": USER_AGENT,
            "Content-Length": str(len(body)),
    }
    global _anilist_connection
    raw: object | None = None
    last_error: Exception | None = None
    for attempt in range(MAX_REQUEST_ATTEMPTS):
        try:
            if _anilist_connection is None:
                _anilist_connection = http.client.HTTPSConnection("graphql.anilist.co", timeout=30)
            _anilist_connection.request("POST", "/", body=body, headers=headers)
            response = _anilist_connection.getresponse()
            payload = response.read()
            if 200 <= response.status < 300:
                raw = json.loads(payload)
                break
            if response.status != 429 and response.status < 500:
                raise RuntimeError(f"AniList HTTP {response.status}: {payload[:500]!r}")
            retry_after = response.getheader("Retry-After")
            delay = float(retry_after) if retry_after else 2 ** attempt
            _anilist_connection.close()
            _anilist_connection = None
            time.sleep(delay)
        except (OSError, TimeoutError, http.client.HTTPException) as error:
            last_error = error
            if _anilist_connection is not None:
                _anilist_connection.close()
            _anilist_connection = None
            if attempt + 1 < MAX_REQUEST_ATTEMPTS:
                time.sleep(2 ** attempt)
    if raw is None:
        raise RuntimeError(f"AniList request failed for {label}") from last_error
    if not isinstance(raw, dict) or raw.get("errors"):
        raise SystemExit(f"Unexpected AniList response for {label}: {raw!r}")
    media = raw.get("data", {}).get("Page", {}).get("media")
    if not isinstance(media, list):
        raise SystemExit(f"AniList response has no media array for {label}")
    return [item for item in media if isinstance(item, dict)]


def fetch_anilist_popularity() -> list[dict]:
    result: list[dict] = []
    pages = (ANILIST_LIMIT + ANILIST_PAGE_SIZE - 1) // ANILIST_PAGE_SIZE
    for page in range(1, pages + 1):
        per_page = min(ANILIST_PAGE_SIZE, ANILIST_LIMIT - len(result))
        media = fetch_anilist_page(
            ANILIST_QUERY,
            {"page": page, "perPage": per_page},
            f"popularity page {page}",
        )
        result.extend(media)
        if len(media) < per_page:
            break
        if page < pages:
            time.sleep(ANILIST_PAGE_DELAY_SECONDS)
    result = result[:ANILIST_LIMIT]
    valid_ids = [item.get("id") for item in result if isinstance(item.get("id"), int)]
    if len(result) != ANILIST_LIMIT or len(valid_ids) != ANILIST_LIMIT:
        raise SystemExit(
            f"Incomplete AniList response: received {len(result):,} of "
            f"{ANILIST_LIMIT:,} entries ({len(valid_ids):,} valid IDs)"
        )
    if len(set(valid_ids)) != ANILIST_LIMIT:
        raise SystemExit("Invalid AniList response: duplicate IDs in Top 5000")
    return result


def chunks(values: list[int], size: int) -> list[list[int]]:
    return [values[index:index + size] for index in range(0, len(values), size)]


def load_media_cache(path: Path) -> dict[int, dict]:
    if not path.is_file():
        return {}
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        return {}
    return {int(key): value for key, value in raw.items() if isinstance(value, dict)}


def save_media_cache(path: Path, cache: dict[int, dict]) -> None:
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(
        json.dumps(cache, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    temporary.replace(path)


def fetch_anilist_metadata(catalog: list[dict]) -> tuple[dict[int, dict], dict[int, dict]]:
    """Fetch by known AniList IDs; use unique MAL IDs only for unresolved rows."""
    cache = load_media_cache(METADATA_CACHE)
    anilist_ids = sorted({
        item["anilistId"] for item in catalog if isinstance(item.get("anilistId"), int)
    })
    missing_ids = [anilist_id for anilist_id in anilist_ids if anilist_id not in cache]
    batches = chunks(missing_ids, ANILIST_METADATA_BATCH_SIZE)
    for batch_number, batch in enumerate(batches, start=1):
        for media in fetch_anilist_page(
            ANILIST_METADATA_BY_ID_QUERY,
            {"ids": batch},
            f"metadata ID batch {batch_number}/{len(batches)}",
        ):
            if isinstance(media.get("id"), int):
                cache[media["id"]] = media
        if batch_number % 10 == 0 or batch_number == len(batches):
            save_media_cache(METADATA_CACHE, cache)
            print(
                f"AniList metadata by ID: {batch_number}/{len(batches)} batches "
                f"({len(cache):,} records cached)",
                flush=True,
            )
        if batch_number < len(batches):
            time.sleep(ANILIST_PAGE_DELAY_SECONDS)

    unresolved = [
        item for item in catalog
        if not isinstance(item.get("anilistId"), int) or item["anilistId"] not in cache
    ]
    mal_counts: dict[int, int] = {}
    for item in catalog:
        if isinstance(item.get("malId"), int):
            mal_counts[item["malId"]] = mal_counts.get(item["malId"], 0) + 1
    fallback_mal_ids = sorted({
        item["malId"] for item in unresolved
        if isinstance(item.get("malId"), int) and mal_counts[item["malId"]] == 1
    })
    by_mal: dict[int, dict] = {}
    mal_batches = chunks(fallback_mal_ids, ANILIST_METADATA_BATCH_SIZE)
    for batch_number, batch in enumerate(mal_batches, start=1):
        for media in fetch_anilist_page(
            ANILIST_METADATA_BY_MAL_QUERY,
            {"ids": batch},
            f"metadata MAL fallback batch {batch_number}/{len(mal_batches)}",
        ):
            if isinstance(media.get("idMal"), int):
                by_mal[media["idMal"]] = media
        if batch_number < len(mal_batches):
            time.sleep(ANILIST_PAGE_DELAY_SECONDS)
    return cache, by_mal


def fetch_anilist_relations(catalog: list[dict]) -> dict[int, dict]:
    """Relation-only refresh for the already versioned catalog snapshot."""
    cache = load_media_cache(RELATIONS_CACHE)
    ids = sorted({item["anilistId"] for item in catalog if isinstance(item.get("anilistId"), int)})
    missing = [anilist_id for anilist_id in ids if anilist_id not in cache]
    batches = chunks(missing, ANILIST_METADATA_BATCH_SIZE)
    for batch_number, batch in enumerate(batches, start=1):
        for media in fetch_anilist_page(
            ANILIST_RELATIONS_QUERY, {"ids": batch},
            f"relations batch {batch_number}/{len(batches)}",
        ):
            if isinstance(media.get("id"), int):
                cache[media["id"]] = media
        if batch_number % 10 == 0 or batch_number == len(batches):
            save_media_cache(RELATIONS_CACHE, cache)
            print(f"AniList relations: {batch_number}/{len(batches)} batches ({len(cache):,} records)", flush=True)
        if batch_number < len(batches):
            time.sleep(ANILIST_PAGE_DELAY_SECONDS)
    return cache


def fetch_relations_by_mal(catalog: list[dict]) -> dict[int, dict]:
    """Fallback only for unique MAL IDs without a known AniList ID."""
    cache = load_media_cache(RELATIONS_MAL_CACHE)
    counts: dict[int, int] = {}
    for item in catalog:
        if isinstance(item.get("malId"), int):
            counts[item["malId"]] = counts.get(item["malId"], 0) + 1
    ids = sorted({item["malId"] for item in catalog
                  if not isinstance(item.get("anilistId"), int)
                  and isinstance(item.get("malId"), int)
                  and counts[item["malId"]] == 1})
    batches = chunks([mal_id for mal_id in ids if mal_id not in cache], ANILIST_METADATA_BATCH_SIZE)
    for batch_number, batch in enumerate(batches, start=1):
        matches: dict[int, list[dict]] = {}
        for media in fetch_anilist_page(
            ANILIST_RELATIONS_BY_MAL_QUERY, {"ids": batch},
            f"relations MAL batch {batch_number}/{len(batches)}",
        ):
            if isinstance(media.get("idMal"), int) and media["idMal"] in batch:
                matches.setdefault(media["idMal"], []).append(media)
        for mal_id, found in matches.items():
            if len(found) == 1 and isinstance(found[0].get("id"), int):
                cache[mal_id] = found[0]
        if batch_number % 10 == 0 or batch_number == len(batches):
            save_media_cache(RELATIONS_MAL_CACHE, cache)
            print(f"AniList relations MAL fallback: {batch_number}/{len(batches)} batches ({len(cache):,} records)", flush=True)
        if batch_number < len(batches):
            time.sleep(ANILIST_PAGE_DELAY_SECONDS)
    return cache


def apply_relations(item: dict, media: dict | None) -> None:
    item.pop("relationAniListId", None)
    if item.get("anilistId") is None and isinstance((media or {}).get("id"), int):
        item["relationAniListId"] = media["id"]
    raw_date = (media or {}).get("startDate") or {}
    item["startDate"] = {
        part: raw_date.get(part) for part in ("year", "month", "day")
    } if isinstance(raw_date.get("year"), int) else None
    edges = ((media or {}).get("relations") or {}).get("edges") or []
    item["relations"] = sorted(
        ({"type": edge["relationType"], "anilistId": edge["node"]["id"]}
         for edge in edges
         if isinstance(edge, dict) and isinstance(edge.get("node"), dict)
         and isinstance(edge["node"].get("id"), int)
         and isinstance(edge.get("relationType"), str)),
        key=lambda edge: (edge["type"], edge["anilistId"]),
    )


def apply_metadata(item: dict, media: dict | None) -> None:
    apply_relations(item, media)
    item["genres"] = []
    item["format"] = None
    item["seasonYear"] = None
    item["status"] = None
    item["source"] = None
    item["episodes"] = None
    item["countryOfOrigin"] = None
    item["averageScore"] = None
    item["studios"] = []
    if media is None:
        return
    item["genres"] = sorted({value for value in media.get("genres") or [] if isinstance(value, str)})
    for key in ("format", "seasonYear", "status", "source", "episodes", "countryOfOrigin", "averageScore"):
        item[key] = media.get(key)
    nodes = (media.get("studios") or {}).get("nodes") or []
    item["studios"] = sorted({
        node["name"].strip() for node in nodes
        if isinstance(node, dict) and isinstance(node.get("name"), str) and node["name"].strip()
    })


def metadata_coverage(catalog: list[dict]) -> dict[str, int]:
    metadata_fields = (
        "genres", "format", "seasonYear", "status", "source", "episodes",
        "countryOfOrigin", "averageScore", "studios",
    )
    return {
        "matched": sum(any(item[field] not in (None, [], "") for field in metadata_fields) for item in catalog),
        "genres": sum(bool(item["genres"]) for item in catalog),
        "format": sum(item["format"] is not None for item in catalog),
        "seasonYear": sum(item["seasonYear"] is not None for item in catalog),
        "status": sum(item["status"] is not None for item in catalog),
        "source": sum(item["source"] is not None for item in catalog),
        "episodes": sum(item["episodes"] is not None for item in catalog),
        "countryOfOrigin": sum(item["countryOfOrigin"] is not None for item in catalog),
        "averageScore": sum(item["averageScore"] is not None for item in catalog),
        "studios": sum(bool(item["studios"]) for item in catalog),
    }


def enrich_popularity(catalog: list[dict], popular: list[dict]) -> tuple[int, int, list[tuple[int, dict]]]:
    by_anilist: dict[int, list[int]] = {}
    by_mal: dict[int, list[int]] = {}
    for index, item in enumerate(catalog):
        item["popularity"] = None
        item["popularityRank"] = None
        if isinstance(item.get("anilistId"), int):
            by_anilist.setdefault(item["anilistId"], []).append(index)
        if isinstance(item.get("malId"), int):
            by_mal.setdefault(item["malId"], []).append(index)

    matched_indexes: set[int] = set()
    matched_ranks: set[int] = set()
    unmatched: list[tuple[int, dict]] = []
    for rank, item in enumerate(popular, start=1):
        matches = by_anilist.get(item.get("id"), [])
        if len(matches) == 1:
            index = matches[0]
            catalog[index]["popularity"] = item.get("popularity")
            catalog[index]["popularityRank"] = rank
            matched_indexes.add(index)
            matched_ranks.add(rank)
        else:
            unmatched.append((rank, item))

    # MAL is only a safe fallback when both sides are unique, the catalog does
    # not contradict AniList's ID, and the row was not already associated.
    popular_mal_counts: dict[int, int] = {}
    for item in popular:
        if isinstance(item.get("idMal"), int):
            popular_mal_counts[item["idMal"]] = popular_mal_counts.get(item["idMal"], 0) + 1

    fallback_matches = 0
    for rank, item in unmatched:
        mal_id = item.get("idMal")
        if not isinstance(mal_id, int) or popular_mal_counts.get(mal_id) != 1:
            continue
        matches = by_mal.get(mal_id, [])
        if len(matches) != 1:
            continue
        index = matches[0]
        if index in matched_indexes or by_anilist.get(item.get("id")):
            continue
        # AniList IDs can be superseded upstream while the MAL identity remains
        # stable. A unique MAL match with no competing current AniList ID is safe;
        # update the stale ID so subsequent refreshes match AniList directly.
        catalog[index]["anilistId"] = item["id"]
        catalog[index]["popularity"] = item.get("popularity")
        catalog[index]["popularityRank"] = rank
        matched_indexes.add(index)
        matched_ranks.add(rank)
        fallback_matches += 1

    unresolved = [
        (rank, item)
        for rank, item in enumerate(popular, start=1)
        if rank not in matched_ranks
    ]
    return len(matched_indexes), fallback_matches, unresolved


def write_catalog(normalized: list[dict]) -> None:
    if len(normalized) != EXPECTED_CATALOG_SIZE or len({item["id"] for item in normalized}) != EXPECTED_CATALOG_SIZE:
        raise SystemExit("Catalog count/identity validation failed; asset was not replaced")
    ranks = sorted(item["popularityRank"] for item in normalized if item.get("popularityRank") is not None)
    if ranks != list(range(1, ANILIST_LIMIT + 1)):
        raise SystemExit("Top 5000 validation failed; asset was not replaced")
    normalized.sort(key=lambda x: x["title"].casefold())
    stats = assign_groups(normalized)
    print("Group coverage: " + json.dumps(stats, sort_keys=True), flush=True)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(normalized, ensure_ascii=False, separators=(",", ":"))
    temporary = OUT.with_suffix(".json.tmp")
    temporary.write_text(payload, encoding="utf-8")
    temporary.replace(OUT)
    version_temporary = VERSION_OUT.with_suffix(".version.tmp")
    version_temporary.write_text(
        hashlib.sha256(payload.encode("utf-8")).hexdigest() + "\n", encoding="utf-8"
    )
    version_temporary.replace(VERSION_OUT)
    print(f"Wrote {len(normalized):,} anime to {OUT}")


def main() -> None:
    import sys
    if sys.argv[1:] == ["--cache-relations"]:
        normalized = json.loads(OUT.read_text(encoding="utf-8"))
        relations = fetch_anilist_relations(normalized)
        save_media_cache(RELATIONS_CACHE, relations)
        print(f"Cached relations for {len(relations):,} AniList IDs; catalog assets unchanged")
        return
    if sys.argv[1:] == ["--relations-only"]:
        normalized = json.loads(OUT.read_text(encoding="utf-8"))
        relations = fetch_anilist_relations(normalized)
        by_mal = fetch_relations_by_mal(normalized)
        for item in normalized:
            media = relations.get(item.get("anilistId"))
            if media is None and item.get("anilistId") is None:
                media = by_mal.get(item.get("malId"))
            apply_relations(item, media)
        write_catalog(normalized)
        RELATIONS_CACHE.unlink(missing_ok=True)
        RELATIONS_MAL_CACHE.unlink(missing_ok=True)
        print(f"Relation metadata unavailable for {sum(isinstance(item.get('anilistId'), int) and item['anilistId'] not in relations for item in normalized):,} known AniList IDs")
        return
    if sys.argv[1:] == ["--regroup-local"]:
        normalized = json.loads(OUT.read_text(encoding="utf-8"))
        write_catalog(normalized)
        return
    if sys.argv[1:]:
        raise SystemExit("Usage: fetch_catalog.py [--cache-relations|--relations-only|--regroup-local]")
    raw = fetch_anime_api()
    normalized = normalize_master_catalog(raw)
    unique_ids = {item["id"] for item in normalized}

    if len(normalized) != EXPECTED_CATALOG_SIZE or len(unique_ids) != EXPECTED_CATALOG_SIZE:
        raise SystemExit(
            f"AnimeAPI normalization produced {len(normalized):,} unique entries; "
            f"expected exactly {EXPECTED_CATALOG_SIZE:,}. Refusing to replace the bundled catalog."
        )

    metadata_by_anilist, metadata_by_mal = fetch_anilist_metadata(normalized)
    metadata_fallbacks = 0
    for item in normalized:
        media = metadata_by_anilist.get(item.get("anilistId"))
        if media is None and isinstance(item.get("malId"), int):
            media = metadata_by_mal.get(item["malId"])
            if media is not None:
                metadata_fallbacks += 1
                item["anilistId"] = media.get("id") or item.get("anilistId")
        apply_metadata(item, media)

    popular = fetch_anilist_popularity()

    matched, fallback_matches, unresolved = enrich_popularity(normalized, popular)
    if matched != ANILIST_LIMIT:
        valid_ids = sum(isinstance(item.get("id"), int) for item in popular)
        raise SystemExit(
            f"Incomplete catalog enrichment: downloaded={len(popular):,}, "
            f"valid_ids={valid_ids:,}, matched={matched:,}, "
            f"unmatched={len(unresolved):,}, "
            f"unmatched_entries={[(rank, item.get('id'), item.get('idMal')) for rank, item in unresolved]}; "
            "refusing to replace the bundled catalog"
        )
    write_catalog(normalized)
    METADATA_CACHE.unlink(missing_ok=True)
    print(
        f"Matched popularity for {matched:,} entries "
        f"({fallback_matches:,} via safe MyAnimeList fallback)"
    )
    print(f"AniList metadata MAL fallbacks: {metadata_fallbacks:,}")
    print("AniList metadata coverage: " + json.dumps(metadata_coverage(normalized), sort_keys=True))


if __name__ == "__main__":
    main()
