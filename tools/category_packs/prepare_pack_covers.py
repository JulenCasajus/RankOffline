#!/usr/bin/env python3
"""Prepare licensed, deterministic cover payloads for RankOffline data-only packs.

The source manifest is deliberately explicit: every candidate must name the stable catalog
identity, a local file or HTTPS source, and documentary redistribution permission. This tool
never treats API access or hot-link permission as redistribution permission.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import struct
import urllib.request

MAX_IMAGE_BYTES = 2 * 1024 * 1024
SUPPORTED_MAGIC = {
    b"\xff\xd8\xff": ".jpg",
    b"\x89PNG\r\n\x1a\n": ".png",
    b"GIF87a": ".gif",
    b"GIF89a": ".gif",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def image_extension(path: Path) -> str:
    size = path.stat().st_size
    if size <= 0 or size > MAX_IMAGE_BYTES:
        raise ValueError(f"Invalid image size for {path}: {size}")
    head = path.read_bytes()[:16]
    if head.startswith(b"RIFF") and head[8:12] == b"WEBP":
        return ".webp"
    for magic, extension in SUPPORTED_MAGIC.items():
        if head.startswith(magic):
            if extension == ".png":
                header = path.read_bytes()[:24]
                if len(header) < 24 or header[12:16] != b"IHDR":
                    raise ValueError(f"Invalid PNG structure: {path}")
                width, height = struct.unpack(">II", header[16:24])
                if width <= 0 or height <= 0 or width > 20_000 or height > 20_000:
                    raise ValueError(f"Invalid PNG dimensions for {path}: {width}x{height}")
            return extension
    raise ValueError(f"Unsupported or invalid image bytes: {path}")


def stable_filename(canonical_id: str, extension: str) -> str:
    return hashlib.sha256(canonical_id.encode("utf-8")).hexdigest()[:32] + extension


def read_catalog(path: Path) -> list[dict]:
    with gzip.open(path, "rt", encoding="utf-8") as source:
        return [json.loads(line) for line in source if line.strip()]


def logical_cover_ids(catalog: list[dict], groups_path: Path | None) -> list[str]:
    by_id = {item["id"]: item for item in catalog}
    candidates: list[tuple] = []
    grouped = set()
    if groups_path and groups_path.is_file():
        with gzip.open(groups_path, "rt", encoding="utf-8") as source:
            for line in source:
                if not line.strip():
                    continue
                group = json.loads(line)
                members = sorted(group["members"], key=lambda value: (value["position"], value["mediaId"]))
                first_id = members[0]["mediaId"]
                grouped.update(member["mediaId"] for member in members)
                if first_id in by_id:
                    candidates.append((
                        group.get("popularityRank") is None,
                        group.get("popularityRank") or 2**63 - 1,
                        -(group.get("popularity") or -1),
                        group.get("displayTitle", "").casefold(),
                        group["id"],
                        first_id,
                    ))
    for item in catalog:
        if item["id"] not in grouped:
            candidates.append((
                item.get("popularityRank") is None,
                item.get("popularityRank") or 2**63 - 1,
                -(item.get("popularity") or -1),
                item.get("title", "").casefold(),
                item["id"],
                item["id"],
            ))
    ordered = []
    seen = set()
    for candidate in sorted(candidates):
        identity = candidate[-1]
        if identity not in seen:
            seen.add(identity)
            ordered.append(identity)
    return ordered


def fetch_source(entry: dict, cache: Path) -> Path:
    local = entry.get("localPath")
    if local:
        path = Path(local).expanduser().resolve()
        if not path.is_file():
            raise FileNotFoundError(path)
        return path
    url = entry.get("sourceUrl", "")
    if not url.startswith("https://"):
        raise ValueError("Cover source must be an existing local file or HTTPS URL")
    key = hashlib.sha256(url.encode("utf-8")).hexdigest()
    cached = cache / key
    if cached.is_file():
        image_extension(cached)
        return cached
    request = urllib.request.Request(url, headers={"User-Agent": "RankOffline-pack-builder/1"})
    with urllib.request.urlopen(request, timeout=20) as response:
        content_type = response.headers.get_content_type()
        if not content_type.startswith("image/"):
            raise ValueError(f"Non-image response for {url}: {content_type}")
        data = response.read(MAX_IMAGE_BYTES + 1)
    if len(data) > MAX_IMAGE_BYTES:
        raise ValueError(f"Image exceeds {MAX_IMAGE_BYTES} bytes: {url}")
    cache.mkdir(parents=True, exist_ok=True)
    cached.write_bytes(data)
    image_extension(cached)
    return cached


def prepare(args: argparse.Namespace) -> dict:
    sources = json.loads(args.sources.read_text(encoding="utf-8"))
    entries = sources.get("covers", [])
    by_id = {entry["canonicalId"]: entry for entry in entries}
    for entry in entries:
        if entry.get("redistributionAllowed") is not True or not entry.get("licenseEvidence"):
            raise ValueError(f"Missing redistribution evidence for {entry.get('canonicalId')}")
    catalog = read_catalog(args.catalog)
    ordered_ids = logical_cover_ids(catalog, args.groups if args.one_cover_per_group else None)
    args.output.mkdir(parents=True, exist_ok=True)
    covers_dir = args.output / "covers"
    if covers_dir.exists():
        shutil.rmtree(covers_dir)
    covers_dir.mkdir()
    cache = args.cache.expanduser().resolve()
    selected, skipped = [], []
    for canonical_id in ordered_ids:
        if len(selected) == args.limit:
            break
        entry = by_id.get(canonical_id)
        if entry is None:
            skipped.append({"canonicalId": canonical_id, "reason": "no licensed source"})
            continue
        try:
            source = fetch_source(entry, cache)
            extension = image_extension(source)
            destination = covers_dir / stable_filename(canonical_id, extension)
            shutil.copyfile(source, destination)
            selected.append({
                "canonicalId": canonical_id,
                "path": f"covers/{destination.name}",
                "bytes": destination.stat().st_size,
                "sha256": sha256(destination),
                "licenseEvidence": entry["licenseEvidence"],
                "source": entry.get("sourceUrl") or entry.get("localPath"),
            })
        except Exception as error:  # report each deterministic replacement; never fake success
            skipped.append({"canonicalId": canonical_id, "reason": str(error)})
    if len(selected) != args.limit:
        raise ValueError(f"Prepared {len(selected)} of required {args.limit} verified covers")
    index = {"schemaVersion": 1, "covers": [
        {"canonicalId": item["canonicalId"], "path": item["path"]} for item in selected
    ]}
    metadata = args.output / "metadata"
    metadata.mkdir(exist_ok=True)
    (metadata / "cover_index.json").write_text(
        json.dumps(index, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n",
        encoding="utf-8",
    )
    report = {"packId": args.pack_id, "selected": selected, "skipped": skipped}
    (args.output / "cover_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return report


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pack-id", required=True)
    parser.add_argument("--catalog", required=True, type=Path)
    parser.add_argument("--groups", type=Path)
    parser.add_argument("--sources", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--cache", type=Path, default=Path("tools/.cache/category-packs/cover-downloads"))
    parser.add_argument("--limit", type=int, default=100)
    parser.add_argument("--one-cover-per-group", action="store_true")
    args = parser.parse_args()
    report = prepare(args)
    print(f"{args.pack_id}: prepared {len(report['selected'])} verified covers")


if __name__ == "__main__":
    main()
