# Scoring

RankOffline produces one saved score for each rated entry.

## Simple mode

Choose a 0–10 score (typically in 0.5 steps). It becomes the final score.

## Elaborate mode

Define categories and weighting profiles. Mark irrelevant categories N/A; they are excluded from the weighted mean:

```text
quality = sum(category score × category weight) / sum(applicable weights)
final   = min(10, quality + personal-taste bonus)
```

The optional bonus is separate from quality. Profiles decide how much each category matters; categories decide what you rate. Stable category IDs allow renaming/recoloring without silently breaking a profile.

Changing rating mode or profile does not silently rewrite previously saved final scores. Editing and saving a rating persists the canonical score and any applicable elaborated details in Room.

## Data safety

Normal catalog updates and recovery should preserve personal ratings and notes. The deliberate **Delete pack** action is different: in the v0.2.0 design, it explicitly purges that pack's ratings, notes, cover cache and pack-specific settings after confirmation. Back up anything important before deleting a pack.
