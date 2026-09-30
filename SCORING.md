# Scoring model

For applicable categories C:

`base = sum(score[c] * weight[c]) / sum(weight[c])`

`final = min(10, base + taste_bonus)`

Where `taste_bonus` is from `0.0` to `1.0`.

Weights:

- writing: 0.35
- characters: 0.25
- engagement: 0.20
- visuals: 0.15
- worldbuilding: 0.05

The final rating is intentionally capped at 10.0. Personal taste is displayed separately so a user can see both objective-ish execution score and subjective enjoyment bonus.
