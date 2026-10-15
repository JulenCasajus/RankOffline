# Third-party software and data notices

This document records the provenance and licence boundaries of components and
data used by RankOffline. It is not legal advice.

## A. Application software

RankOffline application source code is licensed under
[GPL-3.0-only](LICENSE). Bundled databases, provider content, remote services,
and third-party trademarks remain subject to their own terms and are not
relicensed by the application source-code licence.

The distributed runtime uses AndroidX components including Jetpack Compose,
Room, DataStore, Activity, AppCompat, and Lifecycle, plus Kotlin/Kotlinx
components. The applicable components are distributed under the
[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0). Complete
GPL-3.0 and Apache-2.0 texts are packaged in `assets/legal/`.

## B. Bundled databases and metadata sources

- [AnimeAPI by nattadasu](https://github.com/nattadasu/animeApi) supplies the
  Anime catalog foundation and provider mappings. Its software is MIT; its
  compiled database is identified as ODbL 1.0 with contents under DbCL 1.0,
  while some raw source components are CC0 1.0. AnimeAPI requires database
  users to credit AnimeAPI and Anime Offline Database.
- [cedya77/anime-offline-database](https://github.com/cedya77/anime-offline-database),
  continuing the original
  [manami-project/anime-offline-database](https://github.com/manami-project/anime-offline-database),
  supplies title aliases only from pinned release `2026-42`. The separately
  bundled derived alias database is under ODbL 1.0, with contents under DbCL
  1.0.
- [AniList](https://anilist.co) supplies selected Anime metadata, explicit
  relations, popularity, and primary cover metadata. Its known collection and
  competing-service restrictions remain unresolved for release; attribution
  does not grant broader permission or imply endorsement.
- [MangaDex](https://mangadex.org) supplies Manga metadata, aliases, tags,
  creators, conservative variant relations, and cover references. RankOffline
  does not provide chapter reading or distribute scanlation content. Its
  current acceptable-use policy requires credit and must be reassessed before
  adding advertising or paid service features.
- [TMDB](https://www.themoviedb.org) supplies Movie and Series metadata,
  grouping metadata, alternative titles, networks/companies, and poster
  references. Current use is non-commercial; commercial use requires a
  separate licensing review/contact with TMDB.
- [Open Library](https://openlibrary.org) supplies Work metadata and author
  records from bulk dumps. Contributions are generally offered under CC0 where
  contributors can make that dedication, but underlying rights can remain.
  This notice does not characterize all Open Library content as public domain.

The ODbL, DbCL, and CC0 licence references are:

- [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/)
- [DbCL 1.0](https://opendatacommons.org/licenses/dbcl/1-0/)
- [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/)

## C. Remote image and data services

- Existing Anime cover fallback may use [Jikan](https://jikan.moe), an
  unofficial MyAnimeList API. Neither Jikan nor MyAnimeList endorses
  RankOffline.
- TMDB poster images use the approved TMDB image host when the user explicitly
  downloads a cover.
- Manga covers use MangaDex's cover service when explicitly requested.
- Book covers are retrieved through the Open Library Covers service. Cover-image
  rights can vary; they are not claimed to be CC0 or public domain.

No personal ratings or notes are sent to these providers.

## D. Trademarks and non-endorsement

Provider names and marks belong to their respective owners. Attribution
identifies sources and does not imply affiliation, certification, or
endorsement. TMDB specifically requires this notice:

> This product uses the TMDB API but is not endorsed or certified by TMDB.

The official RankOffline name, logo, launcher icons, and distinctive branding
are governed separately by [TRADEMARKS.md](TRADEMARKS.md).
