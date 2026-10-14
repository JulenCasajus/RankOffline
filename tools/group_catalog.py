"""Conservative, reproducible format groups from explicit AniList relations."""
from __future__ import annotations

from collections import defaultdict
from datetime import date

FAMILY_RELATIONS = {"PREQUEL", "SEQUEL", "PARENT", "SIDE_STORY"}
EXCLUDED_FAMILY_RELATIONS = {
    "ALTERNATIVE", "CHARACTER", "OTHER", "SAME_UNIVERSE", "SPIN_OFF", "SUMMARY",
}
DERIVATIVE_RELATIONS = {"ALTERNATIVE", "SPIN_OFF", "SUMMARY"}
SERIES_FORMATS = {"TV", "TV_SHORT", "ONA"}
NORMALIZED_FORMATS = {
    **{value: "SERIES" for value in SERIES_FORMATS},
    "MOVIE": "MOVIES",
    "OVA": "SPECIAL",
    "SPECIAL": "SPECIAL",
    "MUSIC": "MUSIC",
}


def normalized_format(raw_format: object) -> str | None:
    if not isinstance(raw_format, str) or not raw_format.strip():
        return None
    return NORMALIZED_FORMATS.get(raw_format.strip().upper())


def chronological_key(item: dict) -> tuple:
    start = item.get("startDate") or {}
    year, month, day = (start.get(part) for part in ("year", "month", "day"))
    try:
        complete = date(year, month, day).isoformat()
    except (TypeError, ValueError):
        complete = None
    return (
        0 if complete else 1,
        complete or "",
        item.get("seasonYear") or year or 9999,
        (item.get("title") or "").casefold(),
        item["id"],
    )


def is_single_episode_short_series(item: dict) -> bool:
    """True for standalone short-form entries that must not define a season chain."""
    raw_format = str(item.get("format") or "").strip().upper()
    return raw_format in {"ONA", "TV_SHORT"} and item.get("episodes") == 1


def adaptation_ids(item: dict) -> set[int]:
    return {
        edge["anilistId"]
        for edge in item.get("relations") or []
        if edge.get("type") == "ADAPTATION" and isinstance(edge.get("anilistId"), int)
    }


def has_conflicting_adaptation_sources(source: dict, target: dict) -> bool:
    if normalized_format(source.get("format")) != "SERIES":
        return False
    if normalized_format(target.get("format")) != "SERIES":
        return False
    source_adaptations = adaptation_ids(source)
    target_adaptations = adaptation_ids(target)
    return bool(source_adaptations and target_adaptations and source_adaptations.isdisjoint(target_adaptations))


def is_safe_mainline_edge(
    relation_type: str,
    source: dict,
    target: dict,
    pair_relation_types: set[str] | None = None,
) -> bool:
    if (
        pair_relation_types
        and "SIDE_STORY" in pair_relation_types
        and normalized_format(source.get("format")) == "SERIES"
        and normalized_format(target.get("format")) == "SERIES"
    ):
        return False
    if relation_type not in {"PREQUEL", "SEQUEL"}:
        return True
    source_format = str(source.get("format") or "").strip().upper()
    target_format = str(target.get("format") or "").strip().upper()
    crosses_series_format = source_format != target_format
    return not (
        crosses_series_format
        and (is_single_episode_short_series(source) or is_single_episode_short_series(target))
    )


def assign_groups(catalog: list[dict]) -> dict[str, object]:
    """Annotate groups as family root + normalized format; singleton buckets stay individual."""
    by_id = {item["id"]: item for item in catalog}
    anilist_ids: dict[int, list[str]] = defaultdict(list)
    for item in catalog:
        for key in ("groupId", "groupFormat", "groupFamilyRootId", "groupDisplayTitle", "groupCoverAnimeId"):
            item.pop(key, None)
        relation_id = item.get("anilistId") or item.get("relationAniListId")
        if isinstance(relation_id, int):
            anilist_ids[relation_id].append(item["id"])

    adjacency: dict[str, set[str]] = defaultdict(set)
    predecessors: dict[str, set[str]] = defaultdict(set)
    unresolved = 0
    ambiguous_parent_edges = 0
    excluded_pair_edges = 0
    short_form_mainline_edges = 0
    conflicting_adaptation_edges = 0
    derivative_node_edges = 0
    resolved_by_source: dict[str, list[tuple[str, str]]] = defaultdict(list)
    relation_types_by_pair: dict[frozenset[str], set[str]] = defaultdict(set)
    derivative_candidates: list[tuple[str, str]] = []

    for item in catalog:
        source = item["id"]
        for edge in item.get("relations") or []:
            relation_type = edge.get("type")
            targets = anilist_ids.get(edge.get("anilistId"), [])
            if len(targets) != 1 or targets[0] == source:
                if relation_type in FAMILY_RELATIONS:
                    unresolved += 1
                continue
            target = targets[0]
            relation_types_by_pair[frozenset((source, target))].add(relation_type)
            if relation_type in DERIVATIVE_RELATIONS:
                derivative_candidates.append((source, target))
            if relation_type in FAMILY_RELATIONS:
                resolved_by_source[source].append((relation_type, target))

    # Direction matters: A --SUMMARY/SPIN_OFF--> B together with
    # B --PARENT--> A identifies B as the derivative. Requiring the reverse
    # PARENT avoids globally excluding a main anime merely because some other
    # entry happens to label it ALTERNATIVE.
    derivative_nodes = {
        target
        for source, target in derivative_candidates
        if ("PARENT", source) in resolved_by_source[target]
    }

    for item in catalog:
        source = item["id"]
        resolved_edges = resolved_by_source[source]
        # A work can name multiple parents when it is a crossover, anthology,
        # anniversary reel, or compilation. Treating those parent links as an
        # undirected family bridge can incorrectly join otherwise unrelated
        # franchises (the Annecy anniversary reel joined One Piece and Dragon
        # Ball this way). A single PARENT remains useful and unambiguous; a
        # multi-parent source is deliberately left unattached rather than risk
        # merging every parent family.
        parent_targets = {
            target for relation_type, target in resolved_edges
            if relation_type == "PARENT"
        }
        ambiguous_parent = len(parent_targets) > 1

        for relation_type, target in resolved_edges:
            if relation_type == "PARENT" and ambiguous_parent:
                ambiguous_parent_edges += 1
                continue
            pair_relation_types = relation_types_by_pair[frozenset((source, target))]
            if pair_relation_types.intersection(EXCLUDED_FAMILY_RELATIONS):
                excluded_pair_edges += 1
                continue
            if source in derivative_nodes or target in derivative_nodes:
                derivative_node_edges += 1
                continue
            if (
                relation_type in {"PREQUEL", "SEQUEL"}
                and has_conflicting_adaptation_sources(item, by_id[target])
            ):
                conflicting_adaptation_edges += 1
                continue
            if not is_safe_mainline_edge(relation_type, item, by_id[target], pair_relation_types):
                short_form_mainline_edges += 1
                continue
            adjacency[source].add(target)
            adjacency[target].add(source)
            if relation_type == "SEQUEL":
                predecessors[target].add(source)
            elif relation_type in {"PREQUEL", "PARENT"}:
                predecessors[source].add(target)

    def root_of(component: set[str]) -> str:
        candidates = [item_id for item_id in component if not predecessors[item_id].intersection(component)]
        pool = candidates or list(component)
        series = [item_id for item_id in pool if normalized_format(by_id[item_id].get("format")) == "SERIES"]
        return min(series or pool, key=lambda item_id: chronological_key(by_id[item_id]))

    seen: set[str] = set()
    group_counts: dict[str, int] = defaultdict(int)
    grouped_members = 0
    for first in sorted(by_id):
        if first in seen or first not in adjacency:
            continue
        component = {first}
        queue = [first]
        seen.add(first)
        while queue:
            current = queue.pop()
            for target in adjacency[current]:
                if target not in seen:
                    seen.add(target)
                    component.add(target)
                    queue.append(target)
        family_root = root_of(component)
        buckets: dict[str, list[str]] = defaultdict(list)
        for anime_id in component:
            bucket = normalized_format(by_id[anime_id].get("format"))
            if bucket is not None:
                buckets[bucket].append(anime_id)
        for bucket, members in buckets.items():
            if len(members) < 2:
                continue
            ordered = sorted(members, key=lambda anime_id: chronological_key(by_id[anime_id]))
            representative = ordered[0]
            group_id = f"group:{family_root}:{bucket}"
            for anime_id in members:
                by_id[anime_id].update({
                    "groupId": group_id,
                    "groupFormat": bucket,
                    "groupFamilyRootId": family_root,
                    "groupDisplayTitle": by_id[representative]["title"],
                    "groupCoverAnimeId": representative,
                })
            group_counts[bucket] += 1
            grouped_members += len(members)

    return {
        "groups": sum(group_counts.values()),
        "groupsByFormat": dict(sorted(group_counts.items())),
        "groupedMembers": grouped_members,
        "unresolvedRelations": unresolved,
        "ambiguousParentEdgesSkipped": ambiguous_parent_edges,
        "excludedPairEdgesSkipped": excluded_pair_edges,
        "derivativeNodeEdgesSkipped": derivative_node_edges,
        "conflictingAdaptationEdgesSkipped": conflicting_adaptation_edges,
        "shortFormMainlineEdgesSkipped": short_form_mainline_edges,
    }
