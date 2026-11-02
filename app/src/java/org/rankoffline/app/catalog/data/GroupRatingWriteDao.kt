package org.rankoffline.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

/** SQL contract extracted from MediaGroupDao; methods inherited by its @Dao facade. */
interface GroupRatingWriteDao {
    @Upsert
    suspend fun upsertRating(value: MediaGroupRatingEntity)


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRatingScores(values: List<MediaGroupRatingScoreEntity>)


    @Query("DELETE FROM media_group_rating_score WHERE pack_id = :packId AND group_id = :id")
    suspend fun deleteRatingScores(packId: Long, id: String)


    @Query("DELETE FROM media_group_rating WHERE pack_id = :packId AND group_id = :id")
    suspend fun deleteRating(packId: Long, id: String): Int


    @Query("DELETE FROM media_group_rating_score WHERE pack_id = :packId AND category_id = :categoryId")
    suspend fun deleteRatingScoresForCategory(packId: Long, categoryId: String): Int


    @Query("DELETE FROM media_group_rating_score WHERE pack_id = :packId AND category_id NOT IN (:ids)")
    suspend fun deleteRatingScoresOutsideCategories(packId: Long, ids: List<String>): Int

}
