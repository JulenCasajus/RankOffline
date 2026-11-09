package org.rankoffline.app

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Transaction

/** Catalog-owned values only. A refresh must never overwrite the local cover cache. */
data class MediaCatalogItemFields(
    @ColumnInfo(name = "pack_id") val packId: Long,
    val id: String,
    val title: String,
    val provider: String?,
    @ColumnInfo(name = "provider_id") val providerId: String?,
    val popularity: Double?,
    @ColumnInfo(name = "popularity_rank") val popularityRank: Int?,
    @ColumnInfo(name = "remote_cover_ref") val remoteCoverRef: String?
)

/** Writes and removes catalog content, never the user's ratings. */
@Dao
interface MediaCatalogDao : CatalogImportDao, CatalogMetadataDao, CatalogCleanupDao, CatalogStorageStatsDao {
    /** Call within the installation's outer transaction, after validating the complete pack. */
    @Transaction
    suspend fun replaceItemDetails(
        packId: Long,
        ids: List<String>,
        attributes: List<MediaItemAttributeEntity>,
        aliases: List<MediaAliasEntity>
    ) {
        require(ids.distinct().size == ids.size)
        require(attributes.all { it.packId == packId && it.mediaId in ids })
        require(aliases.all { it.packId == packId && it.mediaId in ids })
        ids.chunked(500).forEach { chunk ->
            deleteItemAttributes(packId, chunk)
            deleteItemAliases(packId, chunk)
        }
        // Bounded batches avoid excessive SQLite bind parameters.
        attributes.chunked(500).forEach { insertAttributes(it) }
        aliases.chunked(500).forEach { insertAliases(it) }
    }

    /** Pack removal/refresh deliberately preserves detached personal ratings and notes. */
    @Transaction
    suspend fun deleteCatalogReferenceData(packId: Long) {
        deleteGroups(packId)
        deleteItems(packId)
        deleteAllMetadata(packId)
    }

}
