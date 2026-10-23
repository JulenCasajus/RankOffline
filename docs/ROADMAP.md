# RankOffline roadmap

This is a direction list, not a release commitment.

## After 0.2 stabilization

- Finish the external data-only pack acquisition UI after catalog identities can safely reject or namespace collisions between independently authored packs.
- Add import/export and optional sharing for custom palettes and personal data without introducing an account requirement.
- Expand provider-backed rating import interoperability.
- Improve legally redistributable/prepackaged cover coverage where provider terms permit it.
- Continue accessibility, large-font, and small-viewport validation across all nine UI languages.

## Invariants

- local-first and usable offline after pack installation;
- no executable plugins;
- no destructive database fallback;
- provider relationships precomputed by pack tooling, never inferred on the phone;
- personal data scoped by PackId and preserved by normal catalog updates.
