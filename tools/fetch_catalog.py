#!/usr/bin/env python3
"""Build the offline catalog from AnimeAPI plus AniList popularity metadata.

Usage:
    python tools/fetch_catalog.py

The resulting catalog JSON is bundled into the APK and needs no runtime network access.
Data sources: https://animeapi.my.id/animeApi.json and https://graphql.anilist.co
Database licensing: ODbL 1.0 + DbCL 1.0; retain attribution when distributing.
"""
from __future__ import annotations
import hashlib
import json
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ANIME_API_URL = "https://animeapi.my.id/animeApi.json"
ANILIST_URL = "https://graphql.anilist.co"
ANILIST_LIMIT = 5_000
ANILIST_PAGE_SIZE = 50
ANILIST_PAGE_DELAY_SECONDS = 0.75
MAX_REQUEST_ATTEMPTS = 4
OUT = Path(__file__).resolve().parents[1] / "app/src/main/assets/anime_catalog.json"
VERSION_OUT = OUT.with_name("anime_catalog.version")
USER_AGENT = "AnimeRankOffline/0.1 (+GitHub personal project)"

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


def fetch_anilist_popularity() -> list[dict]:
    result: list[dict] = []
    pages = (ANILIST_LIMIT + ANILIST_PAGE_SIZE - 1) // ANILIST_PAGE_SIZE
    for page in range(1, pages + 1):
        per_page = min(ANILIST_PAGE_SIZE, ANILIST_LIMIT - len(result))
        body = json.dumps({
            "query": ANILIST_QUERY,
            "variables": {"page": page, "perPage": per_page},
        }).encode("utf-8")
        request = Request(
            ANILIST_URL,
            data=body,
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json",
                "User-Agent": USER_AGENT,
            },
        )
        raw = fetch_json(request)
        if not isinstance(raw, dict) or raw.get("errors"):
            raise SystemExit(f"Unexpected AniList response on page {page}")
        media = raw.get("data", {}).get("Page", {}).get("media")
        if not isinstance(media, list):
            raise SystemExit(f"AniList response has no media array on page {page}")
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


def enrich_popularity(catalog: list[dict], popular: list[dict]) -> tuple[int, int]:
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
    unmatched: list[tuple[int, dict]] = []
    for rank, item in enumerate(popular, start=1):
        matches = by_anilist.get(item.get("id"), [])
        if len(matches) == 1:
            index = matches[0]
            catalog[index]["popularity"] = item.get("popularity")
            catalog[index]["popularityRank"] = rank
            matched_indexes.add(index)
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
        catalog_anilist_id = catalog[index].get("anilistId")
        if index in matched_indexes or catalog_anilist_id not in (None, item.get("id")):
            continue
        catalog[index]["popularity"] = item.get("popularity")
        catalog[index]["popularityRank"] = rank
        matched_indexes.add(index)
        fallback_matches += 1

    return len(matched_indexes), fallback_matches


def main() -> None:
    raw = fetch_anime_api()
    popular = fetch_anilist_popularity()

    seen: set[str] = set()
    normalized: list[dict] = []
    for item in raw:
        title = str(item.get("title") or "").strip()
        if not title:
            continue
        aid = stable_id(item)
        if aid in seen:
            continue
        seen.add(aid)
        normalized.append({
            "id": aid,
            "title": title,
            "malId": item.get("myanimelist"),
            "anilistId": item.get("anilist"),
            "popularity": None,
            "popularityRank": None,
        })

    matched, fallback_matches = enrich_popularity(normalized, popular)
    if matched != ANILIST_LIMIT:
        raise SystemExit(
            f"Catalog only matched {matched:,} of {ANILIST_LIMIT:,} AniList popularity entries; "
            "refusing to replace the bundled catalog"
        )
    normalized.sort(key=lambda x: x["title"].casefold())
    OUT.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(normalized, ensure_ascii=False, separators=(",", ":"))
    temporary = OUT.with_suffix(".json.tmp")
    temporary.write_text(payload, encoding="utf-8")
    temporary.replace(OUT)
    VERSION_OUT.write_text(hashlib.sha256(payload.encode("utf-8")).hexdigest() + "\n", encoding="utf-8")
    print(f"Wrote {len(normalized):,} anime to {OUT}")
    print(
        f"Matched popularity for {matched:,} entries "
        f"({fallback_matches:,} via safe MyAnimeList fallback)"
    )


if __name__ == "__main__":
    main()
