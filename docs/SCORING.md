# Scoring

RankOffline supports two rating styles that both produce one canonical saved final score.

## Simple rating

Choose a score from **0.0 to 10.0** in **0.5** steps.

The selected value becomes the saved final score.

## Elaborate rating

Elaborate mode combines:

1. category scores;
2. a weighting profile;
3. an optional personal-taste bonus.

For applicable categories:

```text
quality = sum(score × weight) / sum(weight)
```

Categories marked **N/A** are excluded.

The personal-taste bonus is then added and the final result is capped at 10:

```text
final = min(10, quality + personal_taste)
```

## Categories and profiles

Categories define **what** is rated.

Profiles define **how much each category matters**.

Users can customize both. Stable category IDs are used internally so renaming or recoloring a category does not break profile relationships.

## Persistence

Switching between simple and elaborate mode does not create separate rankings.

Existing elaborate details can remain stored even when the user saves a simple score.

Editing a profile later does not silently recalculate previously saved ratings. A final score changes only when the user explicitly saves that item again.

## Principle

Catalog updates, category-pack removal, and application upgrades must preserve user-owned rating data unless the user explicitly chooses a destructive delete action.
