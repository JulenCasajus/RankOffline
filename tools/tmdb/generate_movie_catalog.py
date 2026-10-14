#!/usr/bin/env python3
"""Generate deterministic RankOffline Movie catalog artifacts from TMDb metadata."""

from __future__ import annotations

import argparse
import concurrent.futures
import dataclasses
import datetime as dt
import gzip
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sys
import threading
import time
from typing import Any, Callable, Iterable, Iterator
import urllib.error
import urllib.parse
import urllib.request


API_BASE = "https://api.themoviedb.org"
FORMAT_VERSION = 1
MEDIA_TYPE = "movie"
PROVIDER = "tmdb"
TRANSIENT_HTTP_CODES = {429, 500, 502, 503, 504}
AUTH_HTTP_CODES = {401, 403}
MAX_RESPONSE_BYTES = 5 * 1024 * 1024
DEFAULT_WORKERS = 4
DEFAULT_REQUESTS_PER_SECOND = 4.0
DEFAULT_TIMEOUT_SECONDS = 20.0
DEFAULT_MAX_ATTEMPTS = 3


class TmdbToolError(RuntimeError):
    pass


class TmdbAuthenticationError(TmdbToolError):
    pass


@dataclasses.dataclass(frozen=True)
class MovieFetchResult:
    movie_id: int
    details: dict[str, Any] | None
    cached: bool = False
    unavailable: bool = False


@dataclasses.dataclass(frozen=True)
class CollectionMembership:
    movie_id: int
    media_id: str
    release_date: str | None
    collection_id: int
    collection_name: str
    collection_poster_path: str | None


class TmdbClient:
    """Small fixed-host TMDb client with resumable per-movie cache."""

    def __init__(
        self,
        token: str,
        cache_dir: Path,
        requests_per_second: float = DEFAULT_REQUESTS_PER_SECOND,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
        max_attempts: int = DEFAULT_MAX_ATTEMPTS,
        opener: Callable[..., Any] = urllib.request.urlopen,
        sleep: Callable[[float], None] = time.sleep,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        if not token.strip():
            raise TmdbAuthenticationError(
                "TMDB_READ_ACCESS_TOKEN is required for TMDb API enrichment."
            )
        if requests_per_second <= 0 or timeout <= 0 or max_attempts <= 0:
            raise ValueError("Rate, timeout, and max attempts must be positive")
        self._token = token
        self._cache_dir = cache_dir
        self._request_interval = 1.0 / requests_per_second
        self._timeout = timeout
        self._max_attempts = max_attempts
        self._opener = opener
        self._sleep = sleep
        self._clock = clock
        self._rate_lock = threading.Lock()
        self._last_request_started = 0.0

    def movie_details(self, movie_id: int) -> MovieFetchResult:
        return self._details(
            resource="movie",
            resource_id=movie_id,
            append_to_response="credits,alternative_titles",
        )

    def tv_details(self, series_id: int) -> MovieFetchResult:
        return self._details(
            resource="tv",
            resource_id=series_id,
            append_to_response="alternative_titles",
        )

    def _details(
        self,
        resource: str,
        resource_id: int,
        append_to_response: str,
    ) -> MovieFetchResult:
        cache_path = self._cache_dir / resource / f"{resource_id}.json"
        cached = _read_cached_details(cache_path, resource_id)
        if cached is not None:
            return MovieFetchResult(resource_id, cached, cached=True)

        query = urllib.parse.urlencode(
            {"language": "en-US", "append_to_response": append_to_response}
        )
        url = f"{API_BASE}/3/{resource}/{resource_id}?{query}"
        for attempt in range(self._max_attempts):
            self._wait_for_rate_slot()
            request = urllib.request.Request(
                url,
                headers={
                    "Authorization": f"Bearer {self._token}",
                    "Accept": "application/json",
                    "User-Agent": "RankOffline-catalog-tool/1.0",
                },
                method="GET",
            )
            try:
                with self._opener(request, timeout=self._timeout) as response:
                    payload = response.read(MAX_RESPONSE_BYTES + 1)
                    if len(payload) > MAX_RESPONSE_BYTES:
                        raise TmdbToolError(
                            f"TMDb response too large for {resource} {resource_id}"
                        )
                details = json.loads(payload.decode("utf-8"))
                _validate_details_identity(details, resource_id)
                _write_json_atomic(cache_path, details)
                return MovieFetchResult(resource_id, details)
            except urllib.error.HTTPError as error:
                if error.code == 404:
                    return MovieFetchResult(resource_id, None, unavailable=True)
                if error.code in AUTH_HTTP_CODES:
                    raise TmdbAuthenticationError(
                        f"TMDb authentication failed with HTTP {error.code}."
                    ) from error
                if error.code not in TRANSIENT_HTTP_CODES:
                    raise TmdbToolError(
                        f"TMDb request for {resource} {resource_id} failed with HTTP {error.code}."
                    ) from error
                retry_after = error.headers.get("Retry-After") if error.headers else None
                delay = _retry_delay(retry_after, attempt)
            except (urllib.error.URLError, TimeoutError, OSError) as error:
                delay = _retry_delay(None, attempt)
                if attempt + 1 == self._max_attempts:
                    raise TmdbToolError(
                        f"TMDb request for {resource} {resource_id} failed after retries."
                    ) from error
            except (json.JSONDecodeError, UnicodeDecodeError, ValueError) as error:
                raise TmdbToolError(
                    f"Malformed TMDb response for {resource} {resource_id}."
                ) from error

            if attempt + 1 == self._max_attempts:
                raise TmdbToolError(
                    f"TMDb request for {resource} {resource_id} failed after "
                    f"{self._max_attempts} attempts."
                )
            self._sleep(delay)
        raise AssertionError("Unreachable retry loop")

    def _wait_for_rate_slot(self) -> None:
        with self._rate_lock:
            now = self._clock()
            wait_seconds = self._request_interval - (now - self._last_request_started)
            if wait_seconds > 0:
                self._sleep(wait_seconds)
            self._last_request_started = self._clock()


def map_movie_details(details: dict[str, Any]) -> tuple[dict[str, Any], CollectionMembership | None]:
    movie_id = _positive_int(details.get("id"), "movie id")
    title = _nonblank(details.get("title"))
    if title is None:
        raise TmdbToolError(f"TMDb movie {movie_id} has no title")
    original_title = _nonblank(details.get("original_title"))
    release_date = _valid_date(details.get("release_date"))
    popularity = _finite_float(details.get("popularity"))
    media_id = f"movie:tmdb:{movie_id}"
    genres = _unique_strings(
        entry.get("name") for entry in _list_of_dicts(details.get("genres"))
    )
    credits = _movie_credits(details)
    aliases = _movie_aliases(details, title, original_title)
    record = {
        "id": media_id,
        "mediaType": MEDIA_TYPE,
        "provider": PROVIDER,
        "providerId": str(movie_id),
        "title": title,
        "originalTitle": original_title,
        "year": int(release_date[:4]) if release_date else None,
        "startDate": release_date,
        "format": "MOVIE",
        "status": _nonblank(details.get("status")),
        "originalLanguage": _nonblank(details.get("original_language")),
        "demographic": None,
        "partNumber": None,
        "popularity": popularity,
        "popularityRank": None,
        "remoteCoverRef": _nonblank(details.get("poster_path")),
        "genres": genres,
        "credits": credits,
        "aliases": aliases,
    }

    collection = details.get("belongs_to_collection")
    membership = None
    if collection is not None:
        if not isinstance(collection, dict):
            raise TmdbToolError(f"Invalid collection metadata for movie {movie_id}")
        collection_id = _positive_int(collection.get("id"), "collection id")
        collection_name = _nonblank(collection.get("name"))
        if collection_name is None:
            raise TmdbToolError(f"TMDb collection {collection_id} has no name")
        membership = CollectionMembership(
            movie_id=movie_id,
            media_id=media_id,
            release_date=release_date,
            collection_id=collection_id,
            collection_name=collection_name,
            collection_poster_path=_nonblank(collection.get("poster_path")),
        )
    return record, membership


def build_collection_groups(memberships: Iterable[CollectionMembership]) -> list[dict[str, Any]]:
    by_collection: dict[int, list[CollectionMembership]] = {}
    for membership in memberships:
        by_collection.setdefault(membership.collection_id, []).append(membership)

    groups: list[dict[str, Any]] = []
    for collection_id in sorted(by_collection):
        members = sorted(by_collection[collection_id], key=_collection_member_sort_key)
        if len(members) < 2:
            continue
        representative_metadata = members[0]
        groups.append(
            {
                "id": f"movie:tmdb:collection:{collection_id}",
                "mediaType": MEDIA_TYPE,
                "groupType": "COLLECTION",
                "provider": PROVIDER,
                "providerGroupId": str(collection_id),
                "displayTitle": representative_metadata.collection_name,
                "remoteCoverRef": next(
                    (member.collection_poster_path for member in members if member.collection_poster_path),
                    None,
                ),
                "members": [
                    {"mediaId": member.media_id, "position": position}
                    for position, member in enumerate(members)
                ],
            }
        )
    return groups


def read_daily_export(
    path: Path,
    limit: int | None,
    include_adult: bool = False,
    include_video: bool = False,
) -> Iterator[int]:
    selected = 0
    with gzip.open(path, "rt", encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            if not line.strip():
                continue
            try:
                entry = json.loads(line)
                movie_id = _positive_int(entry.get("id"), "movie id")
            except (json.JSONDecodeError, AttributeError, TypeError, ValueError) as error:
                raise TmdbToolError(f"Malformed daily export line {line_number}") from error
            if not include_adult and entry.get("adult") is True:
                continue
            if not include_video and entry.get("video") is True:
                continue
            yield movie_id
            selected += 1
            if limit is not None and selected >= limit:
                return


def generate_artifacts(
    details_by_id: dict[int, dict[str, Any]],
    output_dir: Path,
    catalog_version: str,
) -> dict[str, Any]:
    if not catalog_version.strip():
        raise ValueError("catalog version must not be blank")
    records: list[dict[str, Any]] = []
    memberships: list[CollectionMembership] = []
    for movie_id in sorted(details_by_id):
        details = details_by_id[movie_id]
        _validate_details_identity(details, movie_id)
        record, membership = map_movie_details(details)
        records.append(record)
        if membership is not None:
            memberships.append(membership)
    groups = build_collection_groups(memberships)

    output_dir.mkdir(parents=True, exist_ok=True)
    catalog_path = output_dir / "movie_catalog.jsonl.gz"
    group_path = output_dir / "movie_groups.jsonl.gz"
    _write_deterministic_jsonl_gzip(catalog_path, records)
    _write_deterministic_jsonl_gzip(group_path, groups)
    catalog_manifest = _artifact_manifest(catalog_path, catalog_version, "itemCount", len(records))
    group_manifest = _artifact_manifest(group_path, catalog_version, "groupCount", len(groups))
    _write_json_atomic(output_dir / "movie_catalog.manifest.json", catalog_manifest)
    _write_json_atomic(output_dir / "movie_groups.manifest.json", group_manifest)
    return {
        "records": records,
        "groups": groups,
        "catalogManifest": catalog_manifest,
        "groupManifest": group_manifest,
    }


def enrich_movies(
    movie_ids: Iterable[int],
    client: TmdbClient,
    workers: int = DEFAULT_WORKERS,
    progress_stream: Any = sys.stderr,
) -> tuple[dict[int, dict[str, Any]], list[int]]:
    return enrich_resources(movie_ids, client.movie_details, workers, progress_stream)


def enrich_resources(
    resource_ids: Iterable[int],
    fetch_details: Callable[[int], MovieFetchResult],
    workers: int = DEFAULT_WORKERS,
    progress_stream: Any = sys.stderr,
) -> tuple[dict[int, dict[str, Any]], list[int]]:
    ids = sorted(set(resource_ids))
    if workers <= 0:
        raise ValueError("workers must be positive")
    details: dict[int, dict[str, Any]] = {}
    unavailable: list[int] = []
    cached = 0
    fetched = 0
    processed = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {executor.submit(fetch_details, resource_id): resource_id for resource_id in ids}
        try:
            for future in concurrent.futures.as_completed(futures):
                result = future.result()
                processed += 1
                if result.unavailable:
                    unavailable.append(result.movie_id)
                elif result.details is not None:
                    details[result.movie_id] = result.details
                    if result.cached:
                        cached += 1
                    else:
                        fetched += 1
                if processed % 100 == 0 or processed == len(ids):
                    print(
                        f"Processed {processed} / {len(ids)} | Cached: {cached} | "
                        f"Fetched: {fetched} | Skipped: {len(unavailable)} | Failed: 0",
                        file=progress_stream,
                    )
        except Exception:
            for pending in futures:
                pending.cancel()
            raise
    return details, sorted(unavailable)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ids", nargs="*", type=int, default=[])
    parser.add_argument("--ids-file", type=Path)
    parser.add_argument("--export-file", type=Path)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--all-from-export", action="store_true")
    parser.add_argument("--confirm-large-run", action="store_true")
    parser.add_argument("--include-adult", action="store_true")
    parser.add_argument("--include-video", action="store_true")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--catalog-version", required=True)
    parser.add_argument(
        "--cache-dir",
        type=Path,
        default=Path(__file__).resolve().parents[1] / ".cache" / "tmdb",
    )
    parser.add_argument("--workers", type=int, default=DEFAULT_WORKERS)
    parser.add_argument("--requests-per-second", type=float, default=DEFAULT_REQUESTS_PER_SECOND)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    if args.limit is not None and args.limit <= 0:
        parser.error("--limit must be positive")
    if args.export_file and args.limit is None and not (
        args.all_from_export and args.confirm_large_run
    ):
        parser.error(
            "--export-file requires --limit, or both --all-from-export and --confirm-large-run"
        )
    if args.all_from_export != args.confirm_large_run:
        parser.error("large export mode requires both --all-from-export and --confirm-large-run")
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    ids = _selected_ids(args)
    if not ids:
        raise TmdbToolError("No TMDb movie IDs were selected.")
    if args.dry_run:
        print(f"Selected {len(ids)} unique TMDb movie IDs; no API requests or files written.")
        return 0

    token = os.environ.get("TMDB_READ_ACCESS_TOKEN", "")
    client = TmdbClient(token, args.cache_dir, args.requests_per_second)
    details, unavailable = enrich_movies(ids, client, args.workers)
    generated = generate_artifacts(details, args.output_dir, args.catalog_version)
    print(
        f"Generated {len(generated['records'])} movies and {len(generated['groups'])} collections. "
        f"Unavailable IDs: {len(unavailable)}."
    )
    if unavailable:
        print("Unavailable TMDb IDs: " + ",".join(str(movie_id) for movie_id in unavailable))
    return 0


def _selected_ids(args: argparse.Namespace) -> list[int]:
    ids = list(args.ids)
    if args.ids_file:
        ids.extend(_read_ids_file(args.ids_file))
    if args.export_file:
        ids.extend(
            read_daily_export(
                args.export_file,
                args.limit,
                include_adult=args.include_adult,
                include_video=args.include_video,
            )
        )
    valid = []
    for movie_id in ids:
        valid.append(_positive_int(movie_id, "movie id"))
    return sorted(set(valid))


def _read_ids_file(path: Path) -> list[int]:
    ids: list[int] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        content = line.split("#", 1)[0].strip()
        if not content:
            continue
        for token in re.split(r"[\s,]+", content):
            try:
                ids.append(_positive_int(int(token), "movie id"))
            except ValueError as error:
                raise TmdbToolError(f"Invalid movie ID at {path}:{line_number}") from error
    return ids


def _movie_credits(details: dict[str, Any]) -> list[dict[str, str]]:
    pairs: set[tuple[str, str]] = set()
    for company in _list_of_dicts(details.get("production_companies")):
        name = _nonblank(company.get("name"))
        if name:
            pairs.add(("PRODUCTION_COMPANY", name))
    credits = details.get("credits")
    if isinstance(credits, dict):
        for crew in _list_of_dicts(credits.get("crew")):
            if crew.get("job") == "Director":
                name = _nonblank(crew.get("name"))
                if name:
                    pairs.add(("DIRECTOR", name))
    return [
        {"role": role, "name": name}
        for role, name in sorted(pairs, key=lambda pair: (pair[0], pair[1].casefold(), pair[1]))
    ]


def _movie_aliases(
    details: dict[str, Any],
    canonical_title: str,
    original_title: str | None,
) -> list[dict[str, str | None]]:
    excluded = {canonical_title}
    if original_title:
        excluded.add(original_title)
    by_title: dict[str, dict[str, str | None]] = {}
    alternative_titles = details.get("alternative_titles")
    if isinstance(alternative_titles, dict):
        for entry in _list_of_dicts(alternative_titles.get("titles")):
            title = _nonblank(entry.get("title"))
            if not title or title in excluded or title in by_title:
                continue
            by_title[title] = {
                "title": title,
                "language": None,
                "kind": _nonblank(entry.get("iso_3166_1")),
            }
    return sorted(by_title.values(), key=lambda alias: (alias["title"].casefold(), alias["title"]))


def _collection_member_sort_key(member: CollectionMembership) -> tuple[int, str, int]:
    return (1 if member.release_date is None else 0, member.release_date or "", member.movie_id)


def _write_deterministic_jsonl_gzip(path: Path, records: Iterable[dict[str, Any]]) -> None:
    temporary = path.with_name(path.name + ".tmp")
    try:
        with temporary.open("wb") as raw:
            with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
                for record in records:
                    line = json.dumps(
                        record,
                        ensure_ascii=False,
                        separators=(",", ":"),
                        allow_nan=False,
                    ).encode("utf-8")
                    compressed.write(line + b"\n")
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def _artifact_manifest(
    path: Path,
    version: str,
    count_key: str,
    count: int,
    media_type: str = MEDIA_TYPE,
    provider: str = PROVIDER,
) -> dict[str, Any]:
    return {
        "formatVersion": FORMAT_VERSION,
        "mediaType": media_type,
        "provider": provider,
        "catalogVersion": version,
        "sha256": _sha256_file(path),
        count_key: count,
    }


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _write_json_atomic(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    try:
        temporary.write_text(
            json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n",
            encoding="utf-8",
        )
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def _read_cached_details(path: Path, movie_id: int) -> dict[str, Any] | None:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        _validate_details_identity(value, movie_id)
        return value
    except (OSError, json.JSONDecodeError, TypeError, ValueError, TmdbToolError):
        return None


def _validate_details_identity(details: Any, movie_id: int) -> None:
    if not isinstance(details, dict) or details.get("id") != movie_id:
        raise TmdbToolError(f"TMDb response identity mismatch for movie {movie_id}")


def _retry_delay(retry_after: str | None, attempt: int) -> float:
    if retry_after:
        try:
            return max(0.0, min(float(retry_after), 60.0))
        except ValueError:
            pass
    return float(2**attempt)


def _positive_int(value: Any, field: str) -> int:
    if isinstance(value, bool):
        raise ValueError(f"{field} must be a positive integer")
    result = int(value)
    if result <= 0 or result != value:
        raise ValueError(f"{field} must be a positive integer")
    return result


def _nonblank(value: Any) -> str | None:
    return value.strip() if isinstance(value, str) and value.strip() else None


def _valid_date(value: Any) -> str | None:
    text = _nonblank(value)
    if text is None:
        return None
    try:
        return dt.date.fromisoformat(text).isoformat()
    except ValueError:
        return None


def _finite_float(value: Any) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


def _list_of_dicts(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    return [entry for entry in value if isinstance(entry, dict)]


def _unique_strings(values: Iterable[Any]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        text = _nonblank(value)
        if text and text not in seen:
            seen.add(text)
            result.append(text)
    return result


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except TmdbToolError as error:
        print(f"Error: {error}", file=sys.stderr)
        raise SystemExit(1)
