package org.rankoffline.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** SQL contract extracted from MediaCatalogDao; methods inherited by its @Dao facade. */
interface CatalogMetadataDao {
    @Query("SELECT COUNT(*) FROM media_item WHERE pack_id = :packId")
    suspend fun itemCount(packId: Long): Long


    @Query("SELECT metadata_value FROM media_catalog_metadata WHERE pack_id = :packId AND metadata_key = :key")
    suspend fun metadata(packId: Long, key: String): String?


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMetadata(values: List<MediaCatalogMetadataEntity>)


    @Query("DELETE FROM media_catalog_metadata WHERE pack_id = :packId AND metadata_key = :key")
    suspend fun deleteMetadata(packId: Long, key: String): Int

}
