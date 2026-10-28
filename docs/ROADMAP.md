# RankOffline roadmap

This is a direction list, not a release commitment.

## 0.2.1 development scope

- A **virtual** combined Search/Ranking view over every installed READY PackId is implemented;
  it is not a sixth installable catalog and stores no duplicate ratings.
- Show All first in Choose Category only when at least two packs are installed/ready;
  hide it at zero or one pack and exclude it from Package Management.
- Interleave per-pack popularity ranks for global popularity ordering; use true
  global A–Z sorting and combined rated-item rankings. Open each item with its
  own originating pack's scoring rules, identity and settings.
- Keep independent All view/display preferences while leaving the underlying
  per-pack rating systems untouched. Design for future arbitrary PackIds.
- Popularity interleaving and global alphabetical ordering preserve source identity and use bounded
  per-pack cursors. Combined rated ordering remains bounded by the user's rated library and should be
  profiled before broadening the feature to arbitrary community packs.
- Pack install/recovery runs as persistent WorkManager jobs with per-PackId cancellation.
- Storage settings distinguish measured pack files from logical shared-Room estimates.
- Pack removal offers both uninstall-with-personal-data and permanent deletion.
- Personal ratings/settings/themes can be exported, imported or shared through a versioned local
  file in Settings → Packages. The file is unencrypted and excludes catalogs and cover binaries.
- Personal rankings can be previewed and shared separately as offline-rendered 1080×1920 PNG cards
  using Top 7 or Top 10 layouts, with optional local covers and optional integrity-declared pack
  background artwork. This visual export never includes notes or backup/settings data.
- Pack-scoped external-ID mapping, rating-scale conversion and dry-run conflict reporting provide a
  connector boundary; no OAuth, polling or provider synchronization is enabled.

## After 0.2 stabilization

- Eventually support a searchable community pack registry/server and external data-only pack
  acquisition UI, after catalog identities can safely reject or namespace collisions between
  independently authored packs. Never execute pack-supplied code.
- Extend the local backup format only through explicit schema versions; optional palette-only
  sharing remains future work.
- Expand provider-backed rating import interoperability.
- Improve legally redistributable/prepackaged cover coverage where provider terms permit it.
- Continue accessibility, large-font, and small-viewport validation across all nine UI languages.

## Invariants

- local-first and usable offline after pack installation;
- no executable plugins;
- no destructive database fallback;
- provider relationships precomputed by pack tooling, never inferred on the phone;
- personal data scoped by PackId and preserved by normal catalog updates.
