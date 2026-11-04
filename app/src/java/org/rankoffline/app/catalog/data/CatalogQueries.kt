package org.rankoffline.app

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import java.time.LocalDate

/**
 * Read-only universal catalog queries. The manifest defines field identities, and all values
 * are bound parameters. Group membership comes from installed pack data, never an algorithm.
 * Merge only selects a different projection; it does not alter item or group records.
 */
internal object CatalogQueries {
    fun catalog(
        manifest: PackManifest,
        state: SearchUiState,
        merge: Boolean,
        limit: Int,
        offset: Int = 0
    ): SupportSQLiteQuery = build(
        manifest, state.query, state.filters, merge, limit, offset,
        ranking = null, categoryId = null,
        catalogSort = state.sortOrder
    )

    /** Top cover identities only: no rating joins, group-count projections or files. */
    fun popularCovers(manifest: PackManifest, limit: Int, offset: Int): SupportSQLiteQuery {
        require(limit in 1..5_000 && offset >= 0 && offset <= Int.MAX_VALUE - limit)
        val sql = """
            WITH visible AS (
                SELECT i.pack_id, i.id, 'ITEM' AS entry_kind, i.title,
                    i.popularity, i.popularity_rank
                FROM media_item i
                WHERE i.pack_id = ? AND NOT EXISTS (
                    SELECT 1 FROM media_group_member m
                    WHERE m.pack_id = i.pack_id AND m.media_id = i.id
                )
                UNION ALL
                SELECT g.pack_id, g.id, 'GROUP' AS entry_kind,
                    g.display_title AS title,
                    COALESCE(g.group_popularity, (
                        SELECT MAX(i.popularity) FROM media_group_member m
                        JOIN media_item i ON i.pack_id=m.pack_id AND i.id=m.media_id
                        WHERE m.pack_id=g.pack_id AND m.group_id=g.id
                    )) AS popularity,
                    COALESCE(g.group_popularity_rank, (
                        SELECT MIN(i.popularity_rank) FROM media_group_member m
                        JOIN media_item i ON i.pack_id=m.pack_id AND i.id=m.media_id
                        WHERE m.pack_id=g.pack_id AND m.group_id=g.id
                    )) AS popularity_rank
                FROM media_group g WHERE g.pack_id = ?
            )
            SELECT pack_id, id, entry_kind, NULL AS final_score,
                NULL AS quality_score, NULL AS additive_score,
                NULL AS category_score, 0 AS member_count,
                0 AS rated_count, 0 AS has_explicit_score
            FROM visible
            ORDER BY ${catalogOrder(CatalogSortOrder.POPULARITY)},
                pack_id ASC, entry_kind ASC, id ASC
            LIMIT ? OFFSET ?
        """.trimIndent()
        return SimpleSQLiteQuery(sql, arrayOf<Any>(manifest.packId.value, manifest.packId.value, limit, offset))
    }

    fun ranking(
        manifest: PackManifest,
        state: RankingUiState,
        merge: Boolean,
        limit: Int,
        offset: Int = 0
    ): SupportSQLiteQuery = build(
        manifest, "", state.filters, merge, limit, offset,
        ranking = state, categoryId = state.ratingCategoryId,
        catalogSort = CatalogSortOrder.TITLE_ASC
    )

    private fun build(
        manifest: PackManifest,
        search: String,
        filters: CatalogFilters,
        merge: Boolean,
        limit: Int,
        offset: Int,
        ranking: RankingUiState?,
        categoryId: String?,
        catalogSort: CatalogSortOrder
    ): SupportSQLiteQuery {
        require(limit in 1..5000 && offset >= 0 && offset <= Int.MAX_VALUE - limit)
        val merged = merge && manifest.capabilities.merge && manifest.merge.enabled
        val args = mutableListOf<Any>()
        val item = StringBuilder(
            "SELECT i.pack_id, i.id, 'ITEM' AS entry_kind, i.title, " +
                "i.popularity, i.popularity_rank, " +
                "r.final_score, r.quality_score, r.additive_score, "
        )
        item.append(categoryScore("i", "media_id", "media_rating_score", categoryId, args))
        item.append(" AS category_score FROM media_item i ")
        item.append("LEFT JOIN media_rating r ON r.pack_id=i.pack_id AND r.media_id=i.id WHERE i.pack_id=?")
        args += manifest.packId.value
        applySearch(item, args, search, memberOfGroup = false)
        applyFilters(item, args, manifest, filters, "i", memberOfGroup = false)
        if (merged) {
            // All members (including shared members) are represented by their group entries.
            item.append(" AND NOT EXISTS (SELECT 1 FROM media_group_member gm ")
            item.append("WHERE gm.pack_id=i.pack_id AND gm.media_id=i.id)")
        }
        var sql = item.toString()
        if (merged) {
            val group = StringBuilder(
                "SELECT g.pack_id, g.id, 'GROUP' AS entry_kind, g.display_title AS title, " +
                    "COALESCE(g.group_popularity, (SELECT MAX(i2.popularity) FROM media_group_member m2 " +
                    "JOIN media_item i2 ON i2.pack_id=m2.pack_id AND i2.id=m2.media_id " +
                    "WHERE m2.pack_id=g.pack_id AND m2.group_id=g.id)) AS popularity, " +
                    "COALESCE(g.group_popularity_rank, (SELECT MIN(i2.popularity_rank) FROM media_group_member m2 " +
                    "JOIN media_item i2 ON i2.pack_id=m2.pack_id AND i2.id=m2.media_id " +
                    "WHERE m2.pack_id=g.pack_id AND m2.group_id=g.id)) AS popularity_rank, " +
                    "COALESCE(gr.final_score, (SELECT AVG(mr.final_score) FROM media_group_member m " +
                    "JOIN media_rating mr ON mr.pack_id=m.pack_id AND mr.media_id=m.media_id " +
                    "WHERE m.pack_id=g.pack_id AND m.group_id=g.id AND mr.final_score BETWEEN 0 AND 10)) AS final_score, " +
                    "COALESCE(gr.quality_score, (SELECT AVG(mr.quality_score) FROM media_group_member m " +
                    "JOIN media_rating mr ON mr.pack_id=m.pack_id AND mr.media_id=m.media_id " +
                    "WHERE m.pack_id=g.pack_id AND m.group_id=g.id AND mr.final_score BETWEEN 0 AND 10)) AS quality_score, " +
                    "COALESCE(gr.additive_score, (SELECT AVG(mr.additive_score) FROM media_group_member m " +
                    "JOIN media_rating mr ON mr.pack_id=m.pack_id AND mr.media_id=m.media_id " +
                    "WHERE m.pack_id=g.pack_id AND m.group_id=g.id AND mr.final_score BETWEEN 0 AND 10)) AS additive_score, "
            )
            // Explicit group category score takes precedence; otherwise average rated members.
            if (categoryId == null) {
                group.append("NULL AS category_score ")
            } else {
                group.append("COALESCE((SELECT s.score FROM media_group_rating_score s WHERE s.pack_id=g.pack_id " +
                    "AND s.group_id=g.id AND s.category_id=? AND s.applicable=1), " +
                    "(SELECT AVG(s.score) FROM media_group_member m " +
                    "JOIN media_rating mr ON mr.pack_id=m.pack_id AND mr.media_id=m.media_id " +
                    "JOIN media_rating_score s ON s.pack_id=m.pack_id AND s.media_id=m.media_id " +
                    "WHERE m.pack_id=g.pack_id AND m.group_id=g.id AND mr.final_score BETWEEN 0 AND 10 " +
                    "AND s.category_id=? AND s.applicable=1)) AS category_score ")
                args += categoryId
                args += categoryId
            }
            group.append("FROM media_group g LEFT JOIN media_group_rating gr " +
                "ON gr.pack_id=g.pack_id AND gr.group_id=g.id WHERE g.pack_id=? " +
                "AND EXISTS (SELECT 1 FROM media_group_member m WHERE m.pack_id=g.pack_id AND m.group_id=g.id)")
            args += manifest.packId.value
            applySearch(group, args, search, memberOfGroup = true)
            applyFilters(group, args, manifest, filters, "g", memberOfGroup = true)
            sql += " UNION ALL " + group.toString()
        }
        val order = if (ranking == null) catalogOrder(catalogSort) else rankingOrder(ranking)
        args += limit
        args += offset
        require(args.size <= 900) { "Too many SQLite parameters for a catalog query" }
        return SimpleSQLiteQuery(
            "WITH visible AS ($sql) SELECT v.pack_id, v.id, v.entry_kind, v.final_score, " +
                "v.quality_score, v.additive_score, v.category_score, " +
                "CASE WHEN v.entry_kind='GROUP' THEN " +
                "(SELECT COUNT(*) FROM media_group_member m WHERE m.pack_id=v.pack_id AND m.group_id=v.id) " +
                "ELSE 0 END AS member_count, " +
                "CASE WHEN v.entry_kind='GROUP' THEN " +
                "(SELECT COUNT(*) FROM media_group_member m JOIN media_rating r " +
                "ON r.pack_id=m.pack_id AND r.media_id=m.media_id " +
                "WHERE m.pack_id=v.pack_id AND m.group_id=v.id AND r.final_score BETWEEN 0 AND 10) " +
                "ELSE 0 END AS rated_count, " +
                "CASE WHEN v.entry_kind='GROUP' THEN EXISTS(SELECT 1 FROM media_group_rating gr " +
                "WHERE gr.pack_id=v.pack_id AND gr.group_id=v.id AND gr.final_score IS NOT NULL) " +
                "ELSE 0 END AS has_explicit_score " +
                "FROM visible v " +
                (if (ranking == null) "" else "WHERE ${rankingMetricColumn(ranking)} IS NOT NULL ") +
                "ORDER BY $order, v.pack_id ASC, v.entry_kind ASC, v.id ASC LIMIT ? OFFSET ?",
            args.toTypedArray()
        )
    }

    private fun categoryScore(
        alias: String, idColumn: String, table: String,
        categoryId: String?, args: MutableList<Any>
    ): String {
        if (categoryId == null) return "NULL"
        args += categoryId
        return "(SELECT s.score FROM $table s WHERE s.pack_id=$alias.pack_id " +
            "AND s.$idColumn=$alias.id AND s.category_id=? AND s.applicable=1)"
    }

    private fun applySearch(
        sql: StringBuilder, args: MutableList<Any>,
        query: String, memberOfGroup: Boolean
    ) {
        val term = query.trim().takeIf(String::isNotEmpty) ?: return
        val pattern = "%${term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
        if (memberOfGroup) {
            sql.append(" AND (g.display_title LIKE ? ESCAPE '\\' COLLATE NOCASE OR EXISTS (")
            sql.append("SELECT 1 FROM media_group_member m JOIN media_item i ")
            sql.append("ON i.pack_id=m.pack_id AND i.id=m.media_id WHERE m.pack_id=g.pack_id ")
            sql.append("AND m.group_id=g.id AND (i.title LIKE ? ESCAPE '\\' COLLATE NOCASE OR EXISTS (")
            sql.append("SELECT 1 FROM media_alias a WHERE a.pack_id=i.pack_id AND a.media_id=i.id ")
            sql.append("AND a.title LIKE ? ESCAPE '\\' COLLATE NOCASE))))")
            repeat(3) { args += pattern }
        } else {
            sql.append(" AND (i.title LIKE ? ESCAPE '\\' COLLATE NOCASE OR EXISTS (")
            sql.append("SELECT 1 FROM media_alias a WHERE a.pack_id=i.pack_id AND a.media_id=i.id ")
            sql.append("AND a.title LIKE ? ESCAPE '\\' COLLATE NOCASE))")
            repeat(2) { args += pattern }
        }
    }

    private fun applyFilters(
        sql: StringBuilder, args: MutableList<Any>, manifest: PackManifest,
        filters: CatalogFilters, alias: String, memberOfGroup: Boolean
    ) {
        val definitions = manifest.filters.associateBy(PackFilterDefinition::id)
        val fields = manifest.fields.associateBy(PackFieldDefinition::id)
        filters.selections.forEach { (filterId, selection) ->
            if (!selection.isActive) return@forEach
            val definition = requireNotNull(definitions[filterId]) { "Undeclared filter: $filterId" }
            val field = requireNotNull(fields[definition.field]) { "Missing field: ${definition.field}" }
            val values = mutableListOf<Any>()
            val condition = when (selection) {
                is CatalogFilterValue.Choices -> {
                    require(definition.type == PackFilterType.MULTI_SELECT)
                    require(field.type in setOf(CatalogFieldType.TEXT, CatalogFieldType.CHOICE))
                    require(selection.values.size <= 64)
                    require(field.options.isEmpty() || selection.values.all { it in field.options })
                    values.addAll(selection.values.sorted())
                    "a.value_text IN (${List(selection.values.size) { "?" }.joinToString()})"
                }
                is CatalogFilterValue.Range -> {
                    require(definition.type == PackFilterType.RANGE)
                    require(field.type == CatalogFieldType.INTEGER || field.type == CatalogFieldType.DECIMAL)
                    buildList {
                        selection.min?.let { add("a.value_number >= ?"); values += it }
                        selection.max?.let { add("a.value_number <= ?"); values += it }
                    }.joinToString(" AND ")
                }
                is CatalogFilterValue.DateRange -> {
                    require(definition.type == PackFilterType.RANGE && field.type == CatalogFieldType.DATE)
                    buildList {
                        selection.from?.let { LocalDate.parse(it); add("a.value_text >= ?"); values += it }
                        selection.to?.let { LocalDate.parse(it); add("a.value_text <= ?"); values += it }
                    }.joinToString(" AND ")
                }
                is CatalogFilterValue.BooleanEquals -> {
                    require(definition.type == PackFilterType.MULTI_SELECT && field.type == CatalogFieldType.BOOLEAN)
                    values += if (selection.value) 1 else 0
                    "a.value_boolean = ?"
                }
            }
            if (memberOfGroup) {
                sql.append(" AND EXISTS (SELECT 1 FROM media_group_member m JOIN media_item_attribute a ")
                sql.append("ON a.pack_id=m.pack_id AND a.media_id=m.media_id WHERE m.pack_id=$alias.pack_id ")
                sql.append("AND m.group_id=$alias.id AND a.field_id=? AND $condition)")
            } else {
                sql.append(" AND EXISTS (SELECT 1 FROM media_item_attribute a WHERE a.pack_id=$alias.pack_id ")
                sql.append("AND a.media_id=$alias.id AND a.field_id=? AND $condition)")
            }
            args += definition.field
            args.addAll(values)
        }
    }

    private fun catalogOrder(order: CatalogSortOrder): String = when (order) {
        CatalogSortOrder.TITLE_ASC -> "title COLLATE NOCASE ASC"
        CatalogSortOrder.TITLE_DESC -> "title COLLATE NOCASE DESC"
        CatalogSortOrder.POPULARITY -> "CASE WHEN popularity_rank IS NULL AND popularity IS NULL THEN 1 ELSE 0 END, " +
            "CASE WHEN popularity_rank IS NULL THEN 1 ELSE 0 END, popularity_rank ASC, popularity DESC, title COLLATE NOCASE ASC"
        CatalogSortOrder.POPULARITY_REVERSE -> "CASE WHEN popularity_rank IS NULL AND popularity IS NULL THEN 1 ELSE 0 END, " +
            "CASE WHEN popularity_rank IS NULL THEN 1 ELSE 0 END, popularity_rank DESC, popularity ASC, title COLLATE NOCASE ASC"
    }

    private fun rankingMetricColumn(state: RankingUiState): String = when (state.sortOrder) {
        RankingSortOrder.FINAL_SCORE -> "final_score"
        RankingSortOrder.QUALITY_SCORE -> "quality_score"
        RankingSortOrder.PERSONAL_TASTE -> "additive_score"
        RankingSortOrder.CATEGORY_SCORE -> "category_score"
    }

    private fun rankingOrder(state: RankingUiState): String {
        val metric = when (state.sortOrder) {
            RankingSortOrder.FINAL_SCORE -> "final_score"
            RankingSortOrder.QUALITY_SCORE -> "quality_score"
            RankingSortOrder.PERSONAL_TASTE -> "additive_score"
            RankingSortOrder.CATEGORY_SCORE -> "category_score"
        }
        val direction = if (state.descending) "DESC" else "ASC"
        return "CASE WHEN $metric IS NULL THEN 1 ELSE 0 END, $metric $direction, title COLLATE NOCASE ASC"
    }
}
