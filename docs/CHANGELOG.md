# Changelog

This file records user-visible changes. Development builds may contain additional fixes
before a release is published.

## 0.2.3

- Search for groups by alternative titles, including supported translations. Matching titles appear directly in search results.
- Improved group names for seasons, editions and specials; separated an incorrectly combined group of specials.
- Update installed catalog packs from a new update prompt or Pack settings while keeping personal ratings and preferences.
- Start a group rating from its members’ ratings or from scratch. Derived ratings reappear when an explicit group rating is removed, and the most-used member profile is selected automatically.
- Open group details and full filter values from the subtitles in the enlarged cover view.
- A smaller welcome screen lets new users choose a rating mode while each pack keeps its own default cover setting.
- Improved package names and update detection, including support for packs installed with 0.2.2.
- Fixed smaller issues in search, package installation, onboarding and the notes editor.

## 0.2.1 & 0.2.2

- Rebuilt the application around validated, downloadable catalog packs. The engine handles fields, filters, groups, rating categories, profiles, cover sources and attribution declared by each pack.
- Added RankOffline All, paged Search and MyRanking, and a five-view session cache with independent list positions.
- Added declarative cover downloads with source-specific HTTPS restrictions, provider rate limits, group-cover fallback and visible batch progress.
- Added editable local rating categories and profiles, personal JSON backup and restore, and a one-time import of personal ratings from the published 0.2.0 app.
- Added a package index, WorkManager installation and recovery, and local READY verification after restart.
- Updated sharing, themes, onboarding and package settings. Pack and personal data remain separate so ordinary uninstall retains ratings.

## 0.2.0

- Published the earlier catalog and rating application. Its data format and database are distinct from the current pack-driven engine.

The `.pack` manifest and personal backup formats have independent version numbers; see
[PACK_FORMAT.md](PACK_FORMAT.md).
