#!/usr/bin/env python3
"""Generate a deterministic RankOffline Book catalog from local Open Library dumps."""

from __future__ import annotations

import argparse
import datetime as dt
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import sys
from typing import Any, Callable, Iterable, Iterator, TextIO


FORMAT_VERSION = 1
MEDIA_TYPE = "book"
PROVIDER = "openlibrary"
WORK_TYPE = "/type/work"
AUTHOR_TYPE = "/type/author"
MAX_SUBJECTS = 50
INDEX_BATCH_SIZE = 1_000
WORK_ID_PATTERN = re.compile(r"^OL[0-9]+W$")
AUTHOR_ID_PATTERN = re.compile(r"^OL[0-9]+A$")
EDITION_ID_PATTERN = re.compile(r"^OL[0-9]+M$")
ISBN_13_PATTERN = re.compile(r"^97[89][0-9]{10}$")
ISBN_10_PATTERN = re.compile(r"^[0-9]{9}[0-9X]$")
OPEN_LIBRARY_COVER_REFERENCE_PREFIX = "openlibrary:v1"
YEAR_PATTERN = re.compile(r"(?<![0-9])([12][0-9]{3})(?![0-9])")
ISO_DATE_PATTERN = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")


class OpenLibraryToolError(RuntimeError):
    pass


class AuthorIndex:
    """Read-only lookup over the disk-backed Open Library author index."""

    def __init__(self, path: Path) -> None:
        self.path = path
        try:
            self._connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
            self._connection.execute("SELECT author_key, name FROM author LIMIT 1").fetchone()
        except sqlite3.Error as error:
            raise OpenLibraryToolError(f"Invalid Open Library author index: {path}") from error

    def name(self, author_key: str) -> str | None:
        author_id = normalize_author_id(author_key)
        row = self._connection.execute(
            "SELECT name FROM author WHERE author_key = ?", (author_id,)
        ).fetchone()
        return row[0] if row else None

    def close(self) -> None:
        self._connection.close()

    def __enter__(self) -> AuthorIndex:
        return self

    def __exit__(self, *_: object) -> None:
        self.close()


def normalize_work_id(value: Any) -> str:
    candidate = _normalize_open_library_key(value, "/works/")
    if not WORK_ID_PATTERN.fullmatch(candidate):
        raise OpenLibraryToolError(f"Invalid Open Library Work key: {value}")
    return candidate


def normalize_author_id(value: Any) -> str:
    candidate = _normalize_open_library_key(value, "/authors/")
    if not AUTHOR_ID_PATTERN.fullmatch(candidate):
        raise OpenLibraryToolError(f"Invalid Open Library Author key: {value}")
    return candidate


def map_work(
    work: dict[str, Any],
    dump_key: str | None = None,
    author_name: Callable[[str], str | None] | None = None,
    popularity: int | None = None,
    popularity_rank: int | None = None,
    cover_metadata: dict[str, str] | None = None,
) -> dict[str, Any]:
    work_id = normalize_work_id(dump_key or work.get("key"))
    record_key = work.get("key")
    if record_key is not None and normalize_work_id(record_key) != work_id:
        raise OpenLibraryToolError(f"Work key mismatch for {work_id}")
    if _type_key(work.get("type")) != WORK_TYPE:
        raise OpenLibraryToolError(f"Open Library record {work_id} is not a Work")
    title = _nonblank(work.get("title"))
    if title is None:
        raise OpenLibraryToolError(f"Open Library Work {work_id} has no title")
    year, start_date = parse_first_publish_date(work.get("first_publish_date"))
    subtitle = _nonblank(work.get("subtitle"))
    aliases = []
    if subtitle and subtitle.casefold() not in title.casefold():
        aliases.append({"title": f"{title}: {subtitle}", "language": None, "kind": "SUBTITLE"})
    return {
        "id": f"book:openlibrary:{work_id}",
        "mediaType": MEDIA_TYPE,
        "provider": PROVIDER,
        "providerId": work_id,
        "title": title,
        "originalTitle": None,
        "year": year,
        "startDate": start_date,
        "format": "BOOK",
        "status": None,
        "originalLanguage": None,
        "demographic": None,
        "partNumber": None,
        "popularity": popularity,
        "popularityRank": popularity_rank,
        "remoteCoverRef": build_cover_reference(work_id, work.get("covers"), cover_metadata),
        "genres": normalize_subjects(work.get("subjects")),
        "credits": resolve_author_credits(work.get("authors"), author_name),
        "aliases": aliases,
    }


def parse_first_publish_date(value: Any) -> tuple[int | None, str | None]:
    text = _nonblank(value)
    if text is None:
        return None, None
    if ISO_DATE_PATTERN.fullmatch(text):
        try:
            parsed = dt.date.fromisoformat(text)
            return parsed.year, text
        except ValueError:
            pass
    match = YEAR_PATTERN.search(text)
    if match is None:
        return None, None
    year = int(match.group(1))
    return (year, None) if 1000 <= year <= 2999 else (None, None)


def select_cover_id(value: Any) -> str | None:
    if not isinstance(value, list):
        return None
    for candidate in value:
        if isinstance(candidate, int) and not isinstance(candidate, bool) and candidate > 0:
            return str(candidate)
    return None


def build_cover_reference(
    work_id: str,
    covers: Any,
    metadata: dict[str, str] | None = None,
) -> str:
    """Encode pre-resolved cover candidates in runtime fallback order."""
    metadata = metadata or {}
    candidates = [
        ("id", select_cover_id(covers) or _positive_integer_string(metadata.get("coverId"))),
        ("isbn13", _normalize_isbn(metadata.get("isbn13"), ISBN_13_PATTERN)),
        ("isbn10", _normalize_isbn(metadata.get("isbn10"), ISBN_10_PATTERN)),
        ("edition", _matching_text(metadata.get("editionOlid"), EDITION_ID_PATTERN)),
        ("work", normalize_work_id(work_id)),
    ]
    encoded = [f"{key}={value}" for key, value in candidates if value is not None]
    return "|".join([OPEN_LIBRARY_COVER_REFERENCE_PREFIX, *encoded])


def _positive_integer_string(value: Any) -> str | None:
    text = str(value).strip() if value is not None else ""
    return text if text.isdigit() and int(text) > 0 else None


def _normalize_isbn(value: Any, pattern: re.Pattern[str]) -> str | None:
    if not isinstance(value, str):
        return None
    normalized = re.sub(r"[-\s]", "", value).upper()
    return normalized if pattern.fullmatch(normalized) else None


def _matching_text(value: Any, pattern: re.Pattern[str]) -> str | None:
    normalized = value.strip().upper() if isinstance(value, str) else ""
    return normalized if pattern.fullmatch(normalized) else None


def normalize_subjects(value: Any, limit: int = MAX_SUBJECTS) -> list[str]:
    if not isinstance(value, list) or limit <= 0:
        return []
    result: list[str] = []
    seen: set[str] = set()
    for candidate in value:
        subject = _nonblank(candidate)
        if subject is None or subject.casefold() in seen:
            continue
        seen.add(subject.casefold())
        result.append(subject)
        if len(result) == limit:
            break
    return result


def resolve_author_credits(
    value: Any,
    author_name: Callable[[str], str | None] | None,
) -> list[dict[str, str]]:
    if not isinstance(value, list) or author_name is None:
        return []
    names: dict[str, str] = {}
    for relationship in value:
        if not isinstance(relationship, dict):
            continue
        author = relationship.get("author")
        key = author.get("key") if isinstance(author, dict) else relationship.get("key")
        try:
            name = author_name(normalize_author_id(key))
        except OpenLibraryToolError:
            continue
        normalized = _nonblank(name)
        if normalized is not None:
            names.setdefault(normalized.casefold(), normalized)
    return [
        {"role": "AUTHOR", "name": name}
        for name in sorted(names.values(), key=lambda item: (item.casefold(), item))
    ]


def build_author_index(
    authors_dump: Path,
    index_path: Path,
    skip_malformed: bool = False,
    batch_size: int = INDEX_BATCH_SIZE,
) -> int:
    if batch_size <= 0:
        raise ValueError("author index batch size must be positive")
    index_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = index_path.with_name(index_path.name + ".tmp")
    temporary.unlink(missing_ok=True)
    count = 0
    connection: sqlite3.Connection | None = None
    try:
        connection = sqlite3.connect(temporary)
        connection.execute(
            "CREATE TABLE author (author_key TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL)"
        )
        batch: list[tuple[str, str]] = []
        for dump_key, author, _ in iter_dump_records(
            authors_dump, AUTHOR_TYPE, skip_malformed
        ):
            try:
                author_id = normalize_author_id(dump_key)
                record_key = author.get("key")
                if record_key is not None and normalize_author_id(record_key) != author_id:
                    raise OpenLibraryToolError(f"Author key mismatch for {author_id}")
                name = _nonblank(author.get("name"))
                if name is None:
                    raise OpenLibraryToolError(f"Open Library Author {author_id} has no name")
            except OpenLibraryToolError:
                if skip_malformed:
                    continue
                raise
            batch.append((author_id, name))
            if len(batch) >= batch_size:
                count += _insert_author_batch(connection, batch)
                batch.clear()
        if batch:
            count += _insert_author_batch(connection, batch)
        connection.commit()
        connection.close()
        connection = None
        os.replace(temporary, index_path)
        return count
    finally:
        if connection is not None:
            connection.close()
        temporary.unlink(missing_ok=True)


def generate_catalog(
    works_dump: Path,
    output_dir: Path,
    catalog_version: str,
    limit: int | None = None,
    selected_work_ids: set[str] | None = None,
    popularity_by_work: dict[str, tuple[int, int]] | None = None,
    cover_metadata_by_work: dict[str, dict[str, str]] | None = None,
    author_index_path: Path | None = None,
    all_from_dump: bool = False,
    skip_malformed: bool = False,
    progress_stream: TextIO = sys.stderr,
) -> dict[str, Any]:
    if not catalog_version.strip():
        raise ValueError("catalog version must not be blank")
    if limit is not None and limit <= 0:
        raise ValueError("limit must be positive")
    selected = (
        {normalize_work_id(value) for value in selected_work_ids}
        if selected_work_ids is not None
        else None
    )
    if selected is not None and not selected:
        raise OpenLibraryToolError("The Work key selection is empty.")
    if limit is None and selected is None and not all_from_dump:
        raise OpenLibraryToolError(
            "Bounded generation requires --limit or --work-keys-file; "
            "full processing requires --all-from-dump --confirm-large-run."
        )

    output_dir.mkdir(parents=True, exist_ok=True)
    catalog_path = output_dir / "book_catalog.jsonl.gz"
    manifest_path = output_dir / "book_catalog.manifest.json"
    catalog_temporary = output_dir / "book_catalog.jsonl.gz.tmp"
    manifest_temporary = output_dir / "book_catalog.manifest.json.tmp"
    seen_path = output_dir / ".book_catalog_seen.sqlite.tmp"
    for path in (catalog_temporary, manifest_temporary, seen_path):
        path.unlink(missing_ok=True)

    author_index: AuthorIndex | None = None
    seen_connection: sqlite3.Connection | None = None
    item_count = 0
    skipped_count = 0
    found_ids: set[str] = set()
    try:
        if author_index_path is not None:
            author_index = AuthorIndex(author_index_path)
        seen_connection = sqlite3.connect(seen_path)
        seen_connection.execute("CREATE TABLE seen (media_id TEXT PRIMARY KEY NOT NULL)")
        with catalog_temporary.open("wb") as raw:
            with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
                for dump_key, work, line_number in iter_dump_records(
                    works_dump, WORK_TYPE, skip_malformed
                ):
                    try:
                        work_id = normalize_work_id(dump_key)
                        if selected is not None and work_id not in selected:
                            continue
                        popularity, popularity_rank = (popularity_by_work or {}).get(
                            work_id, (None, None)
                        )
                        record = map_work(
                            work=work,
                            dump_key=dump_key,
                            author_name=author_index.name if author_index is not None else None,
                            popularity=popularity,
                            popularity_rank=popularity_rank,
                            cover_metadata=(cover_metadata_by_work or {}).get(work_id),
                        )
                    except OpenLibraryToolError as error:
                        if skip_malformed:
                            skipped_count += 1
                            continue
                        raise OpenLibraryToolError(
                            f"Invalid Work at {works_dump}:{line_number}: {error}"
                        ) from error
                    try:
                        seen_connection.execute(
                            "INSERT INTO seen(media_id) VALUES (?)", (record["id"],)
                        )
                    except sqlite3.IntegrityError as error:
                        raise OpenLibraryToolError(
                            f"Duplicate Open Library Work identity at line {line_number}: {work_id}"
                        ) from error
                    compressed.write(
                        json.dumps(
                            record,
                            ensure_ascii=False,
                            separators=(",", ":"),
                            allow_nan=False,
                        ).encode("utf-8")
                        + b"\n"
                    )
                    if selected is not None:
                        found_ids.add(work_id)
                    item_count += 1
                    if item_count % 10_000 == 0:
                        print(f"Emitted {item_count} Open Library Works", file=progress_stream)
                    if limit is not None and item_count >= limit:
                        break
                    if selected is not None and limit is None and found_ids == selected:
                        break

        if selected is not None and limit is None:
            missing = sorted(selected - found_ids)
            if missing:
                preview = ", ".join(missing[:10])
                raise OpenLibraryToolError(f"Selected Works were not found in the dump: {preview}")

        manifest = {
            "formatVersion": FORMAT_VERSION,
            "mediaType": MEDIA_TYPE,
            "provider": PROVIDER,
            "catalogVersion": catalog_version,
            "sha256": _sha256_file(catalog_temporary),
            "itemCount": item_count,
        }
        manifest_temporary.write_text(
            json.dumps(manifest, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
            + "\n",
            encoding="utf-8",
        )
        os.replace(catalog_temporary, catalog_path)
        os.replace(manifest_temporary, manifest_path)
        return {
            "catalogManifest": manifest,
            "itemCount": item_count,
            "skippedCount": skipped_count,
            "authorEnriched": author_index is not None,
        }
    finally:
        if seen_connection is not None:
            seen_connection.close()
        if author_index is not None:
            author_index.close()
        for path in (catalog_temporary, manifest_temporary, seen_path):
            path.unlink(missing_ok=True)


def iter_dump_records(
    path: Path,
    expected_type: str,
    skip_malformed: bool = False,
) -> Iterator[tuple[str, dict[str, Any], int]]:
    with gzip.open(path, "rt", encoding="utf-8") as source:
        for line_number, raw_line in enumerate(source, start=1):
            line = raw_line.rstrip("\r\n")
            fields = line.split("\t", 4)
            if len(fields) != 5:
                if fields and fields[0] != expected_type:
                    continue
                if skip_malformed:
                    continue
                raise OpenLibraryToolError(f"Malformed dump row at {path}:{line_number}")
            dump_type, dump_key, _, _, payload = fields
            if dump_type != expected_type:
                continue
            try:
                value = json.loads(payload)
                if not isinstance(value, dict) or _type_key(value.get("type")) != expected_type:
                    raise ValueError("record type mismatch")
            except (json.JSONDecodeError, ValueError) as error:
                if skip_malformed:
                    continue
                raise OpenLibraryToolError(
                    f"Malformed {expected_type} JSON at {path}:{line_number}"
                ) from error
            yield dump_key, value, line_number


def read_work_keys(path: Path) -> set[str]:
    result: set[str] = set()
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        content = line.split("#", 1)[0].strip()
        if not content:
            continue
        try:
            result.add(normalize_work_id(content))
        except OpenLibraryToolError as error:
            raise OpenLibraryToolError(f"Invalid Work key at {path}:{line_number}") from error
    return result


def read_popularity_selection(path: Path) -> dict[str, tuple[int, int]]:
    result: dict[str, tuple[int, int]] = {}
    ranks: set[int] = set()
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
            work_id = normalize_work_id(value["workId"])
            count = int(value["readinglogCount"])
            rank = int(value["popularityRank"])
            if count < 0 or rank <= 0 or work_id in result or rank in ranks:
                raise ValueError("invalid or duplicate popularity entry")
        except (json.JSONDecodeError, KeyError, TypeError, ValueError, OpenLibraryToolError) as error:
            raise OpenLibraryToolError(
                f"Invalid popularity selection at {path}:{line_number}"
            ) from error
        result[work_id] = (count, rank)
        ranks.add(rank)
    return result


def read_cover_metadata_selection(path: Path) -> dict[str, dict[str, str]]:
    """Read identifiers already returned by the ranked Search snapshot."""
    result: dict[str, dict[str, str]] = {}
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
            work_id = normalize_work_id(value["workId"])
            if work_id in result:
                raise ValueError("duplicate Work")
        except (json.JSONDecodeError, KeyError, TypeError, ValueError, OpenLibraryToolError) as error:
            raise OpenLibraryToolError(
                f"Invalid cover metadata selection at {path}:{line_number}"
            ) from error
        metadata: dict[str, str] = {}
        for field in ("coverId", "isbn13", "isbn10", "editionOlid"):
            candidate = value.get(field)
            if isinstance(candidate, (str, int)) and not isinstance(candidate, bool):
                metadata[field] = str(candidate)
        result[work_id] = metadata
    return result


def select_available_popularity(
    works_dump: Path,
    popularity: dict[str, tuple[int, int]],
    target_count: int,
    skip_malformed: bool = False,
    progress_stream: TextIO = sys.stderr,
) -> tuple[dict[str, tuple[int, int]], dict[str, Any]]:
    """Choose the highest-ranked resolvable Works and make snapshot ranks contiguous."""
    if target_count <= 0:
        raise ValueError("target count must be positive")
    if len(popularity) < target_count:
        raise OpenLibraryToolError(
            f"Popularity selection has {len(popularity)} candidates for a {target_count}-Work target"
        )

    candidates = set(popularity)
    available: set[str] = set()
    for dump_key, work, line_number in iter_dump_records(
        works_dump, WORK_TYPE, skip_malformed
    ):
        try:
            work_id = normalize_work_id(dump_key)
            if work_id not in candidates:
                continue
            # Validate required Work-level fields before accepting a candidate;
            # author resolution remains optional and is performed during output.
            map_work(work, dump_key)
            available.add(work_id)
        except OpenLibraryToolError as error:
            if skip_malformed:
                continue
            raise OpenLibraryToolError(
                f"Invalid selected Work at {works_dump}:{line_number}: {error}"
            ) from error
        if len(available) == len(candidates):
            break

    ordered_candidates = sorted(
        popularity.items(), key=lambda item: (item[1][1], item[0])
    )
    resolved = [(work_id, values) for work_id, values in ordered_candidates if work_id in available]
    if len(resolved) < target_count:
        raise OpenLibraryToolError(
            f"Only {len(resolved)} of {len(popularity)} ranked Work candidates are resolvable; "
            f"cannot produce the required {target_count} Books"
        )

    original_top = {work_id for work_id, _ in ordered_candidates[:target_count]}
    selected = resolved[:target_count]
    result = {
        work_id: (values[0], rank)
        for rank, (work_id, values) in enumerate(selected, start=1)
    }
    missing_top = sorted(original_top - available)
    replacements = [work_id for work_id, _ in selected if work_id not in original_top]
    stats = {
        "candidateCount": len(popularity),
        "availableCandidateCount": len(available),
        "missingTopCount": len(missing_top),
        "missingTopWorkIds": missing_top,
        "replacementCount": len(replacements),
        "replacementWorkIds": replacements,
    }
    print(
        f"Resolved {len(available)} / {len(popularity)} ranked candidates; "
        f"replaced {len(replacements)} unavailable Top-{target_count} Works.",
        file=progress_stream,
    )
    return result, stats


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--works-dump", type=Path, required=True)
    parser.add_argument("--authors-dump", type=Path)
    parser.add_argument(
        "--author-index",
        type=Path,
        default=Path(__file__).resolve().parents[1]
        / ".cache"
        / "openlibrary"
        / "authors.sqlite",
    )
    parser.add_argument("--work-keys-file", type=Path)
    parser.add_argument("--popular-works-file", type=Path)
    parser.add_argument("--target-count", type=int)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--all-from-dump", action="store_true")
    parser.add_argument("--confirm-large-run", action="store_true")
    parser.add_argument("--skip-malformed", action="store_true")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--catalog-version", required=True)
    args = parser.parse_args(argv)
    if args.limit is not None and args.limit <= 0:
        parser.error("--limit must be positive")
    if args.target_count is not None and args.target_count <= 0:
        parser.error("--target-count must be positive")
    if args.target_count is not None and args.popular_works_file is None:
        parser.error("--target-count requires --popular-works-file")
    if args.all_from_dump != args.confirm_large_run:
        parser.error("full dump processing requires both --all-from-dump and --confirm-large-run")
    if args.work_keys_file and args.popular_works_file:
        parser.error("--work-keys-file and --popular-works-file are mutually exclusive")
    if args.all_from_dump and (
        args.limit is not None or args.work_keys_file is not None or args.popular_works_file is not None
    ):
        parser.error("full dump flags cannot be combined with bounded selection")
    if not args.all_from_dump and args.limit is None and args.work_keys_file is None and args.popular_works_file is None:
        parser.error("provide --limit, --work-keys-file or --popular-works-file for bounded generation")
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    popularity = read_popularity_selection(args.popular_works_file) if args.popular_works_file else None
    cover_metadata = (
        read_cover_metadata_selection(args.popular_works_file)
        if args.popular_works_file
        else None
    )
    if popularity is not None and args.target_count is not None:
        popularity, _ = select_available_popularity(
            args.works_dump,
            popularity,
            args.target_count,
            args.skip_malformed,
        )
    selected = set(popularity) if popularity is not None else (
        read_work_keys(args.work_keys_file) if args.work_keys_file else None
    )
    index_path: Path | None = args.author_index
    if args.authors_dump is not None and not index_path.exists():
        count = build_author_index(args.authors_dump, index_path, args.skip_malformed)
        print(f"Indexed {count} Open Library authors at {index_path}.", file=sys.stderr)
    elif not index_path.exists():
        index_path = None
        print(
            "Warning: no Open Library Authors dump/index supplied; AUTHOR credits will be omitted.",
            file=sys.stderr,
        )
    result = generate_catalog(
        works_dump=args.works_dump,
        output_dir=args.output_dir,
        catalog_version=args.catalog_version,
        limit=args.limit,
        selected_work_ids=selected,
        popularity_by_work=popularity,
        cover_metadata_by_work=cover_metadata,
        author_index_path=index_path,
        all_from_dump=args.all_from_dump,
        skip_malformed=args.skip_malformed,
    )
    print(
        f"Generated {result['itemCount']} Book Work records; "
        f"skipped malformed records: {result['skippedCount']}."
    )
    return 0


def _normalize_open_library_key(value: Any, prefix: str) -> str:
    if not isinstance(value, str):
        return ""
    candidate = value.strip()
    if candidate.startswith(prefix):
        candidate = candidate[len(prefix):]
    return candidate


def _type_key(value: Any) -> str | None:
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        return value.get("key") if isinstance(value.get("key"), str) else None
    return None


def _nonblank(value: Any) -> str | None:
    return value.strip() if isinstance(value, str) and value.strip() else None


def _insert_author_batch(connection: sqlite3.Connection, batch: Iterable[tuple[str, str]]) -> int:
    rows = list(batch)
    connection.executemany(
        "INSERT INTO author(author_key, name) VALUES (?, ?) "
        "ON CONFLICT(author_key) DO UPDATE SET name = excluded.name",
        rows,
    )
    connection.commit()
    return len(rows)


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OpenLibraryToolError, OSError, sqlite3.Error) as error:
        print(f"Error: {error}", file=sys.stderr)
        raise SystemExit(1)
