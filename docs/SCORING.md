# Scoring

RankOffline owns a fixed 0–10 score scale. A pack supplies generic categories
and profiles; it does not supply executable formulas.

For active applicable categories with positive weight:

`quality = Σ(score × weight) / Σ(applicable weight)`

Missing or not-applicable categories are excluded and remaining weights are
renormalized. Invalid/non-finite values produce no calculated score. The
app-owned personal-taste bonus is constrained to 0–1 and the final result is:

`final = clamp(quality + bonus, 0, 10)`

Editor values snap to configured steps (rating 0.5, bonus 0.1, weights 5).
Changing criteria, weights, profile or taste is an explicit recalculation.
Editing only notes/status preserves stored final score, quality, bonus, profile
and category values.

Group drafts can derive means from valid rated members of one pack. This is a
starting representation and never replaces item identities.
