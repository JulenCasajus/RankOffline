package org.rankoffline.app

import androidx.room.Dao
import androidx.room.Query

/** SQL contract extracted from MediaGroupDao; methods inherited by its @Dao facade. */
interface GroupRatingReadDao {
    /** Raw member ratings for the shared domain-level derived score calculation. */
    @Query("""SELECT r.* FROM media_group_member m
        JOIN media_rating r ON r.pack_id=m.pack_id AND r.media_id=m.media_id
        WHERE m.pack_id=:packId AND m.group_id=:groupId AND r.final_score IS NOT NULL""")
    suspend fun ratedMemberRatings(packId: Long, groupId: String): List<MediaRatingEntity>


    @Query("""SELECT s.* FROM media_group_member m
        JOIN media_rating r ON r.pack_id=m.pack_id AND r.media_id=m.media_id
        JOIN media_rating_score s ON s.pack_id=r.pack_id AND s.media_id=r.media_id
        WHERE m.pack_id=:packId AND m.group_id=:groupId AND r.final_score IS NOT NULL""")
    suspend fun ratedMemberScores(packId: Long, groupId: String): List<MediaRatingScoreEntity>


    @Query("SELECT * FROM media_group_rating WHERE pack_id = :packId AND group_id = :id")
    suspend fun rating(packId: Long, id: String): MediaGroupRatingEntity?


    @Query("SELECT * FROM media_group_rating WHERE pack_id = :packId AND group_id IN (:ids)")
    suspend fun ratingsByIds(packId: Long, ids: List<String>): List<MediaGroupRatingEntity>


    @Query("SELECT * FROM media_group_rating_score WHERE pack_id = :packId AND group_id = :id ORDER BY category_id")
    suspend fun ratingScores(packId: Long, id: String): List<MediaGroupRatingScoreEntity>

}
