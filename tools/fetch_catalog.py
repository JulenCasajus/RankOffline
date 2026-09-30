#!/usr/bin/env python3
"""Download AnimeAPI's master array and normalize it for the Android offline asset.

Usage:
    python tools/fetch_catalog.py

The resulting JSON is bundled into the APK, so the app itself does not need Internet.
Data source: https://animeapi.my.id/animeApi.json
Database licensing: ODbL 1.0 + DbCL 1.0; retain attribution when distributing.
"""
from __future__ import annotations
import json
from pathlib import Path
from urllib.request import Request, urlopen

URL = "https://animeapi.my.id/animeApi.json"
OUT = Path(__file__).resolve().parents[1] / "app/src/main/assets/anime_catalog.json"


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


def main() -> None:
    req = Request(URL, headers={"User-Agent": "AnimeRankOffline/0.1 (+GitHub personal project)"})
    with urlopen(req, timeout=120) as response:
        raw = json.load(response)
    if isinstance(raw, dict) and "data" in raw:
        raw = raw["data"]
    if not isinstance(raw, list):
        raise SystemExit("Unexpected AnimeAPI response: expected an array")

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
        })

    normalized.sort(key=lambda x: x["title"].casefold())
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(normalized, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"Wrote {len(normalized):,} anime to {OUT}")


if __name__ == "__main__":
    main()
