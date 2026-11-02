package org.rankoffline.app

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** SQL contract extracted from PersonalDataDao; methods inherited by its @Dao facade. */
interface PersonalBackupWriteDao {
    @Upsert
    suspend fun upsertRatings(values: List<MediaRatingEntity>)


    @Upsert
    suspend fun upsertRatingScores(values: List<MediaRatingScoreEntity>)


    @Upsert
    suspend fun upsertGroupRatings(values: List<MediaGroupRatingEntity>)


    @Upsert
    suspend fun upsertGroupRatingScores(values: List<MediaGroupRatingScoreEntity>)


    @Query("DELETE FROM media_rating")
    suspend fun deleteAllRatings()


    @Query("DELETE FROM media_group_rating")
    suspend fun deleteAllGroupRatings()


    @Query("DELETE FROM media_rating_score WHERE pack_id = :packId AND media_id IN (:ids)")
    suspend fun deleteRatingScores(packId: Long, ids: List<String>)


    @Query("DELETE FROM media_group_rating_score WHERE pack_id = :packId AND group_id IN (:ids)")
    suspend fun deleteGroupRatingScores(packId: Long, ids: List<String>)


    /** Destructive personal-data operation: never invoked by removing a catalog pack. */
    @Query("DELETE FROM media_rating WHERE pack_id = :packId")
    suspend fun deletePersonalItemRatings(packId: Long)


    @Query("DELETE FROM media_group_rating WHERE pack_id = :packId")
    suspend fun deletePersonalGroupRatings(packId: Long)

}
