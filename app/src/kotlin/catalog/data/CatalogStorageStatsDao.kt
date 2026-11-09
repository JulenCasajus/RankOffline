package org.rankoffline.app

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** SQL contract extracted from MediaCatalogDao; methods inherited by its @Dao facade. */
interface CatalogStorageStatsDao {
    @Query("SELECT COALESCE(SUM(length(CAST(media_id AS BLOB)) + length(CAST(notes AS BLOB)) + length(CAST(status AS BLOB)) + COALESCE(length(CAST(profile_id AS BLOB)), 0) + 40), 0) FROM media_rating WHERE pack_id = :packId")
    fun observeItemRatingLogicalBytes(packId: Long): Flow<Long>


    @Query("SELECT COALESCE(SUM(length(CAST(group_id AS BLOB)) + length(CAST(notes AS BLOB)) + length(CAST(status AS BLOB)) + COALESCE(length(CAST(profile_id AS BLOB)), 0) + 40), 0) FROM media_group_rating WHERE pack_id = :packId")
    fun observeGroupRatingLogicalBytes(packId: Long): Flow<Long>


    @Query("SELECT COALESCE(SUM(length(CAST(media_id AS BLOB)) + length(CAST(category_id AS BLOB)) + 16), 0) FROM media_rating_score WHERE pack_id = :packId")
    fun observeItemScoreLogicalBytes(packId: Long): Flow<Long>


    @Query("SELECT COALESCE(SUM(length(CAST(group_id AS BLOB)) + length(CAST(category_id AS BLOB)) + 16), 0) FROM media_group_rating_score WHERE pack_id = :packId")
    fun observeGroupScoreLogicalBytes(packId: Long): Flow<Long>

}
