package org.rankoffline.app

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/** Builds bounded Room queries; values are bound and sort expressions are fixed enum choices. */
internal object GroupedQueries {
    fun catalog(
        query: String,
        sortOrder: CatalogSortOrder,
        filters: AnimeFilters,
        limit: Int,
        offset: Int = 0
    ): SupportSQLiteQuery {
        val args = mutableListOf<Any?>()
        val base = visibleRows(query, filters, "", args)
        val order = when (sortOrder) {
            CatalogSortOrder.POPULARITY ->
                "CASE WHEN popularityRank IS NULL THEN 1 ELSE 0 END, popularityRank ASC, title COLLATE NOCASE ASC, id ASC"
            CatalogSortOrder.POPULARITY_REVERSE ->
                "CASE WHEN popularityRank IS NULL THEN 1 ELSE 0 END, popularityRank DESC, title COLLATE NOCASE ASC, id ASC"
            CatalogSortOrder.TITLE_ASC -> "title COLLATE NOCASE ASC, id ASC"
            CatalogSortOrder.TITLE_DESC -> "title COLLATE NOCASE DESC, id ASC"
        }
        args += limit
        args += offset
        return SimpleSQLiteQuery("$base SELECT * FROM visible ORDER BY $order LIMIT ? OFFSET ?", args.toTypedArray())
    }

    fun ranking(
        sortOrder: RankingSortOrder,
        categoryId: String?,
        filters: AnimeFilters,
        limit: Int,
        descending: Boolean = true
    ): SupportSQLiteQuery {
        val args = mutableListOf<Any?>()
        val base = visibleRows("", filters, categoryId.orEmpty(), args)
        val metric = when (sortOrder) {
            RankingSortOrder.FINAL_SCORE -> "finalScore"
            RankingSortOrder.QUALITY_SCORE -> "qualityScore"
            RankingSortOrder.PERSONAL_TASTE -> "additiveScore"
            RankingSortOrder.CATEGORY_SCORE -> "categoryScore"
        }
        args += limit
        val direction = if (descending) "DESC" else "ASC"
        return SimpleSQLiteQuery(
            "$base SELECT * FROM visible WHERE finalScore IS NOT NULL " +
                "ORDER BY CASE WHEN $metric IS NULL THEN 1 ELSE 0 END, $metric $direction, title COLLATE NOCASE LIMIT ?",
            args.toTypedArray()
        )
    }

    private fun visibleRows(
        query: String,
        filters: AnimeFilters,
        categoryId: String,
        args: MutableList<Any?>
    ): String {
        args += categoryId
        args += categoryId
        val groupText = if (query.isBlank()) "" else {
            args += "%$query%"
            args += "%$query%"
            args += "%$query%"
            "AND (g.display_title LIKE ? COLLATE NOCASE OR EXISTS (" +
                "SELECT 1 FROM anime_group_member tm JOIN anime ta ON ta.id=tm.anime_id " +
                "WHERE tm.group_id=g.id AND (ta.title LIKE ? COLLATE NOCASE OR EXISTS (" +
                "SELECT 1 FROM anime_alias aa WHERE aa.anime_id=ta.id " +
                "AND aa.title LIKE ? COLLATE NOCASE))))"
        }
        val groupFilters = groupFilterConditions(filters, args)

        args += categoryId
        val individualText = if (query.isBlank()) "" else {
            args += "%$query%"
            args += "%$query%"
            "AND (a.title LIKE ? COLLATE NOCASE OR EXISTS (" +
                "SELECT 1 FROM anime_alias aa WHERE aa.anime_id=a.id " +
                "AND aa.title LIKE ? COLLATE NOCASE))"
        }
        val individualFilters = individualFilterConditions("a", filters, args)
        return """
            WITH group_stats AS (
                SELECT gm.group_id,COUNT(*) AS memberCount,COUNT(r.final_score) AS ratedCount,
                       MIN(ga.popularity_rank) AS bestPopularityRank,
                       MAX(ga.popularity) AS bestPopularity,
                       AVG(r.final_score) AS derivedFinal,
                       AVG(CASE WHEN r.final_score IS NOT NULL THEN r.quality_score END) AS derivedQuality,
                       AVG(CASE WHEN r.final_score IS NOT NULL THEN r.additive_score END) AS derivedAdditive,
                       AVG(CASE WHEN r.final_score IS NOT NULL AND rs.applicable=1 THEN rs.score END) AS derivedCategory
                FROM anime_group_member gm
                JOIN anime ga ON ga.id=gm.anime_id
                LEFT JOIN rating r ON r.anime_id=gm.anime_id
                LEFT JOIN rating_score rs ON rs.anime_id=gm.anime_id AND rs.category_id=?
                GROUP BY gm.group_id
            ), visible AS (
                SELECT g.id AS id,g.display_title AS title,a.mal_id AS malId,a.anilist_id AS anilistId,
                       gs.bestPopularity AS popularity,gs.bestPopularityRank AS popularityRank,
                       CASE WHEN gr.final_score IS NOT NULL THEN gr.final_score ELSE gs.derivedFinal END AS finalScore,
                       CASE WHEN gr.final_score IS NOT NULL THEN gr.quality_score ELSE gs.derivedQuality END AS qualityScore,
                       CASE WHEN gr.final_score IS NOT NULL THEN gr.additive_score ELSE gs.derivedAdditive END AS additiveScore,
                       CASE WHEN gr.final_score IS NOT NULL THEN grs.score ELSE gs.derivedCategory END AS categoryScore,
                       CASE WHEN gr.final_score IS NOT NULL THEN gr.status ELSE NULL END AS status,
                       COALESCE(a.remote_cover_url,a.poster_url) AS remoteCoverUrl,
                       a.local_cover_path AS localCoverPath,a.cover_resolution_state AS coverResolutionState,
                       g.type AS format,a.season_year AS seasonYear,a.start_date AS startDate,
                       (SELECT ast.studio FROM anime_studio ast WHERE ast.anime_id=a.id ORDER BY ast.studio COLLATE NOCASE LIMIT 1) AS studio,
                       (SELECT ag.genre FROM anime_genre ag WHERE ag.anime_id=a.id ORDER BY ag.genre COLLATE NOCASE LIMIT 1) AS genre,
                       g.id AS groupId,g.type AS groupType,g.root_anime_id AS rootAnimeId,
                       gs.memberCount AS memberCount,gs.ratedCount AS ratedCount,
                       CASE WHEN gr.final_score IS NULL AND gs.ratedCount>0 THEN 1 ELSE 0 END AS derived
                FROM anime_group g JOIN anime a ON a.id=g.root_anime_id
                JOIN group_stats gs ON gs.group_id=g.id
                LEFT JOIN group_rating gr ON gr.group_id=g.id
                LEFT JOIN group_rating_score grs ON grs.group_id=g.id AND grs.category_id=? AND grs.applicable=1
                WHERE gs.memberCount>1 $groupText $groupFilters
                UNION ALL
                SELECT a.id,a.title,a.mal_id AS malId,a.anilist_id AS anilistId,
                       a.popularity,a.popularity_rank AS popularityRank,
                       r.final_score AS finalScore,r.quality_score AS qualityScore,
                       r.additive_score AS additiveScore,rs.score AS categoryScore,r.status,
                       COALESCE(a.remote_cover_url,a.poster_url) AS remoteCoverUrl,
                       a.local_cover_path AS localCoverPath,a.cover_resolution_state AS coverResolutionState,
                       a.format,a.season_year AS seasonYear,a.start_date AS startDate,
                       (SELECT ast.studio FROM anime_studio ast WHERE ast.anime_id=a.id ORDER BY ast.studio COLLATE NOCASE LIMIT 1) AS studio,
                       (SELECT ag.genre FROM anime_genre ag WHERE ag.anime_id=a.id ORDER BY ag.genre COLLATE NOCASE LIMIT 1) AS genre,
                       NULL AS groupId,NULL AS groupType,NULL AS rootAnimeId,
                       0 AS memberCount,0 AS ratedCount,0 AS derived
                FROM anime a LEFT JOIN rating r ON r.anime_id=a.id
                LEFT JOIN rating_score rs ON rs.anime_id=a.id AND rs.category_id=? AND rs.applicable=1
                WHERE NOT EXISTS (SELECT 1 FROM anime_group_member gm WHERE gm.anime_id=a.id)
                $individualText $individualFilters
            )
        """.trimIndent()
    }

    /** Each group filter is an independent ANY(member), then the conditions combine with AND. */
    private fun groupFilterConditions(filters: AnimeFilters, args: MutableList<Any?>): String {
        val conditions = mutableListOf<String>()
        filters.genre?.let {
            conditions += "EXISTS (SELECT 1 FROM anime_group_member gm JOIN anime_genre ag ON ag.anime_id=gm.anime_id WHERE gm.group_id=g.id AND ag.genre=?)"
            args += it
        }
        filters.format?.let {
            conditions += "g.type=?"
            args += normalizedAnimeFormat(it)
        }
        filters.year?.let {
            conditions += "EXISTS (SELECT 1 FROM anime_group_member gm JOIN anime fa ON fa.id=gm.anime_id WHERE gm.group_id=g.id AND fa.season_year=?)"
            args += it
        }
        filters.studio?.let {
            conditions += "EXISTS (SELECT 1 FROM anime_group_member gm JOIN anime_studio ast ON ast.anime_id=gm.anime_id WHERE gm.group_id=g.id AND ast.studio=?)"
            args += it
        }
        return conditions.joinToString(separator = " AND ", prefix = if (conditions.isEmpty()) "" else "AND ")
    }

    private fun individualFilterConditions(alias: String, filters: AnimeFilters, args: MutableList<Any?>): String {
        val conditions = mutableListOf<String>()
        filters.genre?.let {
            conditions += "EXISTS (SELECT 1 FROM anime_genre ag WHERE ag.anime_id=$alias.id AND ag.genre=?)"
            args += it
        }
        filters.format?.let { format ->
            conditions += when (format) {
                SERIES_FORMAT_FILTER -> "$alias.format IN ('TV','TV_SHORT','ONA')"
                MOVIES_FORMAT_FILTER -> "$alias.format='MOVIE'"
                SPECIAL_FORMAT_FILTER -> "$alias.format IN ('OVA','SPECIAL')"
                MUSIC_FORMAT_FILTER -> "$alias.format='MUSIC'"
                else -> "0"
            }
        }
        filters.year?.let { conditions += "$alias.season_year=?"; args += it }
        filters.studio?.let {
            conditions += "EXISTS (SELECT 1 FROM anime_studio ast WHERE ast.anime_id=$alias.id AND ast.studio=?)"
            args += it
        }
        return conditions.joinToString(separator = " AND ", prefix = if (conditions.isEmpty()) "" else "AND ")
    }
}
