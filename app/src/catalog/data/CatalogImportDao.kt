package org.rankoffline.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/** SQL contract extracted from MediaCatalogDao; methods inherited by its @Dao facade. */
interface CatalogImportDao {
    @Query("SELECT id FROM media_item WHERE pack_id = :packId AND id IN (:ids)")
    suspend fun existingItemIds(packId: Long, ids: List<String>): List<String>


    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNewItems(items: List<MediaItemEntity>): List<Long>


    @Update(entity = MediaItemEntity::class)
    suspend fun updateCatalogFields(items: List<MediaCatalogItemFields>): Int


    @Query("DELETE FROM media_item_attribute WHERE pack_id = :packId AND media_id IN (:ids)")
    suspend fun deleteItemAttributes(packId: Long, ids: List<String>)


    @Query("DELETE FROM media_alias WHERE pack_id = :packId AND media_id IN (:ids)")
    suspend fun deleteItemAliases(packId: Long, ids: List<String>)


    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttributes(values: List<MediaItemAttributeEntity>)


    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAliases(values: List<MediaAliasEntity>)


}
