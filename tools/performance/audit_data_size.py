#!/usr/bin/env python3
"""Offline, reproducible RankOffline data-size and SQLite query audit.

The database created here is a desktop SQLite size model, not an Android Room runtime
benchmark. It uses the committed Anime catalog and alias asset, writes only below
tools/.cache/performance, and never contacts a provider or changes a catalog.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
import sqlite3
import statistics
import subprocess
import time
import zipfile
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable


SEARCH_TERMS = (
    "Attack on Titan",
    "Shingeki",
    "Frieren",
    "進撃の巨人",
    "One Piece",
    "a",
    "e",
    "rankoffline-nonexistent-title-8b91",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    default_root = Path(__file__).resolve().parents[2]
    parser.add_argument("--project-root", type=Path, default=default_root)
    parser.add_argument("--iterations", type=int, default=12)
    parser.add_argument(
        "--output",
        type=Path,
        default=default_root / "tools/.cache/performance/audit.json",
    )
    return parser.parse_args()


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def timed_query(
    connection: sqlite3.Connection,
    sql: str,
    parameters: tuple[Any, ...],
    iterations: int,
) -> dict[str, float | int]:
    connection.execute(sql, parameters).fetchall()
    samples: list[float] = []
    rows = 0
    for _ in range(iterations):
        start = time.perf_counter_ns()
        result = connection.execute(sql, parameters).fetchall()
        samples.append((time.perf_counter_ns() - start) / 1_000_000)
        rows = len(result)
    return {
        "rows": rows,
        "median_ms": round(statistics.median(samples), 3),
        "p95_ms": round(percentile(samples, 0.95), 3),
        "max_ms": round(max(samples), 3),
    }


def query_plan(
    connection: sqlite3.Connection,
    sql: str,
    parameters: tuple[Any, ...],
) -> list[str]:
    return [
        row[3]
        for row in connection.execute("EXPLAIN QUERY PLAN " + sql, parameters).fetchall()
    ]


def exact_iso_date(value: Any) -> str | None:
    if not isinstance(value, dict):
        return None
    year, month, day = value.get("year"), value.get("month"), value.get("day")
    if all(isinstance(part, int) for part in (year, month, day)):
        return f"{year:04d}-{month:02d}-{day:02d}"
    return None


def create_schema(connection: sqlite3.Connection) -> None:
    connection.executescript(
        """
        PRAGMA page_size=4096;
        PRAGMA journal_mode=OFF;
        PRAGMA synchronous=OFF;
        PRAGMA temp_store=MEMORY;
        PRAGMA foreign_keys=OFF;

        CREATE TABLE anime(
          id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, mal_id INTEGER,
          anilist_id INTEGER, popularity INTEGER, popularity_rank INTEGER,
          poster_url TEXT, remote_cover_url TEXT, local_cover_path TEXT,
          cover_resolution_state TEXT NOT NULL DEFAULT 'UNKNOWN', format TEXT,
          season_year INTEGER, media_status TEXT, source TEXT, episodes INTEGER,
          country_of_origin TEXT, average_score INTEGER, start_date TEXT
        );
        CREATE INDEX idx_anime_title ON anime(title);
        CREATE UNIQUE INDEX idx_anime_popularity_rank ON anime(popularity_rank);
        CREATE INDEX idx_anime_format ON anime(format);
        CREATE INDEX idx_anime_season_year ON anime(season_year);
        CREATE INDEX idx_anime_media_status ON anime(media_status);
        CREATE INDEX idx_anime_source ON anime(source);
        CREATE INDEX idx_anime_episodes ON anime(episodes);

        CREATE TABLE anime_alias(
          anime_id TEXT NOT NULL, title TEXT NOT NULL, language TEXT, kind TEXT,
          PRIMARY KEY(anime_id,title)
        );
        CREATE INDEX idx_anime_alias_anime ON anime_alias(anime_id);
        CREATE INDEX idx_anime_alias_title ON anime_alias(title);
        CREATE TABLE anime_genre(
          anime_id TEXT NOT NULL, genre TEXT NOT NULL, PRIMARY KEY(anime_id,genre)
        );
        CREATE INDEX idx_anime_genre_genre ON anime_genre(genre);
        CREATE TABLE anime_studio(
          anime_id TEXT NOT NULL, studio TEXT NOT NULL, PRIMARY KEY(anime_id,studio)
        );
        CREATE INDEX idx_anime_studio_studio ON anime_studio(studio);
        CREATE TABLE anime_group(
          id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, root_anime_id TEXT NOT NULL,
          display_title TEXT NOT NULL
        );
        CREATE INDEX idx_anime_group_type ON anime_group(type);
        CREATE INDEX idx_anime_group_root ON anime_group(root_anime_id);
        CREATE TABLE anime_group_member(
          group_id TEXT NOT NULL, anime_id TEXT NOT NULL, position INTEGER NOT NULL,
          PRIMARY KEY(group_id,anime_id)
        );
        CREATE INDEX idx_anime_group_member_anime ON anime_group_member(anime_id);
        CREATE TABLE rating(
          anime_id TEXT NOT NULL PRIMARY KEY, additive_score REAL NOT NULL,
          quality_score REAL, final_score REAL, notes TEXT NOT NULL,
          status TEXT NOT NULL, updated_at INTEGER NOT NULL, profile_id TEXT
        );
        CREATE TABLE rating_score(
          anime_id TEXT NOT NULL, category_id TEXT NOT NULL, score REAL,
          applicable INTEGER NOT NULL, PRIMARY KEY(anime_id,category_id)
        );

        CREATE TABLE media_item(
          id TEXT NOT NULL PRIMARY KEY, media_type TEXT NOT NULL, provider TEXT NOT NULL,
          provider_id TEXT NOT NULL, title TEXT NOT NULL, original_title TEXT,
          year INTEGER, start_date TEXT, format TEXT, status TEXT,
          original_language TEXT, demographic TEXT, part_number INTEGER,
          popularity REAL, popularity_rank INTEGER, remote_cover_ref TEXT,
          local_cover_path TEXT, cover_resolution_state TEXT NOT NULL DEFAULT 'UNKNOWN'
        );
        CREATE INDEX idx_media_item_media_type ON media_item(media_type);
        CREATE INDEX idx_media_item_type_title ON media_item(media_type,title);
        CREATE INDEX idx_media_item_type_popularity_rank ON media_item(media_type,popularity_rank);
        CREATE INDEX idx_media_item_type_year ON media_item(media_type,year);
        CREATE INDEX idx_media_item_type_format ON media_item(media_type,format);
        CREATE INDEX idx_media_item_type_demographic ON media_item(media_type,demographic);
        CREATE UNIQUE INDEX idx_media_item_provider_identity
          ON media_item(media_type,provider,provider_id);
        CREATE TABLE media_alias(
          media_id TEXT NOT NULL, title TEXT NOT NULL, language TEXT, kind TEXT,
          PRIMARY KEY(media_id,title)
        );
        CREATE INDEX idx_media_alias_title ON media_alias(title);
        CREATE TABLE media_genre(
          media_id TEXT NOT NULL, genre TEXT NOT NULL, PRIMARY KEY(media_id,genre)
        );
        CREATE INDEX idx_media_genre_genre ON media_genre(genre);
        CREATE TABLE media_credit(
          media_id TEXT NOT NULL, role TEXT NOT NULL, name TEXT NOT NULL,
          PRIMARY KEY(media_id,role,name)
        );
        CREATE INDEX idx_media_credit_role_name ON media_credit(role,name);
        CREATE TABLE media_group(
          id TEXT NOT NULL PRIMARY KEY, media_type TEXT NOT NULL, group_type TEXT NOT NULL,
          provider TEXT, provider_group_id TEXT, display_title TEXT NOT NULL,
          representative_media_id TEXT, remote_cover_ref TEXT, local_cover_path TEXT,
          cover_resolution_state TEXT NOT NULL DEFAULT 'UNKNOWN'
        );
        CREATE INDEX idx_media_group_type ON media_group(media_type,group_type);
        CREATE TABLE media_group_member(
          group_id TEXT NOT NULL, media_id TEXT NOT NULL, position INTEGER NOT NULL,
          PRIMARY KEY(group_id,media_id)
        );
        CREATE INDEX idx_media_group_member_media ON media_group_member(media_id);
        CREATE TABLE media_rating(
          media_id TEXT NOT NULL PRIMARY KEY, additive_score REAL NOT NULL,
          quality_score REAL, final_score REAL, notes TEXT NOT NULL,
          status TEXT NOT NULL, updated_at INTEGER NOT NULL, profile_id TEXT
        );
        CREATE TABLE media_rating_score(
          media_id TEXT NOT NULL, category_id TEXT NOT NULL, score REAL,
          applicable INTEGER NOT NULL, PRIMARY KEY(media_id,category_id)
        );
        """
    )


def batched(values: Iterable[tuple[Any, ...]], size: int = 1_000) -> Iterable[list[tuple[Any, ...]]]:
    batch: list[tuple[Any, ...]] = []
    for value in values:
        batch.append(value)
        if len(batch) == size:
            yield batch
            batch = []
    if batch:
        yield batch


def insert_many(
    connection: sqlite3.Connection,
    sql: str,
    values: Iterable[tuple[Any, ...]],
) -> int:
    count = 0
    for batch in batched(values):
        connection.executemany(sql, batch)
        count += len(batch)
    return count


def populate_database(
    connection: sqlite3.Connection,
    catalog: list[dict[str, Any]],
    alias_path: Path,
) -> dict[str, int]:
    counts: dict[str, int] = {}
    counts["anime"] = insert_many(
        connection,
        """INSERT INTO anime VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        (
            (
                item["id"], item["title"], item.get("malId"), item.get("anilistId"),
                item.get("popularity"), item.get("popularityRank"), item.get("posterUrl"),
                item.get("posterUrl"), None, "UNKNOWN", item.get("format"),
                item.get("seasonYear"), item.get("status"), item.get("source"),
                item.get("episodes"), item.get("countryOfOrigin"), item.get("averageScore"),
                exact_iso_date(item.get("startDate")),
            )
            for item in catalog
        ),
    )
    counts["anime_genre"] = insert_many(
        connection,
        "INSERT OR IGNORE INTO anime_genre VALUES(?,?)",
        ((item["id"], genre) for item in catalog for genre in item.get("genres", [])),
    )
    counts["anime_studio"] = insert_many(
        connection,
        "INSERT OR IGNORE INTO anime_studio VALUES(?,?)",
        ((item["id"], studio) for item in catalog for studio in item.get("studios", [])),
    )
    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for item in catalog:
        group_id = item.get("groupId")
        if group_id:
            groups[group_id].append(item)
    valid_groups = {
        group_id: members
        for group_id, members in groups.items()
        if len(members) >= 2
        and members[0].get("groupFormat")
        and members[0].get("groupDisplayTitle")
        and members[0].get("groupCoverAnimeId") in {member["id"] for member in members}
    }
    counts["anime_group"] = insert_many(
        connection,
        "INSERT INTO anime_group VALUES(?,?,?,?)",
        (
            (
                group_id,
                members[0]["groupFormat"],
                members[0]["groupCoverAnimeId"],
                members[0]["groupDisplayTitle"],
            )
            for group_id, members in valid_groups.items()
        ),
    )
    group_members: list[tuple[Any, ...]] = []
    for group_id, members in valid_groups.items():
        ordered = sorted(
            members,
            key=lambda member: (
                member.get("startDate") is None,
                json.dumps(member.get("startDate"), sort_keys=True),
                member.get("seasonYear") or 99999,
                member["title"].casefold(),
                member["id"],
            ),
        )
        group_members.extend((group_id, member["id"], position) for position, member in enumerate(ordered))
    counts["anime_group_member"] = insert_many(
        connection, "INSERT INTO anime_group_member VALUES(?,?,?)", group_members
    )
    alias_rows = (
        (record["animeId"], alias["title"], alias.get("language"), alias.get("kind"))
        for record in read_alias_records(alias_path)
        for alias in record["aliases"]
    )
    counts["anime_alias"] = insert_many(
        connection, "INSERT INTO anime_alias VALUES(?,?,?,?)", alias_rows
    )
    rated = catalog[::10]
    counts["rating"] = insert_many(
        connection,
        "INSERT INTO rating VALUES(?,?,?,?,?,?,?,?)",
        (
            (item["id"], 0.0, 7.5, float((index % 21) / 2), "", "COMPLETED", 0, "default")
            for index, item in enumerate(rated)
        ),
    )
    counts["rating_score"] = insert_many(
        connection,
        "INSERT INTO rating_score VALUES(?,?,?,?)",
        (
            row
            for index, item in enumerate(rated)
            for row in (
                (item["id"], "story", float((index % 21) / 2), 1),
                (item["id"], "characters", float(((index + 3) % 21) / 2), 1),
            )
        ),
    )
    connection.commit()
    return counts


def read_alias_records(path: Path) -> Iterable[dict[str, Any]]:
    with gzip.open(path, "rt", encoding="utf-8") as source:
        for line in source:
            yield json.loads(line)


def file_tree_total(root: Path) -> int:
    return sum(path.stat().st_size for path in root.rglob("*") if path.is_file())


def apk_metrics(path: Path) -> dict[str, Any]:
    with zipfile.ZipFile(path) as archive:
        entries = [
            {
                "name": info.filename,
                "uncompressed_bytes": info.file_size,
                "compressed_bytes": info.compress_size,
            }
            for info in archive.infolist()
        ]
    return {
        "bytes": path.stat().st_size,
        "entry_uncompressed_bytes": sum(entry["uncompressed_bytes"] for entry in entries),
        "entry_compressed_bytes": sum(entry["compressed_bytes"] for entry in entries),
        "top20": sorted(entries, key=lambda entry: entry["uncompressed_bytes"], reverse=True)[:20],
        "alias_entries": [entry for entry in entries if "anime_aliases" in entry["name"]],
    }


def tracked_files(project_root: Path) -> list[dict[str, Any]]:
    names = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=project_root,
        check=True,
        capture_output=True,
    ).stdout.split(b"\0")
    rows = []
    for raw_name in names:
        if not raw_name:
            continue
        name = raw_name.decode("utf-8")
        path = project_root / name
        if path.is_file():
            rows.append({"name": name, "bytes": path.stat().st_size})
    return sorted(rows, key=lambda row: row["bytes"], reverse=True)


def main() -> int:
    args = parse_args()
    if args.iterations < 1:
        raise SystemExit("--iterations must be positive")
    project_root = args.project_root.resolve()
    catalog_path = project_root / "app/src/main/assets/anime_catalog.json"
    alias_path = project_root / "app/src/main/assets/anime_aliases.jsonl.gz"
    manifest_path = project_root / "app/src/main/assets/anime_aliases.manifest.json"
    apk_path = project_root / "app/build/outputs/apk/debug/app-debug.apk"
    cache_dir = project_root / "tools/.cache/performance"
    cache_dir.mkdir(parents=True, exist_ok=True)
    database_path = cache_dir / "anime-audit.sqlite"
    database_path.unlink(missing_ok=True)

    catalog_bytes = catalog_path.stat().st_size
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    compressed_alias_bytes = alias_path.stat().st_size
    alias_sha256 = hashlib.sha256(alias_path.read_bytes()).hexdigest()
    if alias_sha256 != manifest["sha256"]:
        raise SystemExit("Bundled Anime alias SHA-256 does not match its manifest")
    uncompressed_alias_bytes = 0
    alias_records = 0
    alias_count = 0
    with gzip.open(alias_path, "rb") as source:
        for line in source:
            uncompressed_alias_bytes += len(line)
            record = json.loads(line)
            alias_records += 1
            alias_count += len(record["aliases"])

    connection = sqlite3.connect(database_path)
    create_schema(connection)
    row_counts = populate_database(connection, catalog, alias_path)
    connection.execute("ANALYZE")
    connection.commit()
    connection.execute("VACUUM")
    connection.execute("ANALYZE")
    connection.commit()

    app_search_sql = """
      SELECT a.id,a.title FROM anime a
      WHERE (?='' OR a.title LIKE '%' || ? || '%' COLLATE NOCASE OR EXISTS(
        SELECT 1 FROM anime_alias aa
        WHERE aa.anime_id=a.id AND aa.title LIKE '%' || ? || '%' COLLATE NOCASE))
      ORDER BY a.title COLLATE NOCASE LIMIT 200
    """
    empty_search_sql = """
      SELECT a.id,a.title FROM anime a
      ORDER BY a.title COLLATE NOCASE LIMIT 200
    """
    grouped_search_sql = """
      WITH visible AS (
        SELECT g.id,g.display_title AS title FROM anime_group g
        WHERE (g.display_title LIKE ? COLLATE NOCASE OR EXISTS(
          SELECT 1 FROM anime_group_member gm JOIN anime a ON a.id=gm.anime_id
          WHERE gm.group_id=g.id AND (a.title LIKE ? COLLATE NOCASE OR EXISTS(
            SELECT 1 FROM anime_alias aa
            WHERE aa.anime_id=a.id AND aa.title LIKE ? COLLATE NOCASE))))
        UNION ALL
        SELECT a.id,a.title FROM anime a
        WHERE NOT EXISTS(SELECT 1 FROM anime_group_member gm WHERE gm.anime_id=a.id)
          AND (a.title LIKE ? COLLATE NOCASE OR EXISTS(
            SELECT 1 FROM anime_alias aa
            WHERE aa.anime_id=a.id AND aa.title LIKE ? COLLATE NOCASE))
      )
      SELECT id,title FROM visible ORDER BY title COLLATE NOCASE LIMIT 200
    """
    group_aggregate_sql = """
      SELECT gm.group_id,COUNT(*),COUNT(r.final_score),AVG(r.final_score)
      FROM anime_group_member gm
      LEFT JOIN rating r ON r.anime_id=gm.anime_id
      GROUP BY gm.group_id
    """
    ranking_sql = """
      SELECT a.id,a.title,r.final_score,rs.score
      FROM rating r JOIN anime a ON a.id=r.anime_id
      LEFT JOIN rating_score rs ON rs.anime_id=r.anime_id
        AND rs.category_id='story' AND rs.applicable=1
      WHERE r.final_score IS NOT NULL
      ORDER BY r.final_score DESC,a.title COLLATE NOCASE LIMIT 100
    """

    search_timings = {}
    for term in SEARCH_TERMS:
        search_timings[term] = timed_query(
            connection, app_search_sql, (term, term, term), args.iterations
        )
    search_timings["<empty-app-shape>"] = timed_query(
        connection, app_search_sql, ("", "", ""), args.iterations
    )
    search_timings["<empty-direct-shape>"] = timed_query(
        connection, empty_search_sql, (), args.iterations
    )
    grouped_timings = {}
    for term in ("Attack on Titan", "Shingeki", "a", "rankoffline-nonexistent-title-8b91"):
        wildcard = f"%{term}%"
        grouped_timings[term] = timed_query(
            connection,
            grouped_search_sql,
            (wildcard, wildcard, wildcard, wildcard, wildcard),
            args.iterations,
        )

    plans = {
        "anime_alias_search": query_plan(
            connection, app_search_sql, ("Attack on Titan",) * 3
        ),
        "anime_empty_search": query_plan(connection, app_search_sql, ("", "", "")),
        "anime_grouped_search": query_plan(
            connection, grouped_search_sql, ("%Attack on Titan%",) * 5
        ),
        "anime_ranking": query_plan(connection, ranking_sql, ()),
        "anime_genre_filter": query_plan(
            connection,
            "SELECT a.id FROM anime a WHERE EXISTS(SELECT 1 FROM anime_genre ag "
            "WHERE ag.anime_id=a.id AND ag.genre=?) LIMIT 200",
            ("Action",),
        ),
        "anime_studio_filter": query_plan(
            connection,
            "SELECT a.id FROM anime a WHERE EXISTS(SELECT 1 FROM anime_studio ast "
            "WHERE ast.anime_id=a.id AND ast.studio=?) LIMIT 200",
            ("Madhouse",),
        ),
        "group_member_lookup": query_plan(
            connection,
            "SELECT a.id FROM anime_group_member gm JOIN anime a ON a.id=gm.anime_id "
            "WHERE gm.group_id=? ORDER BY gm.position",
            ("missing",),
        ),
        "generic_alias_search": query_plan(
            connection,
            "SELECT item.id FROM media_item item WHERE item.media_type=? AND "
            "(item.title LIKE ? COLLATE NOCASE OR item.original_title LIKE ? COLLATE NOCASE "
            "OR EXISTS(SELECT 1 FROM media_alias alias WHERE alias.media_id=item.id "
            "AND alias.title LIKE ? COLLATE NOCASE)) LIMIT 200",
            ("book", "%Hobbit%", "%Hobbit%", "%Hobbit%"),
        ),
        "generic_ranking": query_plan(
            connection,
            "SELECT item.id FROM media_item item JOIN media_rating rating "
            "ON rating.media_id=item.id WHERE item.media_type=? "
            "AND rating.final_score IS NOT NULL ORDER BY rating.final_score DESC LIMIT 100",
            ("book",),
        ),
        "generic_genre_filter": query_plan(
            connection,
            "SELECT item.id FROM media_item item WHERE item.media_type=? AND EXISTS("
            "SELECT 1 FROM media_genre genre WHERE genre.media_id=item.id AND genre.genre=?)",
            ("book", "Fantasy"),
        ),
        "generic_credit_filter": query_plan(
            connection,
            "SELECT item.id FROM media_item item WHERE item.media_type=? AND EXISTS("
            "SELECT 1 FROM media_credit credit WHERE credit.media_id=item.id "
            "AND credit.role=? AND credit.name=?)",
            ("book", "AUTHOR", "Ursula K. Le Guin"),
        ),
    }

    dbstat = {
        name: size
        for name, size in connection.execute(
            "SELECT name,SUM(pgsize) FROM dbstat GROUP BY name ORDER BY SUM(pgsize) DESC"
        )
    }
    page_size = connection.execute("PRAGMA page_size").fetchone()[0]
    page_count = connection.execute("PRAGMA page_count").fetchone()[0]
    ranking_timing = timed_query(connection, ranking_sql, (), args.iterations)
    group_aggregate_timing = timed_query(connection, group_aggregate_sql, (), args.iterations)
    sqlite_version = connection.execute("SELECT sqlite_version()").fetchone()[0]
    connection.close()

    result = {
        "methodology": {
            "kind": "desktop SQLite synthetic size/query model",
            "sqlite_version": sqlite_version,
            "iterations_after_warmup": args.iterations,
            "database_fixture": "real committed Anime catalog and aliases; deterministic 10% modeled ratings",
        },
        "apk": apk_metrics(apk_path),
        "assets": {
            "anime_catalog_bytes": catalog_bytes,
            "anime_catalog_items": len(catalog),
            "anime_alias_compressed_bytes": compressed_alias_bytes,
            "anime_alias_uncompressed_bytes": uncompressed_alias_bytes,
            "anime_alias_sha256": alias_sha256,
            "anime_alias_records": alias_records,
            "anime_alias_count": alias_count,
            "assets_total_bytes": file_tree_total(project_root / "app/src/main/assets"),
            "legal_assets_total_bytes": file_tree_total(project_root / "app/src/main/assets/legal"),
            "room_schema_exports_total_bytes": file_tree_total(project_root / "app/schemas"),
        },
        "synthetic_database": {
            "path": str(database_path.relative_to(project_root)),
            "bytes": database_path.stat().st_size,
            "page_size": page_size,
            "page_count": page_count,
            "row_counts": row_counts,
            "dbstat_bytes": dbstat,
        },
        "benchmarks": {
            "search": search_timings,
            "grouped_search": grouped_timings,
            "ranking": ranking_timing,
            "group_aggregate": group_aggregate_timing,
        },
        "query_plans": plans,
        "tracked_files_top20": tracked_files(project_root)[:20],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
