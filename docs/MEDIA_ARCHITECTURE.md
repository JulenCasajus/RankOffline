# Unified media architecture

RankOffline's five official packs share one Android runtime model. This document records the implemented 0.2 design; it is no longer the earlier transitional Anime-versus-generic plan.

| PackId | persisted media value | canonical item identity | merge group |
|---|---|---|---|
| `animes` | `anime` | existing `mal:*` / `anilist:*` ID | explicit Anime relation family |
| `mangas` | `manga` | `manga:mangadex:<uuid>` | explicit variant component |
| `movies` | `movie` | `movie:tmdb:<id>` | TMDB collection ID |
| `series` | `series` | `series:tmdb:<show>:season:<n>` | TMDB parent show ID |
| `books` | `book` | `book:openlibrary:<WorkOLID>` | conservative explicitly verified Book-series groups |

## Shared runtime

`GenericMediaCatalogImporter` streams every normalized catalog into `media_item` plus common genre, credit, and alias tables. `GenericMediaGroupCatalogImporter` imports every merge-capable pack's precomputed group graph into `media_group` and `media_group_member`. `GenericMediaRepository` and `GenericMediaGroupRepository` implement search, filters, pagination, ranking, item/group ratings, and details for all five media types.

Anime's old tables and importers remain for non-destructive migration from stable 0.1.3 and for migration-focused tests. They are not the active 0.2 catalog/search/rating engine.

## Ownership and identity

Personal rows are PackId-scoped. Catalog rows currently retain canonical ID as a globally unique primary key, so a future third-party pack must not reuse a canonical item/group ID owned by another installed pack. The external installation UI is not implemented yet. Before supporting arbitrary same-provider third-party packs, catalog/group keys must become composite `(pack_id, canonical_id)` through a tested forward migration, or installation must reject cross-pack collisions explicitly. Rating rows already use the composite form.

Runtime code receives explicit media/pack ownership where available. Prefix decoding remains only in migration and detached-legacy compatibility paths; canonical IDs are not renamed.

## User-data rules

Normal catalog replacement updates reference fields while preserving ratings, detailed scores, notes, status, profile IDs, and downloaded cover paths. An explicit **Delete pack and data** action is different: it purges all state owned by that PackId. There is no destructive Room fallback.

## Offline behavior

Installed and imported packs are fully browsable, searchable, filterable, mergeable, rateable, and rankable without network access. Only explicit/user-enabled cover retrieval (including installation-time Top-100) and archive
installation may contact a provider; online title discovery has been removed. Switching Merge
is a read-only local SQL operation. Series groups share the first ordered member's cover where
One Cover per Group is enabled.

A proposed 0.2.1 `RankOfflineAll` would be a virtual combined viewing scope, not an installable
sixth pack or a separate rating namespace. It is not part of 0.2.
