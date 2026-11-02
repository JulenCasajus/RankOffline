package org.rankoffline.app

import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase

/** Room writes for catalog-only values. Personal ratings and local cover state are untouched. */
internal class CatalogImportWriter(
    private val dao: MediaCatalogDao,
    private val sqlite: SupportSQLiteDatabase,
    private val packId: Long,
    private val stage: String
) {
    private val markStatement = sqlite.compileStatement(
        "INSERT INTO `$stage`(id, provider, provider_id) VALUES(?, ?, ?)"
    )

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

    fun close() = markStatement.close()

    fun deleteMissing() {
        // Composite identity: a refresh of one pack cannot delete another pack's items.
        // This cascades reference attributes and memberships but not detached personal ratings.
        sqlite.execSQL(
            "DELETE FROM media_item WHERE pack_id = ? " +
                "AND NOT EXISTS (SELECT 1 FROM `$stage` WHERE `$stage`.id = media_item.id)",
            arrayOf(packId)
        )
    }
}
