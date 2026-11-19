"""Apply reviewed splits of source groups without inferring new relationships."""

from __future__ import annotations

import hashlib


def _ids_digest(ids: set[str]) -> str:
    return hashlib.sha256("\n".join(sorted(ids)).encode("utf-8")).hexdigest()


def split_reviewed_groups(groups: list[dict], items: dict[str, dict],
                          specs: dict[str, dict] | None) -> tuple[list[dict], list[dict]]:
    if not specs:
        return groups, []
    by_id = {group["id"]: group for group in groups}
    if len(by_id) != len(groups):
        raise ValueError("Duplicate source group ID")
    replacements: dict[str, list[dict]] = {}
    report = []
    for source_id, spec in sorted(specs.items()):
        source = by_id.get(source_id)
        if source is None or source.get("aliases"):
            raise ValueError(f"Reviewed split has no plain source group: {source_id}")
        original = {member["mediaId"] for member in source["members"]}
        if len(original) != len(source["members"]) or _ids_digest(original) != spec["sourceIdsSha256"]:
            raise ValueError(f"Reviewed split source members changed: {source_id}")
        ungrouped = set(spec.get("ungrouped", []))
        if len(ungrouped) != len(spec.get("ungrouped", [])) or not ungrouped <= original:
            raise ValueError(f"Invalid ungrouped members in reviewed split: {source_id}")
        assigned = set(ungrouped)
        produced = []
        for part in spec["parts"]:
            group_id = part["id"]
            if group_id != source_id and group_id in by_id:
                raise ValueError(f"Reviewed split group ID already exists: {group_id}")
            if any(group["id"] == group_id for group in produced):
                raise ValueError(f"Duplicate reviewed split group ID: {group_id}")
            prefixes = part["titlePrefixes"]
            if not prefixes or len(prefixes) != len(set(prefixes)):
                raise ValueError(f"Invalid reviewed split title prefixes: {group_id}")
            selected = [member for member in source["members"] if any(
                items[member["mediaId"]]["title"].startswith(prefix) for prefix in prefixes)]
            ids = {member["mediaId"] for member in selected}
            if (len(ids) < 2 or ids & assigned or
                    _ids_digest(ids) != part["memberIdsSha256"]):
                raise ValueError(f"Reviewed split members changed or overlap: {group_id}")
            assigned.update(ids)
            members = [{"mediaId": member["mediaId"], "position": position}
                       for position, member in enumerate(selected)]
            replacement = {**source, "id": group_id, "displayTitle": part["displayTitle"],
                           "members": members,
                           "representativeMediaId": (source.get("representativeMediaId")
                                                     if source.get("representativeMediaId") in ids
                                                     else members[0]["mediaId"])}
            # The source popularity came from a crossover removed from both groups.
            for key in ("popularity", "popularityRank", "remoteCoverRef"):
                replacement.pop(key, None)
            if group_id != source_id:
                # The new group has no provider identity of its own. Reusing the
                # source identity would collide with the retained group in Room.
                replacement.pop("provider", None)
                replacement.pop("providerGroupId", None)
            produced.append(replacement)
        if assigned != original or not any(group["id"] == source_id for group in produced):
            raise ValueError(f"Reviewed split does not partition source: {source_id}")
        replacements[source_id] = produced
        report.append({"sourceGroupId": source_id,
                       "parts": [{"groupId": group["id"], "title": group["displayTitle"],
                                  "memberIds": [m["mediaId"] for m in group["members"]]}
                                 for group in produced],
                       "ungrouped": sorted(ungrouped)})
    return [replacement for group in groups
            for replacement in replacements.get(group["id"], [group])], report
