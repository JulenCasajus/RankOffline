# RankOffline scoring model

RankOffline has one canonical saved score per anime: `RatingEntity.finalScore`.

Simple and elaborate rating are two ways to write that same score. Switching modes does not create parallel rankings or silently recalculate saved anime.

## Simple rating

Simple mode uses a discrete slider:

- range: `0.0 ... 10.0`
- step: `0.5`
- 21 possible values

Saving in simple mode writes the chosen value directly to `finalScore`.

Detailed category scores and the last elaborate profile selection are preserved, so switching to simple mode does not destroy elaborate-rating data.

## Elaborate rating

Elaborate mode separates:

1. **Category scores** — how well the work executes each applicable aspect.
2. **Rating profile** — how important each category is for this particular work.
3. **Personal-taste bonus** — a small subjective adjustment kept separate from the weighted quality score.

This avoids forcing every anime to be judged with identical category importance.

### Default categories

| Category | General weight |
| --- | ---: |
| Writing | 35% |
| Characters | 20% |
| Visuals | 20% |
| Worldbuilding | 15% |
| Audio | 10% |

Category scores use a `0.0 ... 10.0` range in 0.5-point steps.

Category IDs are stable. Names, descriptions, and colors may be edited without breaking profiles.

## Rating profiles

A profile maps stable category IDs to weights. Users may edit the built-in profiles or create their own.

Default profiles:

| Profile | Writing | Characters | Visuals | Worldbuilding | Audio |
| --- | ---: | ---: | ---: | ---: | ---: |
| General | 35% | 20% | 20% | 15% | 10% |
| Narrative | 45% | 25% | 10% | 10% | 10% |
| Character-driven | 25% | 40% | 15% | 10% | 10% |
| Visual / Action | 20% | 15% | 40% | 15% | 10% |
| World / Adventure | 20% | 20% | 15% | 35% | 10% |
| Atmospheric | 25% | 20% | 20% | 15% | 20% |

Profile weights are edited from 0% to 100% in 5-point steps. They do not have to sum to exactly 100%; applicable weights are normalized during calculation.

New custom categories start at 0% in existing profiles. Deleting a category removes its profile-weight references as well.

## N/A categories

A category may be marked N/A when it is not meaningfully applicable to a work.

N/A is not the same as a score of 0:

- `0.0` = applicable and intentionally rated at the minimum.
- `N/A` / null = excluded from the weighted calculation.

For the applicable category set `C`:

```text
quality = sum(score[c] * weight[c]) / sum(weight[c])
```

Only categories with a valid score and a positive applicable weight participate. The calculator guards empty/all-zero configurations so it never produces NaN or Infinity.

## Personal-taste bonus

The personal-taste bonus is:

```text
+0.0 ... +1.0
```

in 0.1-point steps.

For elaborate ratings:

```text
final = min(10, quality + personal_taste)
```

The bonus is intentionally separate from profile weights.

## Persistence and recalculation

When an elaborate rating is saved:

- category scores are stored in `rating_score`;
- the selected profile ID is associated with that anime;
- the calculated result is written to the canonical `finalScore`;
- notes remain shared between simple and elaborate modes.

Editing a profile later does **not** recalculate every anime that previously used it. The stored final score changes only when that anime is explicitly saved again.

If a saved profile is later deleted, the editor falls back safely to General when available, otherwise to the first available profile. The previously saved `finalScore` remains unchanged until the user saves again.

## Reset behavior

Category and profile resets are intentionally independent:

- **Reset categories** restores the official categories, descriptions, colors, and order.
- **Reset profiles** restores the official profiles and weights without replacing the current category set.

Custom categories that exist when profiles are reset receive 0% in the restored profiles. Reset operations do not erase anime final scores or notes.
