"""Choose group display roots only when declared members establish the same work."""

from __future__ import annotations

from collections import defaultdict
import math
import re

from .group_aliases import _SUFFIX, normalized


_ORDINAL_SEASON = re.compile(
    r"^(.+?)(?:\s+|:\s*)(\d+(?:st|nd|rd|th)\s+season|"
    r"climax\s+season|final\s+season|the\s+final)(?:\s+(specials?))?$",
    re.IGNORECASE,
)
_WORD = re.compile(r"\w+", re.UNICODE)
_VARIANT_MARKER = re.compile(r"(?:\s*\((?:oneshot|one-shot|fan.colou?red|official.colou?red)\)|"
                             r"\s+(?:colou?r[- ]ban|fan.colou?red))\s*$", re.IGNORECASE)


def _kind_root(group: dict, titles: list[str]) -> str | None:
    """Name a homogeneous set of specials or films without using its first episode."""
    kind = group["id"].rsplit(":", 1)[-1]
    if kind not in {"SPECIAL", "MOVIES"}:
        return None
    parts = [re.split(r"[:?]", title, maxsplit=1) for title in titles]
    if any(len(part) != 2 or not part[1].strip() for part in parts):
        return None
    roots = [part[0].strip() for part in parts]
    if len({normalized(root) for root in roots}) != 1 or len(roots[0]) < 12:
        return None
    if any(normalized(root) == normalized(title) for root, title in zip(roots, titles)):
        return None
    root = re.sub(r"\s+(?:(?:the\s+)?movie|specials?|OVA|OAV)$", "", roots[0],
                  flags=re.IGNORECASE)
    subtitles = [part[1].strip() for part in parts]
    shared = subtitles[0]
    for subtitle in subtitles[1:]:
        shared = shared[:next((index for index, (left, right) in
                               enumerate(zip(shared, subtitle))
                               if left.casefold() != right.casefold()),
                              min(len(shared), len(subtitle)))]
    if shared and any(len(subtitle) > len(shared) and subtitle[len(shared)].isalnum()
                      and shared[-1].isalnum() for subtitle in subtitles):
        shared = re.sub(r"\S+$", "", shared)
    shared = shared.rstrip(" -—–:.,")
    if shared and any(normalized(shared) == normalized(subtitle) for subtitle in subtitles):
        return None  # Editions of one film/special do not form a titled set.
    shared = re.sub(r"\s+(?:(?:the\s+)?movie|specials?|OVA|OAV)$", "", shared,
                    flags=re.IGNORECASE).rstrip(" -—–:.,")
    if shared.casefold() in {"the", "movie", "special", "specials", "ova", "oav"}:
        shared = ""
    label = "Specials" if kind == "SPECIAL" else "Movies"
    return f"{root}: {shared} {label}" if len(shared) >= 4 else f"{root}: {label}"


def _colon_root(group: dict, titles: list[str]) -> str | None:
    if not all(":" in title or "?" in title for title in titles):
        return None
    roots = [re.split(r"[:?]", title, maxsplit=1)[0].strip() for title in titles]
    if len({normalized(root) for root in roots}) != 1:
        return None
    root = roots[0]
    if len(root) < 5 or normalized(root) == normalized(group["displayTitle"]):
        return None
    # "Show: Arc" and "Show: Arc Season 2" still name the same arc.
    subtitles = [_SUFFIX.sub("", re.split(r"[:?]", title, maxsplit=1)[1]).strip()
                 for title in titles]
    signatures = {tuple(_WORD.findall(normalized(subtitle))[:2]) for subtitle in subtitles}
    return root if len(signatures) >= 2 else None


def _variant_root(group: dict, items: dict[str, dict]) -> str | None:
    if group.get("groupType") != "variants" or len(group["members"]) < 2:
        return None
    members = [items[m["mediaId"]] for m in group["members"]]
    plain = [item for item in members if not _VARIANT_MARKER.search(item["title"])]
    marked = [item for item in members if _VARIANT_MARKER.search(item["title"])]
    if len(plain) != 1 or len(marked) != len(members) - 1:
        return None
    names = {normalized(plain[0]["title"])} | {
        normalized(alias["title"]) for alias in plain[0].get("aliases", [])}
    if not all(normalized(_VARIANT_MARKER.sub("", item["title"])) in names
               for item in marked):
        return None
    return plain[0]["title"]


def _season_root(group: dict, titles: list[str]) -> str | None:
    matches = [_ORDINAL_SEASON.fullmatch(title) for title in titles]
    if not all(matches):
        return None
    roots = {normalized(match.group(1)) for match in matches}
    suffixes = {normalized(match.group(3) or "") for match in matches}
    markers = {normalized(match.group(2)) for match in matches}
    if len(roots) != 1 or len(suffixes) != 1 or len(markers) < 2:
        return None
    root = matches[0].group(1).strip()
    suffix = matches[0].group(3)
    return f"{root} {suffix}" if suffix else root


def group_title_roots(groups: list[dict], items: dict[str, dict],
                      protected_aliases: list[dict],
                      reviewed_titles: dict[str, str] | None = None) -> dict[str, str]:
    """Preserve supplied collection/variant names and avoid cross-group roots."""
    occupied = defaultdict(set)
    for group in groups:
        occupied[normalized(group["displayTitle"])].add(group["id"])
    for alias in protected_aliases:
        if alias["status"] == "accepted":
            occupied[alias["normalized"]].add(alias["groupId"])
    proposed = {}
    for group in groups:
        if group.get("aliases") or len(group.get("members", [])) < 2:
            continue
        if group.get("groupType") == "variants":
            root = _variant_root(group, items)
            if root and normalized(root) != normalized(group["displayTitle"]):
                proposed[group["id"]] = root
            continue
        if group.get("groupType") != "relations":
            continue
        titles = [items[member["mediaId"]]["title"] for member in group["members"]]
        if normalized(group["displayTitle"]) not in {normalized(title) for title in titles}:
            continue
        kind = group["id"].rsplit(":", 1)[-1]
        root = (_kind_root(group, titles) if kind in {"SPECIAL", "MOVIES"} else
                _colon_root(group, titles) or _season_root(group, titles))
        if root and len(root) >= 5 and normalized(root) != normalized(group["displayTitle"]):
            proposed[group["id"]] = root
    by_id = {group["id"]: group for group in groups}
    for group_id, title in (reviewed_titles or {}).items():
        group = by_id.get(group_id)
        kind = group_id.rsplit(":", 1)[-1]
        parent = by_id.get(group_id.rsplit(":", 1)[0] + ":SERIES")
        label = "Specials" if kind == "SPECIAL" else "Movies" if kind == "MOVIES" else None
        if group is None or parent is None or label is None or group.get("aliases"):
            raise ValueError(f"Reviewed group title has no declared parent: {group_id}")
        stem = parent["displayTitle"]
        if normalized(title) != normalized(f"{stem}: {label}"):
            raise ValueError(f"Reviewed group title differs from its declared parent: {group_id}")
        prefix = normalized(stem)
        def starts_at_boundary(value: str) -> bool:
            value = normalized(value)
            return value == prefix or (value.startswith(prefix) and len(value) > len(prefix)
                                        and not value[len(prefix)].isalnum())
        members = [items[member["mediaId"]] for member in group["members"]]
        canonical = sum(starts_at_boundary(member["title"]) for member in members)
        if (canonical < max(2, math.ceil(len(members) * 0.9)) or
                not all(starts_at_boundary(member["title"]) or any(
                    starts_at_boundary(alias["title"]) for alias in member.get("aliases", []))
                    for member in members)):
            raise ValueError(f"Reviewed group title lacks member evidence: {group_id}")
        proposed[group_id] = title
    claims = defaultdict(set)
    for group_id, root in proposed.items():
        claims[normalized(root)].add(group_id)
    result = {group_id: root for group_id, root in proposed.items()
              if not (occupied[normalized(root)] - {group_id})
              and claims[normalized(root)] == {group_id}}
    if set(reviewed_titles or {}) - set(result):
        raise ValueError("Reviewed group title collides with another group")
    return result
