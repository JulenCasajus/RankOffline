#!/usr/bin/env python3
"""Host SQLite probe of real generated group names; writes ignored build artifacts."""

from __future__ import annotations

import gzip
import json
from pathlib import Path
import platform
import sqlite3
import statistics
import time
import zipfile


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build" / "group-alias-audit"


def records(archive: zipfile.ZipFile, path: str):
    with gzip.GzipFile(fileobj=archive.open(path)) as source:
        for line in source:
            yield json.loads(line)


def load_data(db: sqlite3.Connection) -> None:
    db.executescript("""
        CREATE TABLE media_group(pack_id INTEGER, id TEXT, display_title TEXT,
            PRIMARY KEY(pack_id,id));
        CREATE TABLE media_group_alias(pack_id INTEGER, group_id TEXT, title TEXT,
            PRIMARY KEY(pack_id,group_id,title));
        CREATE TABLE media_group_member(pack_id INTEGER, group_id TEXT, media_id TEXT);
        CREATE INDEX idx_member_group ON media_group_member(pack_id,group_id);
        CREATE TABLE media_item(pack_id INTEGER, id TEXT, title TEXT,
            PRIMARY KEY(pack_id,id));
        CREATE TABLE media_alias(pack_id INTEGER, media_id TEXT, title TEXT);
        CREATE INDEX idx_alias_item ON media_alias(pack_id,media_id);
    """)
    for pack in range(1, 6):
        with zipfile.ZipFile(ROOT / f"build/pack-output/pack-{pack}.pack") as archive:
            groups = list(records(archive, "groups/groups.jsonl.gz"))
            member_ids = {m["mediaId"] for g in groups for m in g["members"]}
            db.executemany("INSERT INTO media_group VALUES(?,?,?)",
                           ((pack, g["id"], g.get("displayTitle") or g["id"]) for g in groups))
            db.executemany("INSERT INTO media_group_alias VALUES(?,?,?)",
                           ((pack, g["id"], a["title"]) for g in groups for a in g.get("aliases", [])))
            db.executemany("INSERT INTO media_group_member VALUES(?,?,?)",
                           ((pack, g["id"], m["mediaId"]) for g in groups for m in g["members"]))
            for item in records(archive, "catalog/items.jsonl.gz"):
                if item["id"] not in member_ids:
                    continue
                db.execute("INSERT INTO media_item VALUES(?,?,?)", (pack, item["id"], item["title"]))
                db.executemany("INSERT INTO media_alias VALUES(?,?,?)",
                               ((pack, item["id"], a["title"]) for a in item.get("aliases", [])))
    db.commit()


def benchmark(db: sqlite3.Connection) -> dict:
    own = """SELECT g.id FROM media_group g WHERE g.pack_id=? AND
        (g.display_title LIKE ? OR EXISTS (SELECT 1 FROM media_group_alias a
        WHERE a.pack_id=g.pack_id AND a.group_id=g.id AND a.title LIKE ?)) LIMIT 200"""
    title = "SELECT g.id FROM media_group g WHERE g.pack_id=? AND g.display_title LIKE ? LIMIT 200"
    members = """SELECT g.id FROM media_group g WHERE g.pack_id=? AND
        (g.display_title LIKE ? OR EXISTS (SELECT 1 FROM media_group_member m
        JOIN media_item i ON i.pack_id=m.pack_id AND i.id=m.media_id
        LEFT JOIN media_alias a ON a.pack_id=i.pack_id AND a.media_id=i.id
        WHERE m.pack_id=g.pack_id AND m.group_id=g.id
          AND (i.title LIKE ? OR a.title LIKE ?))) LIMIT 200"""
    cases = [("canonical", 4, "%Law%"), ("group_alias", 4, "%SVU%"),
             ("short", 4, "%La%"), ("missing", 4, "%zxqv_no_match%"),
             ("manga_alias", 2, "%Komi-san Has%")]
    output = {}
    for name, pack, pattern in cases:
        output[name] = {}
        for label, sql in (("title_only", title), ("group_alias", own), ("member_scan", members)):
            args = (pack, pattern) if label == "title_only" else (
                (pack, pattern, pattern) if label == "group_alias" else (pack, pattern, pattern, pattern)
            )
            samples = []
            for _ in range(31):
                start = time.perf_counter_ns()
                rows = db.execute(sql, args).fetchall()
                samples.append((time.perf_counter_ns() - start) / 1_000_000)
            output[name][label] = {"medianMs": round(statistics.median(samples), 3),
                                   "minMs": round(min(samples), 3),
                                   "maxMs": round(max(samples), 3), "results": len(rows)}
            if name == "group_alias":
                output[name][label]["plan"] = [row[3] for row in db.execute("EXPLAIN QUERY PLAN " + sql, args)]
    return output


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    temporary = OUT / "benchmark-temp.sqlite"
    temporary.unlink(missing_ok=True)
    with sqlite3.connect(temporary) as db:
        load_data(db)
        result = benchmark(db)
    temporary.unlink(missing_ok=True)
    (OUT / "benchmark-sqlite.json").write_text(
        json.dumps({"environment": "host Python sqlite3; name-query projection only; 31 warm runs",
                    "platform": platform.platform(), "sqliteVersion": sqlite3.sqlite_version,
                    "results": result}, indent=2, ensure_ascii=False) + "\n")
    for name, values in result.items():
        print(name, {kind: value["medianMs"] for kind, value in values.items()})


if __name__ == "__main__":
    main()
