package org.rankoffline.app

import androidx.room.Dao
import androidx.room.Query

/** SQL contract extracted from PersonalDataDao; methods inherited by its @Dao facade. */
interface PersonalBackupReadDao {
    @Query("SELECT * FROM media_rating")
    suspend fun allRatings(): List<MediaRatingEntity>


    @Query("SELECT * FROM media_rating_score")
    suspend fun allRatingScores(): List<MediaRatingScoreEntity>


    @Query("SELECT * FROM media_group_rating")
    suspend fun allGroupRatings(): List<MediaGroupRatingEntity>


    @Query("SELECT * FROM media_group_rating_score")
    suspend fun allGroupRatingScores(): List<MediaGroupRatingScoreEntity>

}
