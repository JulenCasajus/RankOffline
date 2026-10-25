#!/usr/bin/env python3
"""Generate RankOffline Anime title aliases from a pinned offline database release."""

from __future__ import annotations

import argparse
import contextlib
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import tempfile
from dataclasses import dataclass
from typing import Iterable, Iterator, TextIO
from urllib.parse import urlparse


FORMAT_VERSION = 1
SOURCE = "cedya77/anime-offline-database"
SOURCE_ORIGINAL_PROJECT = "manami-project/anime-offline-database"
SOURCE_RELEASE = "2026-42"
SOURCE_ASSET = "anime-offline-database.jsonl.zst"
SOURCE_ASSET_SHA256 = "5d6fc7ed463c616f4efe6d35344d019f30fe0614fe183c4668a9dd4fe14d6728"
LICENSE = "ODbL-1.0"
ALIAS_KIND = "anime-offline-database"
MAX_ALIASES = 50


@dataclass(frozen=True)
class SourceEntry:
    ordinal: int
    title: str
    synonyms: tuple[str, ...]


@dataclass(frozen=True)
class SourceIndex:
    mal: dict[int, SourceEntry]
    anilist: dict[int, SourceEntry]
    ambiguous_mal: frozenset[int]
    ambiguous_anilist: frozenset[int]


@dataclass(frozen=True)
class GenerationAudit:
    rankofflineAnimeCount: int
    matchedAnimeCount: int
    unmatchedAnimeCount: int
    conflictCount: int
    animeWithAliases: int
    totalAliasCount: int
    medianAliasesPerMatchedAnime: float
    p95AliasesPerMatchedAnime: int
    maxAliasesPerMatchedAnime: int
    truncatedByCapCount: int


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


@contextlib.contextmanager
def open_source_text(path: Path) -> Iterator[TextIO]:
    if path.suffix.lower() != ".zst":
        with path.open("r", encoding="utf-8") as source:
            yield source
        return

    process = subprocess.Popen(
        ["zstd", "-dc", "--", str(path)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    assert process.stdout is not None
    reader = io.TextIOWrapper(process.stdout, encoding="utf-8")
    try:
        yield reader
    finally:
        reader.close()
        stderr = process.stderr.read().decode("utf-8", errors="replace") if process.stderr else ""
        return_code = process.wait()
        if return_code != 0:
            raise RuntimeError(f"zstd failed with exit code {return_code}: {stderr.strip()}")


def provider_id(source_url: str, provider: str) -> int | None:
    parsed = urlparse(source_url)
    expected_host = {
        "mal": "myanimelist.net",
        "anilist": "anilist.co",
    }[provider]
    if parsed.scheme != "https" or (parsed.hostname or "").lower() != expected_host:
        return None
    match = re.fullmatch(r"/anime/(\d+)/?", parsed.path)
    if match is None:
        return None
    value = int(match.group(1))
    return value if value > 0 else None


def _add_index(
    index: dict[int, SourceEntry], ambiguous: set[int], provider_id_value: int, entry: SourceEntry
) -> None:
    if provider_id_value in ambiguous:
        return
    existing = index.get(provider_id_value)
    if existing is None:
        index[provider_id_value] = entry
    elif existing.ordinal != entry.ordinal:
        del index[provider_id_value]
        ambiguous.add(provider_id_value)


def build_source_index(lines: Iterable[str]) -> SourceIndex:
    mal: dict[int, SourceEntry] = {}
    anilist: dict[int, SourceEntry] = {}
    ambiguous_mal: set[int] = set()
    ambiguous_anilist: set[int] = set()
    entry_ordinal = 0
    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            raise ValueError(f"Blank source record at line {line_number}")
        try:
            record = json.loads(line)
        except json.JSONDecodeError as error:
            raise ValueError(f"Malformed source JSON at line {line_number}") from error
        # The JSONL asset starts with a metadata record, not an Anime entry.
        if "sources" not in record:
            continue
        title = record.get("title")
        sources = record.get("sources")
        synonyms = record.get("synonyms", [])
        if not isinstance(title, str) or not isinstance(sources, list) or not isinstance(synonyms, list):
            raise ValueError(f"Invalid source Anime record at line {line_number}")
        entry_ordinal += 1
        entry = SourceEntry(
            ordinal=entry_ordinal,
            title=title,
            synonyms=tuple(value for value in synonyms if isinstance(value, str)),
        )
        mal_ids = {value for source in sources if isinstance(source, str) for value in [provider_id(source, "mal")] if value}
        anilist_ids = {value for source in sources if isinstance(source, str) for value in [provider_id(source, "anilist")] if value}
        for value in mal_ids:
            _add_index(mal, ambiguous_mal, value, entry)
        for value in anilist_ids:
            _add_index(anilist, ambiguous_anilist, value, entry)
    return SourceIndex(mal, anilist, frozenset(ambiguous_mal), frozenset(ambiguous_anilist))


def _positive_int(value: object) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int) and value > 0:
        return value
    return None


def match_source_entry(anime: dict[str, object], index: SourceIndex) -> tuple[SourceEntry | None, bool]:
    mal_id = _positive_int(anime.get("malId"))
    anilist_id = _positive_int(anime.get("anilistId"))
    mal_entry = None if mal_id in index.ambiguous_mal else index.mal.get(mal_id) if mal_id else None
    anilist_entry = (
        None if anilist_id in index.ambiguous_anilist else index.anilist.get(anilist_id) if anilist_id else None
    )
    if mal_entry is not None and anilist_entry is not None and mal_entry.ordinal != anilist_entry.ordinal:
        return None, True
    return mal_entry or anilist_entry, False


def _dedupe_key(value: str) -> str:
    return value.casefold() if value.isascii() else value


def normalize_aliases(canonical_title: str, entry: SourceEntry) -> tuple[list[dict[str, object]], bool]:
    canonical_key = _dedupe_key(canonical_title.strip())
    aliases: list[dict[str, object]] = []
    seen: set[str] = set()
    candidates = (entry.title, *entry.synonyms)
    for candidate in candidates:
        title = candidate.strip()
        if not title:
            continue
        key = _dedupe_key(title)
        if key == canonical_key or key in seen:
            continue
        seen.add(key)
        aliases.append({"title": title, "language": None, "kind": ALIAS_KIND})
    truncated = len(aliases) > MAX_ALIASES
    return aliases[:MAX_ALIASES], truncated


def generate_records(
    rankoffline_catalog: list[dict[str, object]],
    index: SourceIndex,
    issue_report: dict[str, list[str]] | None = None,
) -> tuple[list[dict[str, object]], GenerationAudit]:
    records: list[dict[str, object]] = []
    matched = 0
    conflicts = 0
    truncated = 0
    alias_counts: list[int] = []
    seen_anime_ids: set[str] = set()
    for anime in rankoffline_catalog:
        anime_id = anime.get("id")
        canonical_title = anime.get("title")
        if not isinstance(anime_id, str) or not anime_id or not isinstance(canonical_title, str):
            raise ValueError("RankOffline catalog entries require nonblank id and string title")
        if anime_id in seen_anime_ids:
            raise ValueError(f"Duplicate RankOffline Anime ID: {anime_id}")
        seen_anime_ids.add(anime_id)
        entry, conflict = match_source_entry(anime, index)
        if conflict:
            conflicts += 1
            if issue_report is not None:
                issue_report["conflictAnimeIds"].append(anime_id)
            continue
        if entry is None:
            if issue_report is not None:
                issue_report["unmatchedAnimeIds"].append(anime_id)
            continue
        matched += 1
        aliases, was_truncated = normalize_aliases(canonical_title, entry)
        truncated += int(was_truncated)
        alias_counts.append(len(aliases))
        records.append({"animeId": anime_id, "aliases": aliases})
    records.sort(key=lambda record: str(record["animeId"]))
    sorted_counts = sorted(alias_counts)
    p95 = sorted_counts[max(0, (95 * len(sorted_counts) + 99) // 100 - 1)] if sorted_counts else 0
    audit = GenerationAudit(
        rankofflineAnimeCount=len(rankoffline_catalog),
        matchedAnimeCount=matched,
        unmatchedAnimeCount=len(rankoffline_catalog) - matched - conflicts,
        conflictCount=conflicts,
        animeWithAliases=sum(count > 0 for count in alias_counts),
        totalAliasCount=sum(alias_counts),
        medianAliasesPerMatchedAnime=float(statistics.median(alias_counts)) if alias_counts else 0.0,
        p95AliasesPerMatchedAnime=p95,
        maxAliasesPerMatchedAnime=max(alias_counts, default=0),
        truncatedByCapCount=truncated,
    )
    return records, audit


def _atomic_write(path: Path, writer) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    file_descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    os.close(file_descriptor)
    temporary_path = Path(temporary_name)
    try:
        writer(temporary_path)
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def write_outputs(
    output_dir: Path,
    records: list[dict[str, object]],
    source_asset: str,
    source_release: str,
    source_sha256: str,
) -> dict[str, object]:
    catalog_path = output_dir / "anime_aliases.jsonl.gz"

    def write_catalog(path: Path) -> None:
        with path.open("wb") as raw:
            with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
                for record in records:
                    line = json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
                    compressed.write(line.encode("utf-8"))

    _atomic_write(catalog_path, write_catalog)
    alias_count = sum(len(record["aliases"]) for record in records)
    manifest: dict[str, object] = {
        "formatVersion": FORMAT_VERSION,
        "source": SOURCE,
        "sourceOriginalProject": SOURCE_ORIGINAL_PROJECT,
        "sourceRelease": source_release,
        "sourceAsset": source_asset,
        "sourceAssetSha256": source_sha256,
        "license": LICENSE,
        "recordCount": len(records),
        "aliasCount": alias_count,
        "sha256": sha256_file(catalog_path),
    }
    manifest_path = output_dir / "anime_aliases.manifest.json"

    def write_manifest(path: Path) -> None:
        path.write_text(
            json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )

    _atomic_write(manifest_path, write_manifest)
    return manifest


def generate(
    rankoffline_catalog_path: Path,
    source_path: Path,
    output_dir: Path,
    source_release: str,
    expected_source_sha256: str,
    report_path: Path | None = None,
) -> tuple[dict[str, object], GenerationAudit]:
    if source_release != SOURCE_RELEASE:
        raise ValueError(f"Source release must be pinned to {SOURCE_RELEASE}")
    actual_source_sha256 = sha256_file(source_path)
    if actual_source_sha256 != expected_source_sha256:
        raise ValueError(
            f"Source SHA-256 mismatch: expected {expected_source_sha256}, got {actual_source_sha256}"
        )
    with rankoffline_catalog_path.open("r", encoding="utf-8") as catalog_file:
        catalog = json.load(catalog_file)
    if not isinstance(catalog, list):
        raise ValueError("RankOffline Anime catalog must be a JSON array")
    with open_source_text(source_path) as source_file:
        index = build_source_index(source_file)
    issues = {"conflictAnimeIds": [], "unmatchedAnimeIds": []}
    records, audit = generate_records(catalog, index, issues)
    manifest = write_outputs(
        output_dir,
        records,
        source_path.name,
        source_release,
        actual_source_sha256,
    )
    if report_path is not None:
        def write_report(path: Path) -> None:
            report = {"summary": audit.__dict__, **issues}
            path.write_text(
                json.dumps(report, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n",
                encoding="utf-8",
            )

        _atomic_write(report_path, write_report)
    return manifest, audit


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rankoffline-catalog", required=True, type=Path)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--source-release", required=True)
    parser.add_argument("--source-sha256", default=SOURCE_ASSET_SHA256)
    parser.add_argument("--report", type=Path, help="Optional conflict/unmatched audit JSON path")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    manifest, audit = generate(
        args.rankoffline_catalog,
        args.source,
        args.output_dir,
        args.source_release,
        args.source_sha256,
        args.report,
    )
    print(json.dumps({"manifest": manifest, "audit": audit.__dict__}, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
