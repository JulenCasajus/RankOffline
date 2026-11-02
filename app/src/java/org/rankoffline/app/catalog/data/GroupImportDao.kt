package org.rankoffline.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/** SQL contract extracted from MediaGroupDao; methods inherited by its @Dao facade. */
interface GroupImportDao {
    @Query("SELECT id FROM media_group WHERE pack_id = :packId AND id IN (:ids)")
    suspend fun existingGroupIds(packId: Long, ids: List<String>): List<String>


    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNewGroups(groups: List<MediaGroupEntity>): List<Long>


    @Update(entity = MediaGroupEntity::class)
    suspend fun updateCatalogFields(groups: List<MediaGroupCatalogFields>): Int


    @Query("DELETE FROM media_group_attribute WHERE pack_id = :packId AND group_id IN (:ids)")
    suspend fun deleteAttributes(packId: Long, ids: List<String>)


    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttributes(values: List<MediaGroupAttributeEntity>)

}
