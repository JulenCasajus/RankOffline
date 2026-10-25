# RankOffline scoring model

RankOffline stores one canonical personal rating for each item or explicit group, scoped by its pack identity. Anime, Manga, Movies, Series, and Books use the same rating engine. Simple and elaborate rating are two ways to update that rating; switching modes does not create a parallel ranking or recalculate previously saved results.

## Simple and elaborate ratings

Simple mode writes a value from `0.0` to `10.0` in 0.5-point steps directly as the final score. Existing detailed category scores and the last elaborate profile remain stored.

Elaborate mode combines category scores, a rating profile containing weights keyed by stable category ID, and an optional personal-taste bonus from `+0.0` to `+1.0`.

For the applicable category set `C`:

```text
quality = sum(score[c] * weight[c]) / sum(weight[c])
final = min(10, quality + personal_taste)
```

`0.0` is a valid score. N/A is stored separately and excludes that category from the weighted calculation. Empty and all-zero applicable configurations never produce NaN or Infinity.

## Built-in categories and General weights

Users can edit or replace these defaults. IDs are the stable keys used by profiles and detailed scores.

| Pack | Categories in order (ID: General weight) |
| --- | --- |
| Anime | Writing (`writing`: 35%), Characters (`characters`: 20%), Visuals (`visuals`: 20%), Worldbuilding (`worldbuilding`: 15%), Audio (`audio`: 10%) |
| Manga | Writing (`writing`: 35%), Characters (`characters`: 25%), Art (`art`: 25%), Worldbuilding (`worldbuilding`: 15%) |
| Movies | Writing (`writing`: 35%), Characters (`characters`: 25%), Visuals (`visuals`: 20%), Staging / Immersion (`staging_immersion`: 10%), Audio (`audio`: 10%) |
| Series | Writing (`writing`: 35%), Characters (`characters`: 25%), Visuals (`visuals`: 10%), Worldbuilding (`worldbuilding`: 20%), Audio (`audio`: 10%) |
| Books | Writing (`writing`: 35%), Characters (`characters`: 25%), Prose / Style (`prose`: 15%), Worldbuilding (`worldbuilding`: 15%), Pacing / Structure (`structure`: 10%) |

Manga Art includes drawing, design, backgrounds, panel composition, flow, clarity, and visual storytelling. Movies Staging / Immersion covers casting, performances, staging, direction, emotional impact, and engagement.

## Built-in profiles

Every built-in profile totals 100%. Resetting profiles recreates these weights for the current category set; a custom category not present in a preset starts at 0%.

### Anime

| Profile (ID) | Writing | Characters | Visuals | Worldbuilding | Audio |
| --- | ---: | ---: | ---: | ---: | ---: |
| General (`general`) | 35 | 20 | 20 | 15 | 10 |
| Narrative (`narrative`) | 45 | 25 | 10 | 10 | 10 |
| Character-driven (`character_driven`) | 25 | 40 | 15 | 10 | 10 |
| Visual / Action (`visual_action`) | 20 | 15 | 40 | 15 | 10 |
| World / Adventure (`world_adventure`) | 20 | 20 | 15 | 35 | 10 |
| Atmospheric (`atmospheric`) | 25 | 20 | 20 | 15 | 20 |

### Manga

| Profile (ID) | Writing | Characters | Art | Worldbuilding |
| --- | ---: | ---: | ---: | ---: |
| General (`general`) | 35 | 25 | 25 | 15 |
| Narrative (`narrative`) | 50 | 25 | 15 | 10 |
| Character-driven (`character_driven`) | 25 | 50 | 15 | 10 |
| Art-focused (`art_focused`) | 20 | 20 | 50 | 10 |
| World / Adventure (`world_adventure`) | 25 | 20 | 15 | 40 |

### Movies

| Profile (ID) | Writing | Characters | Visuals | Staging / Immersion | Audio |
| --- | ---: | ---: | ---: | ---: | ---: |
| General (`general`) | 35 | 25 | 20 | 10 | 10 |
| Narrative (`narrative`) | 40 | 20 | 10 | 20 | 10 |
| Character-driven (`character_driven`) | 25 | 40 | 10 | 15 | 10 |
| Visual (`visual_direction`) | 15 | 15 | 40 | 15 | 15 |
| Atmospheric (`atmospheric`) | 15 | 15 | 25 | 20 | 25 |

### Series

| Profile (ID) | Writing | Characters | Visuals | Worldbuilding | Audio |
| --- | ---: | ---: | ---: | ---: | ---: |
| General (`general`) | 35 | 25 | 10 | 20 | 10 |
| Narrative (`narrative`) | 50 | 15 | 10 | 15 | 10 |
| Character-driven (`character_driven`) | 20 | 50 | 10 | 10 | 10 |
| Visual (`visual_direction`) | 20 | 15 | 40 | 10 | 15 |
| World / Adventure (`world_adventure`) | 20 | 20 | 10 | 40 | 10 |
| Atmospheric (`atmospheric`) | 20 | 20 | 25 | 10 | 25 |

### Books

| Profile (ID) | Writing | Characters | Prose / Style | Worldbuilding | Pacing / Structure |
| --- | ---: | ---: | ---: | ---: | ---: |
| General (`general`) | 35 | 25 | 15 | 15 | 10 |
| Narrative (`narrative`) | 45 | 20 | 15 | 10 | 10 |
| Character-driven (`character_driven`) | 25 | 40 | 15 | 10 | 10 |
| Prose / Style (`prose_style`) | 20 | 10 | 45 | 10 | 15 |
| World / Adventure (`world_adventure`) | 20 | 15 | 15 | 40 | 10 |
| Atmospheric (`atmospheric`) | 20 | 20 | 20 | 20 | 20 |

## Persistence, migration, and resets

Detailed scores, final score, selected profile ID, notes, status, and bonus are durable user data. Editing a profile does not retroactively change saved ratings; a stored final score changes only when the user explicitly saves that rating again. If a referenced profile no longer exists, the editor falls back to General, then to the first available profile, without rewriting the saved score or historical category rows.

The debug.6 preset update migrates a pack only when its complete category and profile blocks exactly match the previous factory defaults. Any customized name, description, color, category, profile, or weight preserves the existing configuration. Removed factory category IDs can therefore remain in historical detailed-score rows without being shown in a fresh editor, and no score is inferred for a newly introduced category.

Category and profile resets are independent:

- **Reset categories** restores official category IDs, descriptions, colors, order, and General weights while reconciling the current profiles.
- **Reset profiles** restores official profile IDs and weights for the current categories.

Neither operation deletes final ratings, notes, status, or rating history.

## Group ratings

An explicit precomputed media group can have its own rating, stored independently from member ratings. If no explicit group rating exists, ranking may present an in-memory aggregate of rated members. That derived aggregate is not persisted as the group's official score and never rewrites member ratings.
