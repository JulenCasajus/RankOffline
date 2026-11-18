"""Conservative, reproducible group-name evidence from declared catalog members."""

from __future__ import annotations

from collections import defaultdict
import math
import re
import unicodedata


_SPACE = re.compile(r"\s+")
_SUFFIX = re.compile(
    r"\s*(?:[—–: -]\s*)?(season|series|part|volume|vol\.?|book)\s+"
    r"([0-9]{1,3}|[ivxlcdm]{1,8})\s*$", re.IGNORECASE
)
_ORDINAL_SUFFIX = re.compile(r"\s*(?:[—–: -]\s*)?([2-9])(?:nd|rd|th)\s+season\s*$",
                             re.IGNORECASE)
_BARE_SUFFIX = re.compile(r"\s+([2-9])\s*$")
_DANGLING_MARKER = re.compile(
    r"\b(?:season|series|part|volume|vol\.?|book|episode|chapter|cour|saison|"
    r"temporada|staffel|stagione|sezon|seizoen|sesong|säsong|parte|phần|"
    r"сезон|сезона|עונה|موسم)\s*$|"
    r"(?:ภาค(?:ที่)?|พาร์ต|ซีซั่น|シーズン|시즌)\s*$",
    re.IGNORECASE,
)
_INSTALLMENT_MARKER = re.compile(
    r"\b(?:season|series|part|volume|vol\.?|book|episode)\s+"
    r"(?:[0-9]{1,3}|[ivxlcdm]{1,8})\s*$", re.IGNORECASE
)
_TRAILING_NUMBER = re.compile(r"(?<!\w)([2-9])\s*$")
_NUMBER_WORDS = ("", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
_SHORT_SEASON_MARKER = re.compile(
    r"\b(?:season|series|part|volume|vol\.?|book|episode|chapter|arc|act|cour|"
    r"saison|temporada|staffel|stagione|sezon|seizoen|sesong|säsong|сезон)\s*"
    r"(?:[2-9]|[ivx]{2,4})\b|"
    r"\b(?:final|last)\s+(?:season|series|part)\b|"
    r"\b(?:[2-9](?:nd|rd|th)?|second|third|fourth)\s*"
    r"(?:season|series|part|episode|chapter|arc|act|cour)\b|"
    r"\b[2-9](?:nd|rd|th)\b|\b(?:ii|iii|iv|v|vi)\b|\bs[2-9]\b|"
    r"(?:第?[二三四五六七八九2-9](?:季|期|幕|部|章|回|기|丁目|초메|シリーズ|シーズン|クール)|"
    r"[2-9](?:nd|rd|th)?(?:シーズン|시즌)|"
    r"(?:season|シーズン|시즌|제)\s*[二三四五六七八九2-9]|"
    r"(?:dai\s+(?:ni|san|yon)|(?:ni|san|yon)[\s-]ki)\s+(?:maku|shou|ki)|"
    r"\b[2-9][\s-]?(?:choume|chōme)\b)",
    re.IGNORECASE,
)
_SHORT_SEASON_SPECIAL = re.compile(
    r"\b(?:ova|oav|special|movie|film|shorts|finale|climax)\b|"
    r"\bno\.?\s*\d+(?:\s*\+\s*\d+)?\b|"
    r"(?:特别篇|特別篇|劇場版|극장판|스페셜|新編集版)|"
    r"(?:\(?\b(?:19|20)\d{2}\)?\s*$)", re.IGNORECASE
)
_IDENTITY_WORDS = {"the", "and", "with", "for", "from", "show", "series", "season",
                   "of", "in", "to", "a", "an", "de", "la", "el", "le", "les", "et"}
_NEW_ALIAS_REASONS = {"plausible parallel root of every short season",
                      "declared alias of the shared canonical root",
                      "declared root alias corroborated by numbered members",
                      "parallel aliases of a shared subtitle root",
                      "parallel aliases of a declared set root",
                      "parallel alias of multiple seasons",
                      "reviewed declared group alias"}
_FIRST_INSTALLMENT = re.compile(
    r"\b(?:first|1st)\s+(?:season|series|part|chapter|stage)\b|"
    r"\b(?:season|series|part|chapter|stage|staffel|temporada|saison|stagione)\s*1\b|"
    r"(?:第?[一1](?:季|期|部|幕)|上部)|(?<!\w)1\s*$",
    re.IGNORECASE,
)


def _meaningful(value: str) -> bool:
    letters = [ch for ch in value if ch.isalpha()]
    return len(letters) >= 4 or (len(letters) >= 2 and all(
        unicodedata.east_asian_width(ch) in {"W", "F"} for ch in letters
    ))


def normalized(value: str) -> str:
    return _SPACE.sub(" ", unicodedata.normalize("NFKC", value).casefold()).strip()


def _alias_order(alias: dict) -> tuple[str, str, str]:
    return (alias["title"], alias.get("language") or "", alias.get("kind") or "")


def _structural_suffix(title: str, group_title: str) -> tuple[str, str] | None:
    if not title.casefold().startswith(group_title.casefold()):
        return None
    rest = title[len(group_title):]
    match = _SUFFIX.fullmatch(rest)
    if match:
        return (match.group(1).casefold().rstrip("."), match.group(2).casefold())
    match = _ORDINAL_SUFFIX.fullmatch(rest)
    if match:
        return ("season", match.group(1))
    match = _BARE_SUFFIX.fullmatch(rest)
    return ("bare", match.group(1)) if match else None


def _alias_root(title: str, suffix: tuple[str, str]) -> str | None:
    match = _SUFFIX.search(title)
    if not match or (match.group(1).casefold().rstrip("."), match.group(2).casefold()) != suffix:
        match = (_ORDINAL_SUFFIX.search(title) if suffix[0] == "season" else
                 _BARE_SUFFIX.search(title) if suffix[0] == "bare" else None)
        if not match or match.group(1) != suffix[1]:
            return None
    root = title[:match.start()].rstrip(" -—–:")
    return root if root and _meaningful(root) else None


def _member_specific_root(alias: str, group_title: str) -> bool:
    if _INSTALLMENT_MARKER.search(alias):
        return True
    number = _TRAILING_NUMBER.search(alias)
    if not number:
        return False
    digit = number.group(1)
    canonical = normalized(group_title)
    return not (re.search(rf"(?<!\d){digit}(?:st|nd|rd|th)?(?!\d)", canonical)
                or re.search(rf"\b{_NUMBER_WORDS[int(digit)]}\b", canonical))


def _short_season_issue(alias: str, group_title: str, sources: dict) -> str | None:
    """Require a declared parallel root and exclude visible installment identities.

    A US/GB alternative with no shared name token remains for review: provider
    alternatives can name another programme even when copied to every season.
    This is a plausibility check, not a translation oracle.
    """
    title = normalized(group_title)
    name = normalized(alias)
    for pattern in (_SHORT_SEASON_MARKER, _SHORT_SEASON_SPECIAL):
        if any(match.group().strip() not in title for match in pattern.finditer(name)):
            return "possible season, episode or special title in a short series"
    countries = {source.get("kind") for source in sources.values()}
    if countries <= {"US", "GB"}:
        words = lambda value: {word for word in re.findall(r"[^\W_]+", value)
                               if len(word) >= 3 and word not in _IDENTITY_WORDS}
        title_words = words(title)
        if len(words(name) & title_words) < min(2, len(title_words)):
            return "unanchored US/GB alternate may name a different programme"
    return None


def _root_alias_is_specific(alias: str, root: str) -> bool:
    name = normalized(alias)
    title = normalized(root)
    if (":" in name and ":" not in title) or (
        len(name) < 6 and title.startswith(name) and len(title) > 2 * len(name)
    ):
        return True
    if _member_specific_root(alias, root) or _short_season_issue(
        alias, root, {"root": {"kind": None}}):
        return True
    return any(match.group().strip() not in title for match in _FIRST_INSTALLMENT.finditer(name))


def _subtitle_root_evidence(evidence: dict, group: dict, items: dict[str, dict],
                            root_title: str) -> None:
    kind_root = re.sub(r": (?:Specials|Movies)$", "", root_title)
    is_kind_root = kind_root != root_title
    for member in sorted(group["members"], key=lambda member: member["mediaId"]):
        member_id = member["mediaId"]
        item = items[member_id]
        if is_kind_root and item["title"].casefold().startswith(kind_root.casefold() + ":"):
            suffix = item["title"].split(":", 1)[1].strip()
            for alias in item.get("aliases", []):
                text = alias["title"]
                if not text.casefold().endswith(suffix.casefold()):
                    continue
                candidate = text[:-len(suffix)].rstrip(" -—–:")
                if not _meaningful(candidate) or len(candidate) > 500:
                    continue
                entry = evidence.setdefault(normalized(candidate), {"forms": [], "members": {}})
                entry["forms"].append(candidate)
                entry["members"][member_id] = {
                    "canonicalTitle": item["title"], "sourceAlias": text,
                    "language": alias.get("language"), "kind": alias.get("kind"),
                    "rule": "parallel_kind_root",
                }
        if not item["title"].casefold().startswith(root_title.casefold() + ":"):
            continue
        for alias in item.get("aliases", []):
            if str(alias.get("kind") or "").upper() in {"SUBJECT", "SUBTITLE"} or ":" not in alias["title"]:
                continue
            candidate = alias["title"].split(":", 1)[0].strip()
            if (not _meaningful(candidate) or len(candidate) > 500 or
                    normalized(candidate) == normalized(root_title)):
                continue
            key = normalized(candidate)
            entry = evidence.setdefault(key, {"forms": [], "members": {}})
            entry["forms"].append(candidate)
            source = {"canonicalTitle": item["title"], "sourceAlias": alias["title"],
                      "language": alias.get("language"), "kind": alias.get("kind"),
                      "rule": "parallel_subtitle_root"}
            previous = entry["members"].get(member_id)
            if previous is None or (source["sourceAlias"], source["language"] or "",
                                    source["kind"] or "") < (previous["sourceAlias"],
                                                             previous["language"] or "",
                                                             previous["kind"] or ""):
                entry["members"][member_id] = source


def _numbered_root_evidence(evidence: dict, group: dict, items: dict[str, dict]) -> None:
    if group.get("groupType") != "relations" or not group["id"].endswith(":SERIES"):
        return
    root = group["displayTitle"]
    for member in sorted(group["members"], key=lambda value: value["mediaId"]):
        member_id = member["mediaId"]
        item = items[member_id]
        if not normalized(item["title"]).startswith(normalized(root)):
            continue
        for alias in item.get("aliases", []):
            match = _BARE_SUFFIX.search(alias["title"])
            if not match and re.search(r"(?<!\w)1\s*$", alias["title"]):
                match = re.search(r"(?<!\w)1\s*$", alias["title"])
            if not match:
                continue
            candidate = alias["title"][:match.start()].strip()
            if (not _meaningful(candidate) or len(candidate) > 500 or
                    normalized(candidate) == normalized(root)):
                continue
            entry = evidence.setdefault(normalized(candidate), {"forms": [], "members": {}})
            entry["forms"].append(candidate)
            entry["members"][member_id] = {
                "canonicalTitle": item["title"], "sourceAlias": alias["title"],
                "language": alias.get("language"), "kind": alias.get("kind"),
                "rule": "parallel_numbered_alias_root",
            }


def derive_group_candidates(group: dict, items: dict[str, dict],
                            root_title: str | None = None) -> list[dict]:
    """Only parallel canonical parts and parallel aliases can prove a group root."""
    title = group.get("displayTitle") or group["id"]
    members = {m["mediaId"] for m in group["members"]}
    evidence: dict[str, dict[str, object]] = {}
    structural: dict[str, tuple[str, str]] = {}
    for member_id in members:
        item = items.get(member_id)
        if item is None:
            raise ValueError(f"Missing declared member {member_id} in {group['id']}")
        suffix = _structural_suffix(item["title"], title)
        if suffix:
            structural[member_id] = suffix
    # A direct canonical match can corroborate parallel parts, but is never enough alone.
    for member_id in sorted(members):
        item = items[member_id]
        suffix = structural.get(member_id)
        direct = normalized(item["title"]) == normalized(title)
        if not (suffix or direct):
            continue
        for alias in item.get("aliases", []):
            if str(alias.get("kind") or "").upper() in {"SUBJECT", "SUBTITLE"}:
                continue
            candidate = alias["title"].strip() if direct else _alias_root(alias["title"], suffix)
            if not candidate or len(candidate) > 500 or not _meaningful(candidate):
                continue
            key = normalized(candidate)
            if key == normalized(title):
                continue
            entry = evidence.setdefault(key, {"forms": [], "members": {}})
            entry["forms"].append(candidate)
            source = {
                "canonicalTitle": item["title"], "sourceAlias": alias["title"],
                "language": alias.get("language"), "kind": alias.get("kind"),
                "rule": "direct_and_parallel" if direct else "parallel_structural_suffix",
            }
            previous = entry["members"].get(member_id)
            if previous is None or _alias_order(alias) < (
                previous["sourceAlias"], previous["language"] or "", previous["kind"] or ""
            ):
                entry["members"][member_id] = source
    if root_title:
        _subtitle_root_evidence(evidence, group, items, root_title)
    _numbered_root_evidence(evidence, group, items)
    # A declared variant set may share one exact alternative title across every
    # member. This is stronger than a franchise/collection alias seen on a subset.
    if group.get("groupType") == "variants" and len(members) >= 2 and any(
        normalized(items[mid]["title"]) == normalized(title) for mid in members
    ):
        shared: set[str] | None = None
        member_aliases: dict[str, dict[str, dict]] = {}
        for member_id in sorted(members):
            aliases: dict[str, dict] = {}
            for alias in items[member_id].get("aliases", []):
                if str(alias.get("kind") or "").upper() in {"SUBJECT", "SUBTITLE"}:
                    continue
                key = normalized(alias["title"])
                if key not in aliases or _alias_order(alias) < _alias_order(aliases[key]):
                    aliases[key] = alias
            member_aliases[member_id] = aliases
            shared = set(aliases) if shared is None else shared & set(aliases)
        for key in sorted(shared or set()):
            if key == normalized(title) or not _meaningful(key):
                continue
            entry = evidence.setdefault(key, {"forms": [], "members": {}})
            for member_id in sorted(members):
                alias = member_aliases[member_id][key]
                entry["forms"].append(alias["title"])
                entry["members"][member_id] = {
                    "canonicalTitle": items[member_id]["title"], "sourceAlias": alias["title"],
                    "language": alias.get("language"), "kind": alias.get("kind"),
                    "rule": "shared_declared_variant_alias",
                }
    result = []
    for key, entry in sorted(evidence.items()):
        sources = entry["members"]
        forms = entry["forms"]
        count = len(sources)
        minimum = len(members) if len(members) <= 2 else (
            math.ceil(len(members) * 0.67) if len(members) <= 5 else max(3, math.ceil(len(members) * 0.5))
        )
        if group.get("groupType") == "seasons":
            minimum = max(4, minimum)
        # A repeated title of an installment is not evidence of a group name.
        has_parallel = any(member_id in structural and source["rule"] ==
                           "parallel_structural_suffix"
                           for member_id, source in sources.items())
        subtitle_root = root_title is not None and any(
            source["rule"] == "parallel_subtitle_root" for source in sources.values())
        kind_root = root_title is not None and any(
            source["rule"] == "parallel_kind_root" for source in sources.values())
        shared_variant = all(source["rule"] == "shared_declared_variant_alias"
                             for source in sources.values())
        specific_extension = key.startswith(tuple(normalized(title) + separator
                                                  for separator in (":", " -", " —", "—", "–", " (")))
        member_specific = _member_specific_root(sorted(forms)[0], title)
        dangling_marker = (any(source["rule"] == "parallel_numbered_alias_root"
                               for source in sources.values())
                           and bool(_DANGLING_MARKER.search(sorted(forms)[0])))
        short_season = (group.get("groupType") == "seasons" and len(members) in (2, 3)
                        and count == len(members) and has_parallel)
        short_season_issue = (_short_season_issue(sorted(forms)[0], title, sources)
                              if group.get("groupType") == "seasons" else None)
        plausible_short_season = short_season and short_season_issue is None
        direct_root = (group.get("groupType") == "relations"
                       and group["id"].rsplit(":", 1)[-1] not in {"SPECIAL", "MOVIES"}
                       and count == 1
                       and next(iter(sources.values()))["rule"] == "direct_and_parallel"
                       and len(members) >= 2 and bool(structural)
                       and all(normalized(items[mid]["title"]) == normalized(title)
                               or mid in structural for mid in members))
        plausible_direct_root = direct_root and not _root_alias_is_specific(sorted(forms)[0], title)
        partial_seasons = (group.get("groupType") == "seasons" and count >= 2
                           and has_parallel and short_season_issue is None)
        plausible_subtitle_root = (subtitle_root and count >= max(2, minimum)
                                   and not _root_alias_is_specific(sorted(forms)[0], root_title))
        plausible_kind_root = (kind_root and count >= max(2, minimum)
                               and not _root_alias_is_specific(sorted(forms)[0], root_title))
        numbered_root = (count >= 2 and all(source["rule"] == "parallel_numbered_alias_root"
                                            for source in sources.values()))
        anchored_numbered_root = (
            group.get("groupType") == "relations" and count >= minimum
            and any(source["rule"] == "direct_and_parallel" for source in sources.values())
            and all(source["rule"] in {"direct_and_parallel", "parallel_numbered_alias_root"}
                    for source in sources.values())
            and all(normalized(items[mid]["title"]) == normalized(title) or mid in structural
                    for mid in members)
            and not _root_alias_is_specific(sorted(forms)[0], title)
        )
        status = "rejected" if specific_extension or member_specific or dangling_marker else (
            "accepted" if ((count >= minimum and (has_parallel or shared_variant))
                           or plausible_short_season or plausible_direct_root
                           or plausible_subtitle_root or plausible_kind_root
                           or partial_seasons or anchored_numbered_root)
                          and short_season_issue is None else "ambiguous"
        )
        language_set = {source["language"] for source in sources.values()}
        kind_set = {source["kind"] for source in sources.values()}
        result.append({
            "groupId": group["id"], "groupTitle": title,
            "alias": sorted(forms, key=lambda form: (len(form), normalized(form), form))[0],
            "normalized": key,
            "language": next(iter(language_set)) if len(language_set) == 1 else None,
            "kind": next(iter(kind_set)) if len(kind_set) == 1 else None,
            "support": count, "groupSize": len(members), "requiredSupport": minimum,
            "sources": [{"mediaId": media_id, **source} for media_id, source in sorted(sources.items())],
            "status": status,
            "reason": ("exact alias shared by every declared variant" if shared_variant else
                       "plausible parallel root of every short season" if plausible_short_season else
                       "declared alias of the shared canonical root" if plausible_direct_root else
                       "declared root alias corroborated by numbered members"
                       if anchored_numbered_root else
                       "parallel aliases of a shared subtitle root" if plausible_subtitle_root else
                       "parallel aliases of a declared set root" if plausible_kind_root else
                       "parallel alias of multiple seasons" if partial_seasons and count < minimum else
                       "parallel members and sufficient coverage") if status == "accepted" else (
                       "alias extends the canonical name with a possibly specific subtitle" if specific_extension else
                       "alias retains an installment marker absent from the group" if member_specific else
                       "alias ends with an unfinished installment marker" if dangling_marker else
                       short_season_issue if short_season_issue else
                       "parallel numbered root needs semantic review" if numbered_root else
                       "insufficient independent parallel evidence"),
        })
    accepted = [candidate for candidate in result if candidate["status"] == "accepted"]
    for candidate in accepted:
        if any(candidate is not other and (candidate["reason"] in _NEW_ALIAS_REASONS
               or other["reason"] not in _NEW_ALIAS_REASONS) and any(
            candidate["normalized"].startswith(other["normalized"] + separator)
            for separator in (":", " -", " —", "—", "–", " (")
        ) and not re.search(r"\(\d{4}\)$", candidate["normalized"])
               for other in accepted):
            candidate["status"] = "ambiguous"
            candidate["reason"] = "extends another supported group alias with a possible subtitle"
    return result


def resolve_collisions(groups: list[dict], candidates: list[dict]) -> list[dict]:
    titles = defaultdict(set)
    for group in groups:
        titles[normalized(group.get("displayTitle") or group["id"])].add(group["id"])
    compact_titles = defaultdict(set)
    for group in groups:
        compact = "".join(char for char in normalized(group.get("displayTitle") or group["id"])
                          if char.isalnum())
        compact_titles[compact].add(group["id"])
    claims = defaultdict(set)
    established_claims = defaultdict(set)
    for candidate in candidates:
        if candidate["status"] == "accepted":
            claims[candidate["normalized"]].add(candidate["groupId"])
            if candidate["reason"] not in _NEW_ALIAS_REASONS:
                established_claims[candidate["normalized"]].add(candidate["groupId"])
    for candidate in candidates:
        conflict = (claims[candidate["normalized"]] | titles[candidate["normalized"]]) - {candidate["groupId"]}
        if candidate["reason"] in _NEW_ALIAS_REASONS:
            compact = "".join(char for char in candidate["normalized"] if char.isalnum())
            conflict |= compact_titles[compact] - {candidate["groupId"]}
        candidate["collisions"] = sorted(conflict)
        established_conflict = (established_claims[candidate["normalized"]] |
                                titles[candidate["normalized"]]) - {candidate["groupId"]}
        if conflict and (candidate["reason"] in _NEW_ALIAS_REASONS
                         or established_conflict):
            candidate["status"] = "rejected"
            candidate["reason"] = "name also identifies another group"
    return candidates
