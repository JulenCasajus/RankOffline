#!/usr/bin/env python3
"""Fetch a bounded, resumable Open Library Work whitelist ordered by readinglog_count."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys
import time
from typing import Any, Callable
import urllib.error
import urllib.parse
import urllib.request
import re


HOST = "openlibrary.org"
# Open Library Search accepts bounded pages of up to 1,000 records. Using that
# verified endpoint capability keeps the production whitelist to 50 cached
# requests instead of 500 while retaining the one-request-per-second pacing.
PAGE_SIZE = 1_000
SEARCH_WINDOW = 10_000
MAX_RESPONSE_BYTES = 4 * 1024 * 1024
CACHE_FORMAT = "cover-identifiers-v1"
ISBN_13_PATTERN = re.compile(r"^97[89][0-9]{10}$")
ISBN_10_PATTERN = re.compile(r"^[0-9]{9}[0-9X]$")
EDITION_ID_PATTERN = re.compile(r"^OL[0-9]+M$")


class PopularWorksError(RuntimeError):
    pass


def fetch_popular_works(
    count: int,
    cache_dir: Path,
    opener: Callable[..., Any] = urllib.request.urlopen,
    sleep: Callable[[float], None] = time.sleep,
    progress_stream: Any = sys.stderr,
) -> list[dict[str, Any]]:
    if count <= 0:
        raise ValueError("count must be positive")
    cache_dir.mkdir(parents=True, exist_ok=True)
    unique: dict[str, dict[str, Any]] = {}
    upper_count: int | None = None
    while len(unique) < count:
        query = "*:*" if upper_count is None else f"readinglog_count:[1 TO {upper_count}]"
        cache_tag = "readinglog" if upper_count is None else f"readinglog-up-to-{upper_count}"
        docs, total = _fetch_query_window(
            query, cache_tag, cache_dir, opener, sleep, progress_stream
        )
        mapped = [record for doc in docs if (record := map_search_result(doc)) is not None]
        if not mapped:
            break
        boundary_count = min(int(record["readinglogCount"]) for record in mapped)

        # Search results have a 10,000-row deep-pagination boundary. Re-fetch
        # the boundary count as a complete bucket so ties can be ordered by
        # Work ID, then continue below that count in the next bounded window.
        if total > len(docs):
            tie_docs, tie_total = _fetch_query_window(
                f"readinglog_count:{boundary_count}",
                f"readinglog-count-{boundary_count}",
                cache_dir,
                opener,
                sleep,
                progress_stream,
            )
            if tie_total > SEARCH_WINDOW:
                raise PopularWorksError(
                    f"readinglog_count={boundary_count} has {tie_total} Works, "
                    f"exceeding the deterministic {SEARCH_WINDOW}-record tie window"
                )
            mapped = [
                record for record in mapped if int(record["readinglogCount"]) > boundary_count
            ]
            mapped.extend(
                record
                for doc in tie_docs
                if (record := map_search_result(doc)) is not None
            )

        for record in mapped:
            unique.setdefault(str(record["workId"]), record)
        print(f"Selected {len(unique)} / {count} popular Works", file=progress_stream)
        if total <= len(docs) or boundary_count <= 0:
            break
        upper_count = boundary_count - 1

    ordered = sorted(
        unique.values(),
        key=lambda record: (-int(record["readinglogCount"]), str(record["workId"])),
    )[:count]
    for rank, record in enumerate(ordered, start=1):
        record["popularityRank"] = rank
    return ordered


def _fetch_query_window(
    query: str,
    cache_tag: str,
    cache_dir: Path,
    opener: Callable[..., Any],
    sleep: Callable[[float], None],
    progress_stream: Any,
) -> tuple[list[Any], int]:
    docs: list[Any] = []
    total: int | None = None
    offset = 0
    while offset < SEARCH_WINDOW:
        limit = min(PAGE_SIZE, SEARCH_WINDOW - offset)
        # Do not reuse the older rank-only cache: those responses omitted the
        # identifiers required for deterministic cover fallback candidates.
        cache_path = cache_dir / f"{cache_tag}-{CACHE_FORMAT}-{offset:06d}-{limit}.json"
        if cache_path.is_file():
            payload = json.loads(cache_path.read_text(encoding="utf-8"))
        else:
            payload = _request_page(offset, limit, opener, sleep, query=query)
            _write_json_atomic(cache_path, payload)
        page = payload.get("docs")
        if not isinstance(page, list):
            raise PopularWorksError("Open Library Search response has no docs array")
        if total is None:
            raw_total = payload.get("numFound")
            total = int(raw_total) if isinstance(raw_total, (int, float)) else SEARCH_WINDOW
        if not page:
            break
        docs.extend(page)
        offset += len(page)
        print(
            f"Fetched {len(docs)} / {min(total, SEARCH_WINDOW)} for {query}",
            file=progress_stream,
        )
        if len(page) < limit or len(docs) >= total:
            break
        sleep(1.0)
    return docs, total if total is not None else 0


def map_search_result(value: Any) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        return None
    key = value.get("key")
    work_id = key.removeprefix("/works/") if isinstance(key, str) else ""
    if not (work_id.startswith("OL") and work_id.endswith("W") and work_id[2:-1].isdigit()):
        return None
    count = value.get("readinglog_count")
    if isinstance(count, bool) or not isinstance(count, (int, float)) or count < 0:
        count = 0
    result: dict[str, Any] = {"workId": work_id, "readinglogCount": int(count)}
    cover_id = value.get("cover_i")
    if isinstance(cover_id, int) and not isinstance(cover_id, bool) and cover_id > 0:
        result["coverId"] = str(cover_id)
    normalized_isbns = [_normalize_isbn(candidate) for candidate in _string_values(value.get("isbn"))]
    result.update(_first_identifier_fields(normalized_isbns))
    edition_olid = next(
        (
            candidate.strip().upper()
            for candidate in _string_values(value.get("edition_key"))
            if EDITION_ID_PATTERN.fullmatch(candidate.strip().upper())
        ),
        None,
    )
    if edition_olid is not None:
        result["editionOlid"] = edition_olid
    return result


def _string_values(value: Any) -> list[str]:
    if isinstance(value, str):
        return [value]
    if isinstance(value, list):
        return [candidate for candidate in value if isinstance(candidate, str)]
    return []


def _normalize_isbn(value: str) -> str:
    return re.sub(r"[-\s]", "", value).upper()


def _first_identifier_fields(values: list[str]) -> dict[str, str]:
    result: dict[str, str] = {}
    for value in values:
        if "isbn13" not in result and ISBN_13_PATTERN.fullmatch(value):
            result["isbn13"] = value
        elif "isbn10" not in result and ISBN_10_PATTERN.fullmatch(value):
            result["isbn10"] = value
        if len(result) == 2:
            break
    return result


def write_selection(path: Path, records: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    try:
        with temporary.open("w", encoding="utf-8", newline="\n") as target:
            for record in records:
                target.write(json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n")
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _request_page(
    offset: int,
    limit: int,
    opener: Callable[..., Any],
    sleep: Callable[[float], None],
    query: str = "*: *".replace(" ", ""),
) -> dict[str, Any]:
    parameters = urllib.parse.urlencode(
        {
            "q": query,
            "sort": "readinglog",
            "fields": "key,readinglog_count,cover_i,isbn,edition_key",
            "limit": limit,
            "offset": offset,
        }
    )
    url = f"https://{HOST}/search.json?{parameters}"
    for attempt in range(4):
        request = urllib.request.Request(
            url,
            headers={"Accept": "application/json", "User-Agent": "RankOffline-catalog-tool/1.0"},
        )
        try:
            with opener(request, timeout=30) as response:
                payload = response.read(MAX_RESPONSE_BYTES + 1)
                if len(payload) > MAX_RESPONSE_BYTES:
                    raise PopularWorksError("Open Library response exceeded size limit")
            value = json.loads(payload.decode("utf-8"))
            if not isinstance(value, dict):
                raise PopularWorksError("Open Library returned malformed JSON")
            return value
        except urllib.error.HTTPError as error:
            if error.code not in {429, 500, 502, 503, 504} or attempt == 3:
                raise PopularWorksError(f"Open Library Search failed with HTTP {error.code}") from error
            retry_after = error.headers.get("Retry-After") if error.headers else None
            delay = float(retry_after) if retry_after and retry_after.isdigit() else 2**attempt
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            if attempt == 3:
                raise PopularWorksError("Open Library Search failed after retries") from error
            delay = 2**attempt
        sleep(delay)
    raise AssertionError("unreachable")


def _write_json_atomic(path: Path, value: dict[str, Any]) -> None:
    temporary = path.with_name(path.name + ".tmp")
    try:
        temporary.write_text(
            json.dumps(value, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
        )
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--count", type=int, default=50_000)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--cache-dir",
        type=Path,
        default=Path(__file__).resolve().parents[1] / ".cache" / "openlibrary" / "popular",
    )
    args = parser.parse_args(argv)
    if args.count <= 0:
        parser.error("--count must be positive")
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    records = fetch_popular_works(args.count, args.cache_dir)
    if len(records) != args.count:
        raise PopularWorksError(f"Requested {args.count} Works but selected {len(records)}")
    write_selection(args.output, records)
    print(f"Wrote {len(records)} ranked Work IDs to {args.output}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (PopularWorksError, OSError, json.JSONDecodeError) as error:
        print(f"Error: {error}", file=sys.stderr)
        raise SystemExit(1)
