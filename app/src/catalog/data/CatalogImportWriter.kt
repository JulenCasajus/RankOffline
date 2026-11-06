package org.rankoffline.app

import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement

/** Room writes for catalog-only values. Personal ratings and local cover state are untouched. */
internal class CatalogImportWriter(
    private val dao: MediaCatalogDao,
    private val sqlite: SupportSQLiteDatabase,
    private val packId: Long,
    private val stage: String?,
    private val freshCatalog: Boolean
) {
    private val markStatement = stage?.let {
        sqlite.compileStatement("INSERT INTO `$it`(id, provider, provider_id) VALUES(?, ?, ?)")
    }
    private val fullBatchStatements = mutableMapOf<String, SupportSQLiteStatement>()

    fun markSeen(item: MediaItemEntity, file: String, line: Long) {
        // Fresh installs insert every row into media_item; its primary and provider-identity
        // constraints already reject duplicates inside the same atomic transaction.
        val mark = markStatement ?: return
        try {
            mark.clearBindings()
            mark.bindString(1, item.id)
            if (item.provider == null) mark.bindNull(2) else mark.bindString(2, item.provider)
            if (item.providerId == null) mark.bindNull(3) else mark.bindString(3, item.providerId)
            mark.executeInsert()
        } catch (error: SQLiteConstraintException) {
            throw CatalogImportException("Duplicate catalog ID or provider identity at $file:$line", error)
        }
    }

    suspend fun upsert(rows: List<MediaCatalogItemRows>) {
        if (freshCatalog) {
            insertRows("media_item", ITEM_COLUMNS, rows.map { it.item }, ::bindItem)
            insertRows("media_item_attribute", ATTRIBUTE_COLUMNS,
                rows.flatMap { it.attributes }, ::bindAttribute)
            insertRows("media_alias", ALIAS_COLUMNS,
                rows.flatMap { it.aliases }, ::bindAlias)
            return
        }
        val ids = rows.map { it.item.id }
        val existing = dao.existingItemIds(packId, ids).toHashSet()
        val fresh = rows.map { it.item }.filterNot { it.id in existing }
        val updated = rows.map { it.item }.filter { it.id in existing }
        if (fresh.isNotEmpty()) {
            val results = dao.insertNewItems(fresh)
            require(results.size == fresh.size && results.none { it == -1L }) {
                "Catalog insert conflict"
            }
        }
        if (updated.isNotEmpty()) {
            // A changed source reference invalidates a previously downloaded cover.
            // The file cache itself will be reclaimed by the cover service.
            val invalidate = sqlite.compileStatement(
                "UPDATE media_item SET local_cover_path = NULL, cover_resolution_state = 'UNKNOWN' " +
                    "WHERE pack_id = ? AND id = ? AND remote_cover_ref IS NOT ?"
            )
            try {
                for (item in updated) {
                    invalidate.clearBindings()
                    invalidate.bindLong(1, packId)
                    invalidate.bindString(2, item.id)
                    if (item.remoteCoverRef == null) invalidate.bindNull(3)
                    else invalidate.bindString(3, item.remoteCoverRef)
                    invalidate.executeUpdateDelete()
                }
            } finally { invalidate.close() }
            // Only catalog-owned columns are updated; other cached cover paths survive.
            val fields = updated.map { item ->
                MediaCatalogItemFields(
                    packId = item.packId,
                    id = item.id,
                    title = item.title,
                    provider = item.provider,
                    providerId = item.providerId,
                    popularity = item.popularity,
                    popularityRank = item.popularityRank,
                    remoteCoverRef = item.remoteCoverRef
                )
            }
            require(dao.updateCatalogFields(fields) == fields.size) { "Catalog update conflict" }
        }
        dao.replaceItemDetails(
            packId = packId,
            ids = ids,
            attributes = rows.flatMap { it.attributes },
            aliases = rows.flatMap { it.aliases }
        )
    }

    fun close() {
        markStatement?.close()
        fullBatchStatements.values.forEach(SupportSQLiteStatement::close)
    }

    fun deleteMissing() {
        if (freshCatalog) return
        val seenTable = requireNotNull(stage)
        // Composite identity: a refresh of one pack cannot delete another pack's items.
        // This cascades reference attributes and memberships but not detached personal ratings.
        sqlite.execSQL(
            "DELETE FROM media_item WHERE pack_id = ? " +
                "AND NOT EXISTS (SELECT 1 FROM `$seenTable` WHERE `$seenTable`.id = media_item.id)",
            arrayOf(packId)
        )
    }

    private fun <T> insertRows(table: String, columns: List<String>, values: List<T>,
                               bind: (SupportSQLiteStatement, Int, T) -> Unit) {
        if (values.isEmpty()) return
        val fullSize = MAX_SQL_BIND_ARGUMENTS / columns.size
        val row = List(columns.size) { "?" }.joinToString(prefix = "(", postfix = ")")
        val prefix = "INSERT INTO `$table`(" + columns.joinToString(",") { "`$it`" } + ") VALUES "
        values.chunked(fullSize).forEach { chunk ->
            val sql = prefix + List(chunk.size) { row }.joinToString(",")
            val reusable = chunk.size == fullSize
            val statement = if (reusable) fullBatchStatements.getOrPut(table) {
                sqlite.compileStatement(sql)
            } else sqlite.compileStatement(sql)
            try {
                statement.clearBindings()
                var index = 1
                chunk.forEach { record ->
                    bind(statement, index, record)
                    index += columns.size
                }
                try { statement.executeInsert() }
                catch (error: SQLiteConstraintException) {
                    throw CatalogImportException("Catalog constraint violation in $table", error)
                }
            } finally {
                if (!reusable) statement.close()
            }
        }
    }

    private fun bindItem(sql: SupportSQLiteStatement, i: Int, value: MediaItemEntity) {
        sql.bindLong(i, value.packId)
        sql.bindString(i + 1, value.id)
        sql.bindString(i + 2, value.title)
        sql.bindTextOrNull(i + 3, value.provider)
        sql.bindTextOrNull(i + 4, value.providerId)
        sql.bindDoubleOrNull(i + 5, value.popularity)
        if (value.popularityRank == null) sql.bindNull(i + 6)
        else sql.bindLong(i + 6, value.popularityRank.toLong())
        sql.bindTextOrNull(i + 7, value.remoteCoverRef)
        sql.bindTextOrNull(i + 8, value.localCoverPath)
        sql.bindString(i + 9, value.coverResolutionState)
    }

    private fun bindAttribute(sql: SupportSQLiteStatement, i: Int, value: MediaItemAttributeEntity) {
        sql.bindLong(i, value.packId)
        sql.bindString(i + 1, value.mediaId)
        sql.bindString(i + 2, value.fieldId)
        sql.bindLong(i + 3, value.position.toLong())
        sql.bindTextOrNull(i + 4, value.valueText)
        sql.bindDoubleOrNull(i + 5, value.valueNumber)
        if (value.valueBoolean == null) sql.bindNull(i + 6)
        else sql.bindLong(i + 6, if (value.valueBoolean) 1L else 0L)
    }

    private fun bindAlias(sql: SupportSQLiteStatement, i: Int, value: MediaAliasEntity) {
        sql.bindLong(i, value.packId)
        sql.bindString(i + 1, value.mediaId)
        sql.bindString(i + 2, value.title)
        sql.bindTextOrNull(i + 3, value.language)
        sql.bindTextOrNull(i + 4, value.kind)
    }

    private fun SupportSQLiteStatement.bindTextOrNull(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindString(index, value)
    }

    private fun SupportSQLiteStatement.bindDoubleOrNull(index: Int, value: Double?) {
        if (value == null) bindNull(index) else bindDouble(index, value)
    }

    private companion object {
        const val MAX_SQL_BIND_ARGUMENTS = 900
        val ITEM_COLUMNS = listOf("pack_id", "id", "title", "provider", "provider_id",
            "popularity", "popularity_rank", "remote_cover_ref", "local_cover_path",
            "cover_resolution_state")
        val ATTRIBUTE_COLUMNS = listOf("pack_id", "media_id", "field_id", "position",
            "value_text", "value_number", "value_boolean")
        val ALIAS_COLUMNS = listOf("pack_id", "media_id", "title", "language", "kind")
    }
}
