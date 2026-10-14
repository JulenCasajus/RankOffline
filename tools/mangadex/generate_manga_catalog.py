#!/usr/bin/env python3
"""Generate deterministic RankOffline Manga catalogs from bounded MangaDex inputs."""

from __future__ import annotations

import argparse
import concurrent.futures
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import threading
import time
from typing import Any, Callable, Iterable
import urllib.error
import urllib.parse
import urllib.request
import uuid


API_BASE = "https://api.mangadex.org"
FORMAT_VERSION = 1
MEDIA_TYPE = "manga"
PROVIDER = "mangadex"
GROUP_TYPE = "VARIANTS"
MAX_PAGE_SIZE = 100
MAX_ALIASES = 30
MAX_RESPONSE_BYTES = 20 * 1024 * 1024
DEFAULT_WORKERS = 2
DEFAULT_REQUESTS_PER_SECOND = 2.0
DEFAULT_TIMEOUT_SECONDS = 20.0
DEFAULT_MAX_ATTEMPTS = 3
TRANSIENT_HTTP_CODES = {429, 500, 502, 503, 504}
AUTH_HTTP_CODES = {401, 403}
MANGA_INCLUDES = ("cover_art", "author", "artist")

# Verified against the official MangaDex OpenAPI enum on 2026-10-15:
# https://api.mangadex.org/docs/static/api.yaml
VARIANT_RELATION_ALLOWLIST = frozenset(
    {"alternate_version", "colored", "monochrome", "preserialization", "serialization"}
)


class MangaDexToolError(RuntimeError):
    pass


class MangaDexClient:
    """Fixed-host, read-only client with bounded requests and filesystem caching."""

    def __init__(
        self,
        cache_dir: Path,
        requests_per_second: float = DEFAULT_REQUESTS_PER_SECOND,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
        max_attempts: int = DEFAULT_MAX_ATTEMPTS,
        opener: Callable[..., Any] = urllib.request.urlopen,
        sleep: Callable[[float], None] = time.sleep,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        if requests_per_second <= 0 or requests_per_second > 2:
            raise ValueError("requests per second must be in (0, 2]")
        if timeout <= 0 or max_attempts <= 0:
            raise ValueError("timeout and max attempts must be positive")
        self._cache_dir = cache_dir
        self._request_interval = 1.0 / requests_per_second
        self._timeout = timeout
        self._max_attempts = max_attempts
        self._opener = opener
        self._sleep = sleep
        self._clock = clock
        self._rate_lock = threading.Lock()
        self._last_request_started = 0.0

    def manga_by_ids(
        self,
        manga_ids: Iterable[str],
        progress_stream: Any = sys.stderr,
    ) -> tuple[dict[str, dict[str, Any]], list[str]]:
        selected = sorted({_canonical_uuid(value) for value in manga_ids})
        results: dict[str, dict[str, Any]] = {}
        missing: list[str] = []
        uncached: list[str] = []
        cached_count = 0
        for manga_id in selected:
            cached = _read_cached_manga(self._manga_cache_path(manga_id), manga_id)
            if cached is None:
                uncached.append(manga_id)
            else:
                results[manga_id] = cached
                cached_count += 1

        fetched = 0
        for batch in _chunks(uncached, MAX_PAGE_SIZE):
            response = self._get_json(
                "/manga",
                [("ids[]", value) for value in batch]
                + [("includes[]", value) for value in MANGA_INCLUDES]
                + [("limit", str(len(batch)))],
            )
            resources = _manga_collection(response)
            returned: set[str] = set()
            for resource in resources:
                manga_id = _resource_manga_id(resource)
                if manga_id not in batch or manga_id in returned:
                    raise MangaDexToolError("MangaDex batch response contains an unexpected identity")
                returned.add(manga_id)
                results[manga_id] = resource
                _write_json_atomic(self._manga_cache_path(manga_id), resource)
                fetched += 1
            missing.extend(sorted(set(batch) - returned))
            processed = cached_count + fetched + len(missing)
            print(
                f"Processed {processed} / {len(selected)} | Cached: {cached_count} | "
                f"Fetched: {fetched} | Skipped: {len(missing)} | Failed: 0",
                file=progress_stream,
            )
        if selected and not uncached:
            print(
                f"Processed {len(selected)} / {len(selected)} | Cached: {cached_count} | "
                "Fetched: 0 | Skipped: 0 | Failed: 0",
                file=progress_stream,
            )
        return results, sorted(missing)

    def discover(self, limit: int, progress_stream: Any = sys.stderr) -> dict[str, dict[str, Any]]:
        if limit <= 0:
            raise ValueError("discovery limit must be positive")
        discovered: dict[str, dict[str, Any]] = {}
        offset = 0
        while len(discovered) < limit:
            page_size = min(MAX_PAGE_SIZE, limit - len(discovered))
            response = self._get_json(
                "/manga",
                [("limit", str(page_size)), ("offset", str(offset)), ("order[createdAt]", "asc")]
                + [("includes[]", value) for value in MANGA_INCLUDES],
            )
            resources = _manga_collection(response)
            if not resources:
                break
            for resource in resources:
                manga_id = _resource_manga_id(resource)
                if manga_id in discovered:
                    raise MangaDexToolError("MangaDex discovery returned a duplicate manga identity")
                discovered[manga_id] = resource
                _write_json_atomic(self._manga_cache_path(manga_id), resource)
                if len(discovered) == limit:
                    break
            offset += len(resources)
            print(f"Discovered {len(discovered)} / {limit}", file=progress_stream)
            total = response.get("total")
            if len(resources) < page_size or (isinstance(total, int) and offset >= total):
                break
        return discovered

    def relations(self, manga_id: str) -> dict[str, Any]:
        manga_id = _canonical_uuid(manga_id)
        cache_path = self._cache_dir / "relations" / f"{manga_id}.json"
        cached = _read_cached_relations(cache_path)
        if cached is not None:
            return cached
        response = self._get_json(
            f"/manga/{manga_id}/relation",
            [("includes[]", "manga")],
            allow_not_found=True,
        )
        if response is None:
            response = {"result": "ok", "response": "collection", "data": []}
        _relation_collection(response)
        _write_json_atomic(cache_path, response)
        return response

    def _manga_cache_path(self, manga_id: str) -> Path:
        return self._cache_dir / "manga" / f"{manga_id}.json"

    def _get_json(
        self,
        path: str,
        query: list[tuple[str, str]],
        allow_not_found: bool = False,
    ) -> dict[str, Any] | None:
        if not path.startswith("/"):
            raise ValueError("MangaDex API path must be absolute")
        url = API_BASE + path
        if query:
            url += "?" + urllib.parse.urlencode(query)
        for attempt in range(self._max_attempts):
            self._wait_for_rate_slot()
            request = urllib.request.Request(
                url,
                headers={
                    "Accept": "application/json",
                    "User-Agent": "RankOffline-catalog-tool/1.0",
                },
                method="GET",
            )
            try:
                with self._opener(request, timeout=self._timeout) as response:
                    payload = response.read(MAX_RESPONSE_BYTES + 1)
                    if len(payload) > MAX_RESPONSE_BYTES:
                        raise MangaDexToolError("MangaDex response exceeded the size limit")
                value = json.loads(payload.decode("utf-8"))
                if not isinstance(value, dict) or value.get("result") != "ok":
                    raise MangaDexToolError("Malformed MangaDex response")
                return value
            except urllib.error.HTTPError as error:
                if error.code == 404 and allow_not_found:
                    return None
                if error.code in AUTH_HTTP_CODES:
                    raise MangaDexToolError(
                        f"MangaDex unexpectedly rejected the read-only request with HTTP {error.code}."
                    ) from error
                if error.code not in TRANSIENT_HTTP_CODES:
                    raise MangaDexToolError(f"MangaDex request failed with HTTP {error.code}.") from error
                retry_after = error.headers.get("Retry-After") if error.headers else None
                delay = _retry_delay(retry_after, attempt)
            except (urllib.error.URLError, TimeoutError, OSError) as error:
                delay = _retry_delay(None, attempt)
                if attempt + 1 == self._max_attempts:
                    raise MangaDexToolError("MangaDex request failed after retries.") from error
            except (json.JSONDecodeError, UnicodeDecodeError) as error:
                raise MangaDexToolError("Malformed MangaDex JSON response.") from error
            if attempt + 1 == self._max_attempts:
                raise MangaDexToolError(
                    f"MangaDex request failed after {self._max_attempts} attempts."
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


def resolve_canonical_title(attributes: dict[str, Any]) -> str:
    titles = _localized_strings(attributes.get("title"))
    english = titles.get("en")
    if english:
        return english
    romanized = sorted(
        ((language, title) for language, title in titles.items() if language.endswith("-ro")),
        key=lambda pair: pair[0],
    )
    if romanized:
        return romanized[0][1]
    original_language = _nonblank(attributes.get("originalLanguage"))
    if original_language and titles.get(original_language):
        return titles[original_language]
    if titles:
        return sorted(titles.items(), key=lambda pair: pair[0])[0][1]
    raise MangaDexToolError("MangaDex manga has no usable title")


def resolve_original_title(attributes: dict[str, Any], canonical_title: str) -> str | None:
    language = _nonblank(attributes.get("originalLanguage"))
    if language is None:
        return None
    titles = _localized_strings(attributes.get("title"))
    candidate = titles.get(language)
    if candidate is None:
        candidates = []
        for entry in _list_of_dicts(attributes.get("altTitles")):
            value = _localized_strings(entry).get(language)
            if value:
                candidates.append(value)
        candidate = sorted(set(candidates), key=lambda value: (value.casefold(), value))[0] if candidates else None
    return candidate if candidate and candidate != canonical_title else None


def map_manga(resource: dict[str, Any]) -> dict[str, Any]:
    manga_id = _resource_manga_id(resource)
    attributes = resource.get("attributes")
    if not isinstance(attributes, dict):
        raise MangaDexToolError(f"MangaDex manga {manga_id} has invalid attributes")
    title = resolve_canonical_title(attributes)
    original_title = resolve_original_title(attributes, title)
    return {
        "id": f"manga:mangadex:{manga_id}",
        "mediaType": MEDIA_TYPE,
        "provider": PROVIDER,
        "providerId": manga_id,
        "title": title,
        "originalTitle": original_title,
        "year": _valid_year(attributes.get("year")),
        "startDate": None,
        "format": "MANGA",
        "status": _nonblank(attributes.get("status")),
        "originalLanguage": _nonblank(attributes.get("originalLanguage")),
        "demographic": _nonblank(attributes.get("publicationDemographic")),
        "partNumber": None,
        "popularity": None,
        "popularityRank": None,
        "remoteCoverRef": _cover_filename(resource),
        "genres": _genres(attributes),
        "credits": _credits(resource),
        "aliases": _aliases(attributes, title, original_title),
    }


def build_variant_groups(
    records: Iterable[dict[str, Any]],
    relations_by_manga: dict[str, dict[str, Any]],
) -> list[dict[str, Any]]:
    records_by_uuid = {record["providerId"]: record for record in records}
    parent = {manga_id: manga_id for manga_id in records_by_uuid}

    def find(value: str) -> str:
        while parent[value] != value:
            parent[value] = parent[parent[value]]
            value = parent[value]
        return value

    def union(left: str, right: str) -> None:
        left_root, right_root = find(left), find(right)
        if left_root == right_root:
            return
        smaller, larger = sorted((left_root, right_root))
        parent[larger] = smaller

    for source_id in sorted(relations_by_manga):
        if source_id not in records_by_uuid:
            continue
        for relation in _relation_collection(relations_by_manga[source_id]):
            attributes = relation.get("attributes")
            relation_type = attributes.get("relation") if isinstance(attributes, dict) else None
            if relation_type not in VARIANT_RELATION_ALLOWLIST:
                continue
            targets = sorted(
                {
                    _canonical_uuid(relationship.get("id"))
                    for relationship in _list_of_dicts(relation.get("relationships"))
                    if relationship.get("type") == "manga" and relationship.get("id") != source_id
                }
            )
            for target_id in targets:
                if target_id in records_by_uuid:
                    union(source_id, target_id)

    components: dict[str, list[str]] = {}
    for manga_id in sorted(records_by_uuid):
        components.setdefault(find(manga_id), []).append(manga_id)

    groups = []
    for member_ids in sorted(
        (sorted(component) for component in components.values() if len(component) >= 2),
        key=lambda component: component,
    ):
        digest = hashlib.sha256(
            ("mangadex-variants-v1\n" + "\n".join(member_ids)).encode("utf-8")
        ).hexdigest()
        representative = records_by_uuid[member_ids[0]]
        ordered = sorted(
            (records_by_uuid[manga_id] for manga_id in member_ids),
            key=lambda record: (
                record["year"] is None,
                record["year"] or 0,
                record["title"].casefold(),
                record["title"],
                record["id"],
            ),
        )
        groups.append(
            {
                "id": f"manga:mangadex:variants:{digest}",
                "mediaType": MEDIA_TYPE,
                "groupType": GROUP_TYPE,
                "provider": PROVIDER,
                "providerGroupId": digest,
                "displayTitle": representative["title"],
                # The shared group format has no representative UUID for MangaDex cover resolution.
                "remoteCoverRef": None,
                "members": [
                    {"mediaId": record["id"], "position": position}
                    for position, record in enumerate(ordered)
                ],
            }
        )
    return groups


def fetch_relations(
    manga_ids: Iterable[str],
    client: MangaDexClient,
    workers: int = DEFAULT_WORKERS,
    progress_stream: Any = sys.stderr,
) -> dict[str, dict[str, Any]]:
    selected = sorted({_canonical_uuid(value) for value in manga_ids})
    if workers <= 0 or workers > 2:
        raise ValueError("workers must be in [1, 2]")
    results: dict[str, dict[str, Any]] = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {executor.submit(client.relations, manga_id): manga_id for manga_id in selected}
        try:
            for processed, future in enumerate(concurrent.futures.as_completed(futures), start=1):
                manga_id = futures[future]
                results[manga_id] = future.result()
                if processed % 100 == 0 or processed == len(selected):
                    print(f"Relations {processed} / {len(selected)}", file=progress_stream)
        except Exception:
            for pending in futures:
                pending.cancel()
            raise
    return results


def generate_artifacts(
    manga_by_id: dict[str, dict[str, Any]],
    relations_by_manga: dict[str, dict[str, Any]],
    output_dir: Path,
    catalog_version: str,
) -> dict[str, Any]:
    if not catalog_version.strip():
        raise ValueError("catalog version must not be blank")
    records = [map_manga(manga_by_id[manga_id]) for manga_id in sorted(manga_by_id)]
    groups = build_variant_groups(records, relations_by_manga)
    output_dir.mkdir(parents=True, exist_ok=True)
    catalog_path = output_dir / "manga_catalog.jsonl.gz"
    group_path = output_dir / "manga_groups.jsonl.gz"
    _write_deterministic_jsonl_gzip(catalog_path, records)
    _write_deterministic_jsonl_gzip(group_path, groups)
    catalog_manifest = _artifact_manifest(
        catalog_path, catalog_version, "itemCount", len(records)
    )
    group_manifest = _artifact_manifest(group_path, catalog_version, "groupCount", len(groups))
    _write_json_atomic(output_dir / "manga_catalog.manifest.json", catalog_manifest)
    _write_json_atomic(output_dir / "manga_groups.manifest.json", group_manifest)
    return {
        "records": records,
        "groups": groups,
        "catalogManifest": catalog_manifest,
        "groupManifest": group_manifest,
    }


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ids", nargs="*", default=[])
    parser.add_argument("--ids-file", type=Path)
    parser.add_argument("--discover", action="store_true")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--include-variants", action="store_true")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--catalog-version", required=True)
    parser.add_argument(
        "--cache-dir",
        type=Path,
        default=Path(__file__).resolve().parents[1] / ".cache" / "mangadex",
    )
    parser.add_argument("--workers", type=int, default=DEFAULT_WORKERS)
    parser.add_argument("--requests-per-second", type=float, default=DEFAULT_REQUESTS_PER_SECOND)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    if args.discover and (args.limit is None or args.limit <= 0):
        parser.error("--discover requires a positive --limit")
    if args.limit is not None and not args.discover:
        parser.error("--limit is only valid with --discover")
    if args.workers <= 0 or args.workers > 2:
        parser.error("--workers must be 1 or 2")
    if args.requests_per_second <= 0 or args.requests_per_second > 2:
        parser.error("--requests-per-second must be in (0, 2]")
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    explicit_ids = list(args.ids)
    if args.ids_file:
        explicit_ids.extend(_read_ids_file(args.ids_file))
    explicit_ids = sorted({_canonical_uuid(value) for value in explicit_ids})
    if not explicit_ids and not args.discover:
        raise MangaDexToolError("No MangaDex manga IDs were selected.")
    if args.dry_run:
        discovery = f" plus up to {args.limit} discovered IDs" if args.discover else ""
        print(f"Selected {len(explicit_ids)} explicit MangaDex IDs{discovery}; no requests or files written.")
        return 0

    client = MangaDexClient(args.cache_dir, args.requests_per_second)
    manga_by_id: dict[str, dict[str, Any]] = {}
    unavailable: list[str] = []
    if explicit_ids:
        selected, unavailable = client.manga_by_ids(explicit_ids)
        manga_by_id.update(selected)
    if args.discover:
        manga_by_id.update(client.discover(args.limit))
    relations = (
        fetch_relations(manga_by_id, client, args.workers) if args.include_variants else {}
    )
    generated = generate_artifacts(
        manga_by_id, relations, args.output_dir, args.catalog_version
    )
    print(
        f"Generated {len(generated['records'])} manga and {len(generated['groups'])} "
        f"variant groups. Unavailable IDs: {len(unavailable)}."
    )
    if unavailable:
        print("Unavailable MangaDex IDs: " + ",".join(unavailable))
    return 0


def _resource_manga_id(resource: Any) -> str:
    if not isinstance(resource, dict) or resource.get("type") != "manga":
        raise MangaDexToolError("MangaDex resource is not a manga")
    return _canonical_uuid(resource.get("id"))


def _manga_collection(response: Any) -> list[dict[str, Any]]:
    if not isinstance(response, dict) or response.get("result") != "ok":
        raise MangaDexToolError("Malformed MangaDex manga collection")
    data = response.get("data")
    if not isinstance(data, list) or not all(isinstance(entry, dict) for entry in data):
        raise MangaDexToolError("Malformed MangaDex manga data")
    return data


def _relation_collection(response: Any) -> list[dict[str, Any]]:
    if not isinstance(response, dict) or response.get("result") != "ok":
        raise MangaDexToolError("Malformed MangaDex relation collection")
    data = response.get("data")
    if not isinstance(data, list) or not all(isinstance(entry, dict) for entry in data):
        raise MangaDexToolError("Malformed MangaDex relation data")
    return data


def _genres(attributes: dict[str, Any]) -> list[str]:
    values = []
    for tag in _list_of_dicts(attributes.get("tags")):
        tag_attributes = tag.get("attributes")
        names = _localized_strings(tag_attributes.get("name")) if isinstance(tag_attributes, dict) else {}
        if names:
            values.append(names.get("en") or sorted(names.items(), key=lambda pair: pair[0])[0][1])
    return _unique_sorted_strings(values)


def _credits(resource: dict[str, Any]) -> list[dict[str, str]]:
    values: set[tuple[str, str]] = set()
    for relationship in _list_of_dicts(resource.get("relationships")):
        role = {"author": "AUTHOR", "artist": "ARTIST"}.get(relationship.get("type"))
        attributes = relationship.get("attributes")
        name = _nonblank(attributes.get("name")) if isinstance(attributes, dict) else None
        if role and name:
            values.add((role, name))
    return [
        {"role": role, "name": name}
        for role, name in sorted(values, key=lambda pair: (pair[0], pair[1].casefold(), pair[1]))
    ]


def _cover_filename(resource: dict[str, Any]) -> str | None:
    for relationship in _list_of_dicts(resource.get("relationships")):
        if relationship.get("type") != "cover_art":
            continue
        attributes = relationship.get("attributes")
        filename = _nonblank(attributes.get("fileName")) if isinstance(attributes, dict) else None
        if filename:
            return filename
    return None


def _aliases(
    attributes: dict[str, Any],
    canonical_title: str,
    original_title: str | None,
) -> list[dict[str, str | None]]:
    excluded = {canonical_title.casefold()}
    if original_title:
        excluded.add(original_title.casefold())
    candidates: list[tuple[str, str]] = []
    for entry in _list_of_dicts(attributes.get("altTitles")):
        for language, title in _localized_strings(entry).items():
            if title.casefold() not in excluded:
                candidates.append((language, title))
    candidates.sort(key=lambda pair: (pair[1].casefold(), pair[1], pair[0]))
    result = []
    seen: set[str] = set()
    for language, title in candidates:
        key = title.casefold()
        if key in seen:
            continue
        seen.add(key)
        result.append({"title": title, "language": language, "kind": None})
        if len(result) == MAX_ALIASES:
            break
    return result


def _localized_strings(value: Any) -> dict[str, str]:
    if not isinstance(value, dict):
        return {}
    return {
        language: text
        for language, raw in value.items()
        if isinstance(language, str) and (text := _nonblank(raw)) is not None
    }


def _list_of_dicts(value: Any) -> list[dict[str, Any]]:
    return [entry for entry in value if isinstance(entry, dict)] if isinstance(value, list) else []


def _nonblank(value: Any) -> str | None:
    return value.strip() if isinstance(value, str) and value.strip() else None


def _valid_year(value: Any) -> int | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        year = int(value)
    except (TypeError, ValueError):
        return None
    return year if year == value and 1 <= year <= 9999 else None


def _unique_sorted_strings(values: Iterable[Any]) -> list[str]:
    unique = {_nonblank(value) for value in values}
    return sorted((value for value in unique if value), key=lambda value: (value.casefold(), value))


def _canonical_uuid(value: Any) -> str:
    try:
        return str(uuid.UUID(str(value)))
    except (ValueError, TypeError, AttributeError) as error:
        raise MangaDexToolError(f"Invalid MangaDex UUID: {value}") from error


def _read_ids_file(path: Path) -> list[str]:
    values = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        content = line.split("#", 1)[0].strip()
        if not content:
            continue
        for token in re.split(r"[\s,]+", content):
            try:
                values.append(_canonical_uuid(token))
            except MangaDexToolError as error:
                raise MangaDexToolError(f"Invalid MangaDex ID at {path}:{line_number}") from error
    return values


def _chunks(values: list[str], size: int) -> Iterable[list[str]]:
    for index in range(0, len(values), size):
        yield values[index:index + size]


def _write_deterministic_jsonl_gzip(path: Path, records: Iterable[dict[str, Any]]) -> None:
    temporary = path.with_name(path.name + ".tmp")
    try:
        with temporary.open("wb") as raw:
            with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
                for record in records:
                    line = json.dumps(
                        record, ensure_ascii=False, separators=(",", ":"), allow_nan=False
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
) -> dict[str, Any]:
    return {
        "formatVersion": FORMAT_VERSION,
        "mediaType": MEDIA_TYPE,
        "provider": PROVIDER,
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


def _read_cached_manga(path: Path, manga_id: str) -> dict[str, Any] | None:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        if _resource_manga_id(value) != manga_id:
            return None
        return value
    except (OSError, json.JSONDecodeError, MangaDexToolError):
        return None


def _read_cached_relations(path: Path) -> dict[str, Any] | None:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        _relation_collection(value)
        return value
    except (OSError, json.JSONDecodeError, MangaDexToolError):
        return None


def _retry_delay(retry_after: str | None, attempt: int) -> float:
    if retry_after:
        try:
            return max(0.0, min(float(retry_after), 60.0))
        except ValueError:
            pass
    return float(2**attempt)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except MangaDexToolError as error:
        print(f"Error: {error}", file=sys.stderr)
        raise SystemExit(1)
