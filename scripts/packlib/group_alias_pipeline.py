"""Add verified group aliases to generated payloads and write a provenance audit."""

from __future__ import annotations

from collections import Counter
import gzip
import json
from pathlib import Path
import re

from .common import deterministic_gzip_writer
from .group_aliases import derive_group_candidates, normalized, resolve_collisions
from .group_splits import split_reviewed_groups
from .group_title_roots import group_title_roots


def _read_records(path: Path):
    opener = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rt", encoding="utf-8") as source:
        for line in source:
            if line.strip():
                yield json.loads(line)


_ONE_SHOT = re.compile(r"\(\s*one[- ]?shot\s*\)$", re.IGNORECASE)


def _preserve_one_shot_item_aliases(files: dict[str, Path], staging: Path,
                                    groups: list[dict], items: dict[str, dict]) -> None:
    marked_ids = {member["mediaId"] for group in groups
                  if group.get("groupType") == "variants" and
                  any(not _ONE_SHOT.search(items[m["mediaId"]]["title"])
                      for m in group["members"])
                  for member in group["members"]
                  if _ONE_SHOT.search(items[member["mediaId"]]["title"])}
    if not marked_ids:
        return
    for member_id in marked_ids:
        item = items[member_id]
        for alias in item.get("aliases", []):
            if not _ONE_SHOT.search(alias["title"]):
                if len(alias["title"]) > 490:
                    raise ValueError(f"One-shot alias cannot retain edition marker: {member_id}")
                alias["title"] += " (Oneshot)"
    for name in [key for key in files if key.startswith("catalog/") and ".jsonl" in key]:
        target = staging / (Path(name).name + ".variant-aliases")
        original = _read_records(files[name])
        if name.endswith(".gz"):
            with deterministic_gzip_writer(target) as output:
                for item in original:
                    output.write(items.get(item["id"], item))
        else:
            with target.open("w", encoding="utf-8") as output:
                for item in original:
                    output.write(json.dumps(items.get(item["id"], item), ensure_ascii=False,
                                            sort_keys=True) + "\n")
        files[name] = target


def add_group_aliases(files: dict[str, Path], staging: Path, audit_dir: Path, pack_id: int,
                      review_holds: dict[str, list[str] | None] | None = None,
                      reviewed_aliases: dict[str, list[str]] | None = None,
                      reviewed_titles: dict[str, str] | None = None,
                      reviewed_splits: dict[str, dict] | None = None) -> dict:
    group_paths = [name for name in files if name.startswith("groups/") and ".jsonl" in name]
    item_paths = [name for name in files if name.startswith("catalog/") and ".jsonl" in name]
    if not group_paths:
        return {"groupsWithAliases": 0, "groupAliases": 0, "hasGroupAliasField": False}
    groups = [group for name in group_paths for group in _read_records(files[name])]
    has_declared_aliases = any(group.get("aliases") for group in groups)
    member_ids = {member["mediaId"] for group in groups for member in group["members"]}
    items = {item["id"]: item for name in item_paths for item in _read_records(files[name])
             if item["id"] in member_ids}
    groups, split_report = split_reviewed_groups(groups, items, reviewed_splits)
    split_sources = {change["sourceGroupId"]: [part["groupId"] for part in change["parts"]]
                     for change in split_report}
    _preserve_one_shot_item_aliases(files, staging, groups, items)
    initial = {group["id"]: derive_group_candidates(group, items) for group in groups}
    title_roots = group_title_roots(groups, items, [candidate for values in initial.values()
                                                    for candidate in values], reviewed_titles)
    candidates = [candidate for group in groups for candidate in (
        derive_group_candidates({**group, "displayTitle": title_roots[group["id"]]},
                                items, title_roots[group["id"]])
        if group["id"] in title_roots else initial[group["id"]])]
    for candidate in candidates:
        if candidate["status"] != "accepted" or candidate["groupId"] not in (review_holds or {}):
            continue
        held_names = review_holds[candidate["groupId"]]
        if held_names is None or candidate["normalized"] in {normalized(name) for name in held_names}:
            candidate["status"] = "ambiguous"
            candidate["reason"] = "source alternative needs manual group identity review"
    for group_id, names in (reviewed_aliases or {}).items():
        for name in names:
            matches = [candidate for candidate in candidates
                       if candidate["groupId"] == group_id
                       and candidate["normalized"] == normalized(name)]
            if len(matches) != 1 or matches[0]["status"] == "rejected":
                raise ValueError(f"Reviewed group alias lacks safe source evidence: {group_id}: {name}")
            candidate = matches[0]
            if candidate["status"] == "ambiguous":
                candidate["status"] = "accepted"
                candidate["reason"] = "reviewed declared group alias"
    effective_groups = [{**group, "displayTitle": title_roots.get(group["id"], group["displayTitle"])}
                        for group in groups]
    candidates = resolve_collisions(effective_groups, candidates)
    original_titles = {group["id"]: group["displayTitle"] for group in groups}
    for candidate in candidates:
        if candidate["groupId"] in title_roots:
            candidate["sourceGroupTitle"] = original_titles[candidate["groupId"]]
    explicit_ids = {group["id"] for group in groups if group.get("aliases")}
    for candidate in candidates:
        if candidate["groupId"] in explicit_ids and candidate["status"] == "accepted":
            candidate["status"] = "rejected"
            candidate["reason"] = "source already declares group aliases"
    by_group: dict[str, list[dict]] = {}
    for candidate in candidates:
        if candidate["status"] == "accepted":
            by_group.setdefault(candidate["groupId"], []).append(candidate)
    for group_id, group_candidates in by_group.items():
        if len(group_candidates) > 64:
            for candidate in sorted(group_candidates, key=lambda c: (-c["support"], c["normalized"]))[64:]:
                candidate["status"] = "rejected"
                candidate["reason"] = "group alias limit of 64"
    by_group = {}
    for candidate in candidates:
        if candidate["status"] == "accepted":
            by_group.setdefault(candidate["groupId"], []).append(candidate)
    counts = Counter(candidate["status"] for candidate in candidates)
    summary = {"groupsTotal": len(groups), "groupsWithAliases": len(by_group),
               "groupAliases": counts["accepted"], "ambiguous": counts["ambiguous"],
               "rejected": counts["rejected"], "collisions": sum(bool(c["collisions"]) for c in candidates),
               "hasGroupAliasField": has_declared_aliases or counts["accepted"] > 0}
    if title_roots:
        summary["rootTitleChanges"] = len(title_roots)
    if split_report:
        summary["reviewedSplits"] = len(split_report)
        summary["groupCount"] = len(groups)
        summary["relationshipCount"] = sum(len(group["members"]) for group in groups)
    audit_dir.mkdir(parents=True, exist_ok=True)
    with (audit_dir / f"pack-{pack_id}.jsonl").open("w", encoding="utf-8") as audit:
        for candidate in sorted(candidates, key=lambda c: (c["groupId"], c["normalized"])):
            audit.write(json.dumps({"packId": pack_id, **candidate}, ensure_ascii=False, sort_keys=True) + "\n")
    (audit_dir / f"pack-{pack_id}-summary.json").write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (audit_dir / f"pack-{pack_id}-title-roots.jsonl").write_text(
        "".join(json.dumps({"groupId": group["id"], "before": group["displayTitle"],
                            "after": title_roots[group["id"]],
                            "members": [items[m["mediaId"]]["title"] for m in group["members"]]},
                           ensure_ascii=False, sort_keys=True) + "\n"
                for group in groups if group["id"] in title_roots), encoding="utf-8")
    (audit_dir / f"pack-{pack_id}-splits.jsonl").write_text(
        "".join(json.dumps(change, ensure_ascii=False, sort_keys=True) + "\n"
                for change in split_report), encoding="utf-8")
    if not by_group and not title_roots and not split_report:
        return summary
    final_groups = {group["id"]: group for group in groups}
    for name in group_paths:
        target = staging / (Path(name).name + ".aliases")
        original = _read_records(files[name])
        if name.endswith(".gz"):
            with deterministic_gzip_writer(target) as output:
                for source in original:
                    for group_id in split_sources.get(source["id"], [source["id"]]):
                        group = dict(final_groups[group_id])
                        if group["id"] in title_roots:
                            group["displayTitle"] = title_roots[group["id"]]
                        accepted = by_group.get(group["id"], [])
                        if accepted:
                            group["aliases"] = [
                                {key: value for key, value in {
                                    "title": candidate["alias"], "language": candidate["language"],
                                    "kind": candidate["kind"]}.items() if value is not None}
                                for candidate in sorted(accepted, key=lambda c: (c["normalized"], c["alias"]))
                            ]
                        output.write(group)
        else:
            with target.open("w", encoding="utf-8") as output:
                for source in original:
                    for group_id in split_sources.get(source["id"], [source["id"]]):
                        group = dict(final_groups[group_id])
                        if group["id"] in title_roots:
                            group["displayTitle"] = title_roots[group["id"]]
                        accepted = by_group.get(group["id"], [])
                        if accepted:
                            group["aliases"] = [{"title": c["alias"]} for c in accepted]
                        output.write(json.dumps(group, ensure_ascii=False, sort_keys=True) + "\n")
        files[name] = target
    return summary
