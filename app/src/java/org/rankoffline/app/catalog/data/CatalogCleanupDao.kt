package org.rankoffline.app

import androidx.room.Dao
import androidx.room.Query

/** SQL contract extracted from MediaCatalogDao; methods inherited by its @Dao facade. */
interface CatalogCleanupDao {
    @Query("DELETE FROM media_group WHERE pack_id = :packId")
    suspend fun deleteGroups(packId: Long): Int


    @Query("DELETE FROM media_item WHERE pack_id = :packId")
    suspend fun deleteItems(packId: Long): Int


    @Query("DELETE FROM media_catalog_metadata WHERE pack_id = :packId")
    suspend fun deleteAllMetadata(packId: Long): Int

}
