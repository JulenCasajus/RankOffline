package org.rankoffline.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_item WHERE id = :mediaId")
    suspend fun item(mediaId: String): MediaItemEntity?

    @Query("SELECT media_type FROM media_item WHERE id = :mediaId")
    suspend fun mediaType(mediaId: String): String?

    @Upsert
    suspend fun upsertItem(item: MediaItemEntity)

    @Upsert
    suspend fun upsertItems(items: List<MediaItemEntity>)

    @Query("SELECT * FROM media_rating WHERE media_id = :mediaId")
    suspend fun rating(mediaId: String): MediaRatingEntity?

    @Query("SELECT * FROM media_rating_score WHERE media_id = :mediaId")
    suspend fun ratingScores(mediaId: String): List<MediaRatingScoreEntity>

    @Upsert
    suspend fun upsertRating(rating: MediaRatingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRatingScores(scores: List<MediaRatingScoreEntity>)

    @Query("DELETE FROM media_rating_score WHERE media_id = :mediaId")
    suspend fun deleteRatingScores(mediaId: String)

    @Transaction
    suspend fun replaceRatingAndScores(
        rating: MediaRatingEntity,
        scores: List<MediaRatingScoreEntity>
    ) {
        upsertRating(rating)
        deleteRatingScores(rating.mediaId)
        if (scores.isNotEmpty()) upsertRatingScores(scores)
    }

    @Query("DELETE FROM media_rating WHERE media_id = :mediaId")
    suspend fun deleteRating(mediaId: String)

    @Query(
        """
        DELETE FROM media_rating_score
        WHERE category_id = :categoryId
          AND media_id IN (SELECT id FROM media_item WHERE media_type = :mediaType)
        """
    )
    suspend fun deleteRatingScoresForCategory(mediaType: String, categoryId: String)

    @Query(
        """
        DELETE FROM media_rating_score
        WHERE category_id NOT IN (:categoryIds)
          AND media_id IN (SELECT id FROM media_item WHERE media_type = :mediaType)
        """
    )
    suspend fun deleteRatingScoresOutsideCategories(mediaType: String, categoryIds: List<String>)

    @Query(
        """
        DELETE FROM media_rating_score
        WHERE media_id IN (SELECT id FROM media_item WHERE media_type = :mediaType)
        """
    )
    suspend fun deleteAllRatingScores(mediaType: String)
}
