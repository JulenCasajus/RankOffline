# RankOffline roadmap

This is a direction list, not a release commitment.

## Proposed 0.2.1: RankOfflineAll

- Add a **virtual** combined Search/Ranking view over every installed READY PackId;
  it is not a sixth installable catalog and stores no duplicate ratings.
- Show All first in Choose Category only when at least two packs are installed/ready;
  hide it at zero or one pack and exclude it from Package Management.
- Interleave per-pack popularity ranks for global popularity ordering; use true
  global A–Z sorting and combined rated-item rankings. Open each item with its
  own originating pack's scoring rules, identity and settings.
- Keep independent All view/display preferences while leaving the underlying
  per-pack rating systems untouched. Design for future arbitrary PackIds.
- Analyze paged SQL/group performance and existing global uniqueness constraints
  before implementation. No All code is required for the 0.2 stable release.

## After 0.2 stabilization

- Eventually support a searchable community pack registry/server and external data-only pack
  acquisition UI, after catalog identities can safely reject or namespace collisions between
  independently authored packs. Never execute pack-supplied code.
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
