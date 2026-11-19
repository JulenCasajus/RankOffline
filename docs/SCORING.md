# Scoring

RankOffline calculates scores on a 0–10 scale. Packs declare category names,
descriptions, colours, default weights and profiles. They cannot supply executable
scoring formulas. Users may edit local category and profile definitions without changing
the installed manifest.

For active, applicable categories with positive weight, quality is the weighted mean:

```text
quality = sum(category score × weight) / sum(applicable weight)
```

Missing or inapplicable categories are excluded and the remaining weights are
renormalized. Invalid or non-finite values do not produce a calculated score. The
optional personal-taste bonus is an application rule in the range 0–1:

```text
final = clamp(quality + personal-taste bonus, 0, 10)
```

Personal taste is off by default in the editor. Disabling it affects a newly saved
rating; existing stored ratings are not silently recalculated when the preference
changes. Rating inputs snap to the configured steps: 0.5 for categories, 0.1 for the
bonus and 5 for weights. Internal scores retain calculation precision; UI formatting
does not change stored values or ranking order.

A group draft can start from the valid ratings of its members in the same pack or from
scratch. When starting from members, the most-used profile among those valid ratings
is selected; ties prefer `general`, then the lowest profile ID. A missing profile falls
back to an available one. An explicit group rating takes precedence over the member
average; removing it reveals the average again without changing member ratings. Group
and item ratings remain separate records. Reset Default restores the installed pack's
category and profile definitions, not a hardcoded set of official presets.
