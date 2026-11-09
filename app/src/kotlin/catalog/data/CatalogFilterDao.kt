package org.rankoffline.app

import androidx.room.Dao
import androidx.room.Query

/** SQL contract extracted from MediaDao; methods inherited by its @Dao facade. */
interface CatalogFilterDao {
    @Query("SELECT DISTINCT value_text FROM media_item_attribute WHERE pack_id = :packId AND field_id = :fieldId AND value_text IS NOT NULL ORDER BY value_text COLLATE NOCASE")
    suspend fun textFilterOptions(packId: Long, fieldId: String): List<String>


    @Query("SELECT DISTINCT value_number FROM media_item_attribute WHERE pack_id = :packId AND field_id = :fieldId AND value_number IS NOT NULL ORDER BY value_number")
    suspend fun numericFilterOptions(packId: Long, fieldId: String): List<Double>


    @Query("SELECT DISTINCT value_boolean FROM media_item_attribute WHERE pack_id = :packId AND field_id = :fieldId AND value_boolean IS NOT NULL ORDER BY value_boolean")
    suspend fun booleanFilterOptions(packId: Long, fieldId: String): List<Boolean>

}
