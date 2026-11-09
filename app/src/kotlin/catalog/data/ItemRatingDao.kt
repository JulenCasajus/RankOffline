package org.rankoffline.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/** SQL contract extracted from MediaDao; methods inherited by its @Dao facade. */
interface ItemRatingDao {
    @Query("SELECT * FROM media_rating WHERE pack_id = :packId AND media_id = :id")
    suspend fun rating(packId: Long, id: String): MediaRatingEntity?


    @Query("SELECT * FROM media_rating_score WHERE pack_id = :packId AND media_id = :id ORDER BY category_id")
    suspend fun ratingScores(packId: Long, id: String): List<MediaRatingScoreEntity>


    @Upsert
    suspend fun upsertRating(value: MediaRatingEntity)


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRatingScores(values: List<MediaRatingScoreEntity>)


    @Query("DELETE FROM media_rating_score WHERE pack_id = :packId AND media_id = :id")
    suspend fun deleteRatingScores(packId: Long, id: String)


    @Transaction
    suspend fun saveRating(value: MediaRatingEntity, scores: List<MediaRatingScoreEntity>) {
        require(scores.all { it.packId == value.packId && it.mediaId == value.mediaId })
        upsertRating(value)
        deleteRatingScores(value.packId, value.mediaId)
        if (scores.isNotEmpty()) insertRatingScores(scores)
    }


    @Query("DELETE FROM media_rating WHERE pack_id = :packId AND media_id = :id")
    suspend fun deleteRating(packId: Long, id: String): Int


    @Query("DELETE FROM media_rating_score WHERE pack_id = :packId AND category_id = :categoryId")
    suspend fun deleteRatingScoresForCategory(packId: Long, categoryId: String): Int


    @Query("DELETE FROM media_rating_score WHERE pack_id = :packId AND category_id NOT IN (:ids)")
    suspend fun deleteRatingScoresOutsideCategories(packId: Long, ids: List<String>): Int

}
