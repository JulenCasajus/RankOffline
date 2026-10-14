package org.rankoffline.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface MediaGroupDao {
    @Query("SELECT * FROM media_group WHERE id = :groupId")
    suspend fun group(groupId: String): MediaGroupEntity?

    @Query("SELECT media_type FROM media_group WHERE id = :groupId")
    suspend fun groupMediaType(groupId: String): String?

    @Query("SELECT media_type FROM media_item WHERE id = :mediaId")
    suspend fun memberMediaType(mediaId: String): String?

    @Query(
        """
        SELECT item.*
        FROM media_group_member member
        INNER JOIN media_item item ON item.id = member.media_id
        WHERE member.group_id = :groupId
        ORDER BY member.position ASC, member.media_id ASC
        """
    )
    suspend fun groupMembers(groupId: String): List<MediaItemEntity>

    @Upsert
    suspend fun upsertGroup(group: MediaGroupEntity)

    @Upsert
    suspend fun upsertGroupMembers(members: List<MediaGroupMemberEntity>)

    @Transaction
    suspend fun upsertGroupWithMembers(
        group: MediaGroupEntity,
        members: List<MediaGroupMemberEntity>
    ) {
        upsertGroup(group)
        if (members.isNotEmpty()) upsertGroupMembers(members)
    }

    @Query("SELECT * FROM media_group_rating WHERE group_id = :groupId")
    suspend fun groupRating(groupId: String): MediaGroupRatingEntity?

    @Query("SELECT * FROM media_group_rating_score WHERE group_id = :groupId")
    suspend fun groupRatingScores(groupId: String): List<MediaGroupRatingScoreEntity>

    @Query(
        """
        SELECT rating.*
        FROM media_group_member member
        INNER JOIN media_rating rating ON rating.media_id = member.media_id
        WHERE member.group_id = :groupId AND rating.final_score IS NOT NULL
        ORDER BY member.position ASC, member.media_id ASC
        """
    )
    suspend fun ratedMemberRatings(groupId: String): List<MediaRatingEntity>

    @Query(
        """
        SELECT score.*
        FROM media_group_member member
        INNER JOIN media_rating rating ON rating.media_id = member.media_id
        INNER JOIN media_rating_score score ON score.media_id = member.media_id
        WHERE member.group_id = :groupId AND rating.final_score IS NOT NULL
        ORDER BY member.position ASC, member.media_id ASC, score.category_id ASC
        """
    )
    suspend fun ratedMemberScores(groupId: String): List<MediaRatingScoreEntity>

    @Upsert
    suspend fun upsertGroupRating(rating: MediaGroupRatingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroupRatingScores(scores: List<MediaGroupRatingScoreEntity>)

    @Query("DELETE FROM media_group_rating_score WHERE group_id = :groupId")
    suspend fun deleteGroupRatingScores(groupId: String)

    @Transaction
    suspend fun replaceGroupRatingAndScores(
        rating: MediaGroupRatingEntity,
        scores: List<MediaGroupRatingScoreEntity>
    ) {
        upsertGroupRating(rating)
        deleteGroupRatingScores(rating.groupId)
        if (scores.isNotEmpty()) upsertGroupRatingScores(scores)
    }

    @Query("DELETE FROM media_group_rating WHERE group_id = :groupId")
    suspend fun deleteGroupRating(groupId: String)

    @Query(
        """
        DELETE FROM media_group_rating_score
        WHERE category_id = :categoryId
          AND group_id IN (SELECT id FROM media_group WHERE media_type = :mediaType)
        """
    )
    suspend fun deleteGroupRatingScoresForCategory(mediaType: String, categoryId: String)

    @Query(
        """
        DELETE FROM media_group_rating_score
        WHERE category_id NOT IN (:categoryIds)
          AND group_id IN (SELECT id FROM media_group WHERE media_type = :mediaType)
        """
    )
    suspend fun deleteGroupRatingScoresOutsideCategories(mediaType: String, categoryIds: List<String>)

    @Query(
        """
        DELETE FROM media_group_rating_score
        WHERE group_id IN (SELECT id FROM media_group WHERE media_type = :mediaType)
        """
    )
    suspend fun deleteAllGroupRatingScores(mediaType: String)
}
