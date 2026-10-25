package org.rankoffline.app

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * Builds the generic merged catalog page in SQLite. Relationship discovery is deliberately not
 * present here: all membership rows were precomputed by pack tooling and imported once.
 */
internal object GenericGroupedQueries {
    fun catalogIds(
        mediaType: String,
        groupType: String,
        query: String,
        sortOrder: CatalogSortOrder,
        filters: GenericMediaFilters,
        creditRole: String?,
        limit: Int,
        offset: Int
    ): SupportSQLiteQuery {
        val args = mutableListOf<Any?>()
        val normalizedQuery = query.trim()

        args += mediaType
        args += groupType
        val groupConditions = mutableListOf<String>()
        if (normalizedQuery.isNotEmpty()) {
            val match = "%$normalizedQuery%"
            groupConditions += """
                (mediaGroup.display_title LIKE ? COLLATE NOCASE OR EXISTS (
                    SELECT 1 FROM media_group_member searchMember
                    INNER JOIN media_item searchItem ON searchItem.id=searchMember.media_id
                    WHERE searchMember.group_id=mediaGroup.id AND (
                        searchItem.title LIKE ? COLLATE NOCASE OR
                        searchItem.original_title LIKE ? COLLATE NOCASE OR
                        EXISTS (SELECT 1 FROM media_alias alias
                                WHERE alias.media_id=searchItem.id
                                  AND alias.title LIKE ? COLLATE NOCASE)
                    )
                ))
            """.trimIndent()
            repeat(4) { args += match }
        }
        filters.genre?.let { genre ->
            groupConditions += """
                EXISTS (SELECT 1 FROM media_group_member filterMember
                        INNER JOIN media_genre genreRow ON genreRow.media_id=filterMember.media_id
                        WHERE filterMember.group_id=mediaGroup.id AND genreRow.genre=?)
            """.trimIndent()
            args += genre
        }
        filters.year?.let { year ->
            groupConditions += """
                EXISTS (SELECT 1 FROM media_group_member filterMember
                        INNER JOIN media_item filterItem ON filterItem.id=filterMember.media_id
                        WHERE filterMember.group_id=mediaGroup.id AND filterItem.year=?)
            """.trimIndent()
            args += year
        }
        filters.format?.let { format ->
            groupConditions += """
                EXISTS (SELECT 1 FROM media_group_member filterMember
                        INNER JOIN media_item filterItem ON filterItem.id=filterMember.media_id
                        WHERE filterMember.group_id=mediaGroup.id AND filterItem.format=?)
            """.trimIndent()
            args += format
        }
        filters.demographic?.let { demographic ->
            groupConditions += """
                EXISTS (SELECT 1 FROM media_group_member filterMember
                        INNER JOIN media_item filterItem ON filterItem.id=filterMember.media_id
                        WHERE filterMember.group_id=mediaGroup.id AND filterItem.demographic=?)
            """.trimIndent()
            args += demographic
        }
        filters.creditName?.let { creditName ->
            groupConditions += """
                EXISTS (SELECT 1 FROM media_group_member filterMember
                        INNER JOIN media_credit credit ON credit.media_id=filterMember.media_id
                        WHERE filterMember.group_id=mediaGroup.id
                          AND credit.role=? AND credit.name=?)
            """.trimIndent()
            args += creditRole
            args += creditName
        }

        args += mediaType
        args += mediaType
        args += groupType
        val itemConditions = mutableListOf<String>()
        if (normalizedQuery.isNotEmpty()) {
            val match = "%$normalizedQuery%"
            itemConditions += """
                (item.title LIKE ? COLLATE NOCASE OR item.original_title LIKE ? COLLATE NOCASE OR
                 EXISTS (SELECT 1 FROM media_alias alias WHERE alias.media_id=item.id
                         AND alias.title LIKE ? COLLATE NOCASE))
            """.trimIndent()
            repeat(3) { args += match }
        }
        filters.genre?.let { genre ->
            itemConditions += "EXISTS (SELECT 1 FROM media_genre genreRow WHERE genreRow.media_id=item.id AND genreRow.genre=?)"
            args += genre
        }
        filters.year?.let { year -> itemConditions += "item.year=?"; args += year }
        filters.format?.let { format -> itemConditions += "item.format=?"; args += format }
        filters.demographic?.let { demographic ->
            itemConditions += "item.demographic=?"
            args += demographic
        }
        filters.creditName?.let { creditName ->
            itemConditions += "EXISTS (SELECT 1 FROM media_credit credit WHERE credit.media_id=item.id AND credit.role=? AND credit.name=?)"
            args += creditRole
            args += creditName
        }

        val order = when (sortOrder) {
            CatalogSortOrder.TITLE_ASC -> "title COLLATE NOCASE ASC, id ASC"
            CatalogSortOrder.TITLE_DESC -> "title COLLATE NOCASE DESC, id ASC"
            CatalogSortOrder.POPULARITY ->
                "CASE WHEN popularity_rank IS NULL AND popularity IS NULL THEN 1 ELSE 0 END, " +
                    "popularity_rank ASC, CASE WHEN popularity_rank IS NULL THEN popularity END DESC, " +
                    "title COLLATE NOCASE ASC, id ASC"
            CatalogSortOrder.POPULARITY_REVERSE ->
                "CASE WHEN popularity_rank IS NULL AND popularity IS NULL THEN 1 ELSE 0 END, " +
                    "popularity_rank DESC, CASE WHEN popularity_rank IS NULL THEN popularity END ASC, " +
                    "title COLLATE NOCASE ASC, id ASC"
        }
        args += limit
        args += offset
        val groupWhere = groupConditions.joinToString(separator = " AND ", prefix = if (groupConditions.isEmpty()) "" else " AND ")
        val itemWhere = itemConditions.joinToString(separator = " AND ", prefix = if (itemConditions.isEmpty()) "" else " AND ")
        return SimpleSQLiteQuery(
            """
            WITH visible AS (
                SELECT mediaGroup.id AS id, 1 AS entry_type,
                       mediaGroup.display_title AS title,
                       mediaGroup.group_popularity_rank AS popularity_rank,
                       mediaGroup.group_popularity AS popularity
                FROM media_group mediaGroup
                WHERE mediaGroup.media_type=? AND mediaGroup.group_type=?
                  AND mediaGroup.member_count>1$groupWhere
                UNION ALL
                SELECT item.id AS id, 0 AS entry_type, item.title AS title,
                       item.popularity_rank AS popularity_rank, item.popularity AS popularity
                FROM media_item item
                WHERE item.media_type=? AND item.id NOT IN (
                    SELECT excludedMember.media_id FROM media_group_member excludedMember
                    INNER JOIN media_group excludedGroup ON excludedGroup.id=excludedMember.group_id
                    WHERE excludedGroup.media_type=? AND excludedGroup.group_type=?
                )$itemWhere
            )
            SELECT id,entry_type FROM visible ORDER BY $order LIMIT ? OFFSET ?
            """.trimIndent(),
            args.toTypedArray()
        )
    }
}
