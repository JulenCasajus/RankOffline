#!/usr/bin/env python3
"""Generate deterministic RankOffline Series season catalogs from TMDb metadata."""

from __future__ import annotations

import argparse
import dataclasses
import gzip
import json
import os
from pathlib import Path
import sys
from typing import Any, Iterable, Iterator

import generate_movie_catalog as common


FORMAT_VERSION = 1
MEDIA_TYPE = "series"
PROVIDER = "tmdb"
MAX_ALIASES_PER_SEASON = 20


@dataclasses.dataclass(frozen=True)
class SeasonCatalogItem:
    series_id: int
    season_number: int
    record: dict[str, Any]


def map_series_details(
    details: dict[str, Any],
) -> tuple[list[dict[str, Any]], dict[str, Any] | None]:
    series_id = common._positive_int(details.get("id"), "series id")
    series_name = common._nonblank(details.get("name"))
    if series_name is None:
        raise common.TmdbToolError(f"TMDb TV series {series_id} has no name")
    original_name = common._nonblank(details.get("original_name"))
    original_title = original_name if original_name and original_name != series_name else None
    parent_poster = common._nonblank(details.get("poster_path"))
    genres = common._unique_strings(
        entry.get("name") for entry in common._list_of_dicts(details.get("genres"))
    )
    credits = _series_credits(details)
    popularity = common._finite_float(details.get("popularity"))

    items: list[SeasonCatalogItem] = []
    seen_seasons: set[int] = set()
    for season in common._list_of_dicts(details.get("seasons")):
        raw_season_number = season.get("season_number")
        if raw_season_number == 0:
            continue
        try:
            season_number = common._positive_int(raw_season_number, "season number")
        except (TypeError, ValueError) as error:
            raise common.TmdbToolError(
                f"Invalid season number for TMDb TV series {series_id}"
            ) from error
        if season_number in seen_seasons:
            raise common.TmdbToolError(
                f"Duplicate season {season_number} for TMDb TV series {series_id}"
            )
        seen_seasons.add(season_number)

        season_name = common._nonblank(season.get("name")) or f"Season {season_number}"
        air_date = common._valid_date(season.get("air_date"))
        media_id = f"series:tmdb:{series_id}:season:{season_number}"
        record = {
            "id": media_id,
            "mediaType": MEDIA_TYPE,
            "provider": PROVIDER,
            "providerId": f"{series_id}:season:{season_number}",
            "title": f"{series_name} — {season_name}",
            "originalTitle": original_title,
            "year": int(air_date[:4]) if air_date else None,
            "startDate": air_date,
            "format": "TV_SEASON",
            "status": common._nonblank(details.get("status")),
            "originalLanguage": common._nonblank(details.get("original_language")),
            "demographic": None,
            "partNumber": season_number,
            "popularity": popularity,
            "popularityRank": None,
            "remoteCoverRef": common._nonblank(season.get("poster_path")) or parent_poster,
            "genres": genres,
            "credits": credits,
            "aliases": _season_aliases(details, series_name, original_name, season_name),
        }
        items.append(SeasonCatalogItem(series_id, season_number, record))

    items.sort(key=lambda item: item.season_number)
    records = [item.record for item in items]
    group = None
    if len(items) >= 2:
        group = {
            "id": f"series:tmdb:{series_id}",
            "mediaType": MEDIA_TYPE,
            "groupType": "SEASONS",
            "provider": PROVIDER,
            "providerGroupId": str(series_id),
            "displayTitle": series_name,
            "remoteCoverRef": parent_poster,
            "members": [
                {"mediaId": item.record["id"], "position": item.season_number}
                for item in items
            ],
        }
    return records, group


def read_daily_export(
    path: Path,
    limit: int | None,
    include_adult: bool = False,
) -> Iterator[int]:
    selected = 0
    with gzip.open(path, "rt", encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            if not line.strip():
                continue
            try:
                entry = json.loads(line)
                series_id = common._positive_int(entry.get("id"), "series id")
            except (json.JSONDecodeError, AttributeError, TypeError, ValueError) as error:
                raise common.TmdbToolError(
                    f"Malformed TV daily export line {line_number}"
                ) from error
            if not include_adult and entry.get("adult") is True:
                continue
            yield series_id
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
    groups: list[dict[str, Any]] = []
    for series_id in sorted(details_by_id):
        details = details_by_id[series_id]
        common._validate_details_identity(details, series_id)
        series_records, group = map_series_details(details)
        records.extend(series_records)
        if group is not None:
            groups.append(group)

    output_dir.mkdir(parents=True, exist_ok=True)
    catalog_path = output_dir / "series_catalog.jsonl.gz"
    group_path = output_dir / "series_groups.jsonl.gz"
    common._write_deterministic_jsonl_gzip(catalog_path, records)
    common._write_deterministic_jsonl_gzip(group_path, groups)
    catalog_manifest = common._artifact_manifest(
        catalog_path,
        catalog_version,
        "itemCount",
        len(records),
        media_type=MEDIA_TYPE,
        provider=PROVIDER,
    )
    group_manifest = common._artifact_manifest(
        group_path,
        catalog_version,
        "groupCount",
        len(groups),
        media_type=MEDIA_TYPE,
        provider=PROVIDER,
    )
    common._write_json_atomic(output_dir / "series_catalog.manifest.json", catalog_manifest)
    common._write_json_atomic(output_dir / "series_groups.manifest.json", group_manifest)
    return {
        "records": records,
        "groups": groups,
        "catalogManifest": catalog_manifest,
        "groupManifest": group_manifest,
    }


def enrich_series(
    series_ids: Iterable[int],
    client: common.TmdbClient,
    workers: int = common.DEFAULT_WORKERS,
    progress_stream: Any = sys.stderr,
) -> tuple[dict[int, dict[str, Any]], list[int]]:
    return common.enrich_resources(series_ids, client.tv_details, workers, progress_stream)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ids", nargs="*", type=int, default=[])
    parser.add_argument("--ids-file", type=Path)
    parser.add_argument("--export-file", type=Path)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--all-from-export", action="store_true")
    parser.add_argument("--confirm-large-run", action="store_true")
    parser.add_argument("--include-adult", action="store_true")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--catalog-version", required=True)
    parser.add_argument(
        "--cache-dir",
        type=Path,
        default=Path(__file__).resolve().parents[1] / ".cache" / "tmdb",
    )
    parser.add_argument("--workers", type=int, default=common.DEFAULT_WORKERS)
    parser.add_argument(
        "--requests-per-second",
        type=float,
        default=common.DEFAULT_REQUESTS_PER_SECOND,
    )
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
        raise common.TmdbToolError("No TMDb TV series IDs were selected.")
    if args.dry_run:
        print(f"Selected {len(ids)} unique TMDb TV series IDs; no API requests or files written.")
        return 0

    token = os.environ.get("TMDB_READ_ACCESS_TOKEN", "")
    client = common.TmdbClient(token, args.cache_dir, args.requests_per_second)
    details, unavailable = enrich_series(ids, client, args.workers)
    generated = generate_artifacts(details, args.output_dir, args.catalog_version)
    print(
        f"Generated {len(generated['records'])} seasons and {len(generated['groups'])} series "
        f"groups. Unavailable IDs: {len(unavailable)}."
    )
    if unavailable:
        print("Unavailable TMDb TV IDs: " + ",".join(str(series_id) for series_id in unavailable))
    return 0


def _selected_ids(args: argparse.Namespace) -> list[int]:
    ids = list(args.ids)
    if args.ids_file:
        ids.extend(common._read_ids_file(args.ids_file))
    if args.export_file:
        ids.extend(read_daily_export(args.export_file, args.limit, args.include_adult))
    return sorted({common._positive_int(series_id, "series id") for series_id in ids})


def _series_credits(details: dict[str, Any]) -> list[dict[str, str]]:
    pairs: set[tuple[str, str]] = set()
    for role, key in (
        ("PRODUCTION_COMPANY", "production_companies"),
        ("NETWORK", "networks"),
        ("CREATOR", "created_by"),
    ):
        for entry in common._list_of_dicts(details.get(key)):
            name = common._nonblank(entry.get("name"))
            if name:
                pairs.add((role, name))
    return [
        {"role": role, "name": name}
        for role, name in sorted(pairs, key=lambda pair: (pair[0], pair[1].casefold(), pair[1]))
    ]


def _season_aliases(
    details: dict[str, Any],
    series_name: str,
    original_name: str | None,
    season_name: str,
) -> list[dict[str, str | None]]:
    excluded = {series_name}
    if original_name:
        excluded.add(original_name)
    by_title: dict[str, dict[str, str | None]] = {}
    alternative_titles = details.get("alternative_titles")
    if isinstance(alternative_titles, dict):
        for entry in common._list_of_dicts(alternative_titles.get("results")):
            alternative_name = common._nonblank(entry.get("title"))
            if not alternative_name or alternative_name in excluded:
                continue
            title = f"{alternative_name} — {season_name}"
            if title in by_title:
                continue
            by_title[title] = {
                "title": title,
                "language": None,
                "kind": common._nonblank(entry.get("iso_3166_1")),
            }
    return sorted(
        by_title.values(),
        key=lambda alias: (alias["title"].casefold(), alias["title"]),
    )[:MAX_ALIASES_PER_SEASON]


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except common.TmdbToolError as error:
        print(f"Error: {error}", file=sys.stderr)
        raise SystemExit(1)
