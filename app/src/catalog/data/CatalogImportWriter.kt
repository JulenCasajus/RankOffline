package org.rankoffline.app

import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement

/** Room writes for catalog-only values. Personal ratings and local cover state are untouched. */
internal class CatalogImportWriter(
    private val dao: MediaCatalogDao,
    private val sqlite: SupportSQLiteDatabase,
    private val packId: Long,
    private val stage: String,
    private val freshCatalog: Boolean
) {
    private val markStatement = sqlite.compileStatement(
        "INSERT INTO `$stage`(id, provider, provider_id) VALUES(?, ?, ?)"
    )
    private val fullBatchStatements = mutableMapOf<String, SupportSQLiteStatement>()

    fun markSeen(item: MediaItemEntity, file: String, line: Long) {
        try {
            markStatement.clearBindings()
            markStatement.bindString(1, item.id)
            if (item.provider == null) markStatement.bindNull(2) else markStatement.bindString(2, item.provider)
            if (item.providerId == null) markStatement.bindNull(3) else markStatement.bindString(3, item.providerId)
            markStatement.executeInsert()
        } catch (error: SQLiteConstraintException) {
            throw CatalogImportException("Duplicate catalog ID or provider identity at $file:$line", error)
        }
    }

    suspend fun upsert(rows: List<MediaCatalogItemRows>) {
        if (freshCatalog) {
            insertRows("media_item", ITEM_COLUMNS, rows.map { itemValues(it.item) })
            insertRows("media_item_attribute", ATTRIBUTE_COLUMNS,
                rows.flatMap { it.attributes }.map(::attributeValues))
            insertRows("media_alias", ALIAS_COLUMNS,
                rows.flatMap { it.aliases }.map(::aliasValues))
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
        markStatement.close()
        fullBatchStatements.values.forEach(SupportSQLiteStatement::close)
    }

    fun deleteMissing() {
        if (freshCatalog) return
        // Composite identity: a refresh of one pack cannot delete another pack's items.
        // This cascades reference attributes and memberships but not detached personal ratings.
        sqlite.execSQL(
            "DELETE FROM media_item WHERE pack_id = ? " +
                "AND NOT EXISTS (SELECT 1 FROM `$stage` WHERE `$stage`.id = media_item.id)",
            arrayOf(packId)
        )
    }

    private fun insertRows(table: String, columns: List<String>, values: List<List<Any?>>) {
        if (values.isEmpty()) return
        require(values.all { it.size == columns.size }) { "Invalid $table row width" }
        val fullSize = MAX_SQL_BIND_ARGUMENTS / columns.size
        values.chunked(fullSize).forEach { chunk ->
            val row = List(columns.size) { "?" }.joinToString(prefix = "(", postfix = ")")
            val sql = "INSERT INTO `$table`(" + columns.joinToString(",") { "`$it`" } +
                ") VALUES " + List(chunk.size) { row }.joinToString(",")
            val reusable = chunk.size == fullSize
            val statement = if (reusable) fullBatchStatements.getOrPut(table) {
                sqlite.compileStatement(sql)
            } else sqlite.compileStatement(sql)
            try {
                statement.clearBindings()
                var index = 1
                chunk.forEach { record -> record.forEach { value ->
                    when (value) {
                        null -> statement.bindNull(index)
                        is String -> statement.bindString(index, value)
                        is Int -> statement.bindLong(index, value.toLong())
                        is Long -> statement.bindLong(index, value)
                        is Double -> statement.bindDouble(index, value)
                        is Boolean -> statement.bindLong(index, if (value) 1L else 0L)
                        else -> error("Unsupported $table value type: ${value::class.java.name}")
                    }
                    index++
                } }
                statement.executeInsert()
            } finally {
                if (!reusable) statement.close()
            }
        }
    }

    private fun itemValues(value: MediaItemEntity): List<Any?> = listOf(
        value.packId, value.id, value.title, value.provider, value.providerId, value.popularity,
        value.popularityRank, value.remoteCoverRef, value.localCoverPath, value.coverResolutionState)

    private fun attributeValues(value: MediaItemAttributeEntity): List<Any?> = listOf(
        value.packId, value.mediaId, value.fieldId, value.position, value.valueText,
        value.valueNumber, value.valueBoolean)

    private fun aliasValues(value: MediaAliasEntity): List<Any?> = listOf(
        value.packId, value.mediaId, value.title, value.language, value.kind)

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
