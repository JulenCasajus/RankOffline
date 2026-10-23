package org.rankoffline.app

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import androidx.sqlite.db.SupportSQLiteQuery

data class GenericVisibleEntryId(
    val id: String,
    @ColumnInfo(name = "entry_type") val entryType: Int
)

data class MediaGroupCatalogFields(
    val id: String,
    @ColumnInfo(name = "media_type") val mediaType: String,
    @ColumnInfo(name = "group_type") val groupType: String,
    val provider: String?,
    @ColumnInfo(name = "provider_group_id") val providerGroupId: String?,
    @ColumnInfo(name = "display_title") val displayTitle: String,
    @ColumnInfo(name = "representative_media_id") val representativeMediaId: String?,
    @ColumnInfo(name = "member_count") val memberCount: Int,
    @ColumnInfo(name = "group_popularity") val popularity: Double?,
    @ColumnInfo(name = "group_popularity_rank") val popularityRank: Int?,
    @ColumnInfo(name = "remote_cover_ref") val remoteCoverRef: String?
)

data class MediaIdType(
    val id: String,
    @ColumnInfo(name = "media_type") val mediaType: String
)

data class SharedCoverIdentityRow(
    @ColumnInfo(name = "target_id") val targetId: String,
    @ColumnInfo(name = "cover_media_id") val coverMediaId: String
)

data class MediaMergedCatalogRow(
    @Embedded val group: MediaGroupEntity,
    @ColumnInfo(name = "rated_count") val ratedCount: Int,
    @ColumnInfo(name = "final_score") val finalScore: Double?,
    @ColumnInfo(name = "quality_score") val qualityScore: Double?,
    @ColumnInfo(name = "additive_score") val additiveScore: Double?,
    @ColumnInfo(name = "category_score") val categoryScore: Double?,
    @ColumnInfo(name = "has_explicit_rating") val hasExplicitRating: Boolean,
    @ColumnInfo(name = "representative_local_cover_path") val representativeLocalCoverPath: String? = null,
    @ColumnInfo(name = "representative_cover_resolution_state") val representativeCoverResolutionState: String? = null
) {
    fun toListEntry(): MediaListEntry.Group = MediaListEntry.Group(
        value = group.copy(
            localCoverPath = group.localCoverPath ?: representativeLocalCoverPath,
            coverResolutionState = if (group.localCoverPath != null) group.coverResolutionState
                else representativeCoverResolutionState ?: group.coverResolutionState
        ).toDomain(),
        finalScore = finalScore,
        qualityScore = qualityScore,
        additiveScore = additiveScore,
        categoryScore = categoryScore,
        popularity = group.popularity,
        popularityRank = group.popularityRank,
        memberCount = group.memberCount,
        ratedCount = ratedCount,
        representativeMediaId = group.representativeMediaId,
        derived = !hasExplicitRating && ratedCount > 0
    )
}

@Dao
interface MediaGroupDao {
    @RawQuery
    suspend fun visibleCatalogEntryIds(query: SupportSQLiteQuery): List<GenericVisibleEntryId>

    @Query("SELECT id FROM media_group WHERE id IN (:groupIds)")
    suspend fun existingGroupIds(groupIds: List<String>): List<String>

    @Query(
        """
        SELECT COUNT(*) FROM media_group
        WHERE media_type = :mediaType AND member_count > 1
        """
    )
    suspend fun usableGroupCount(mediaType: String): Long

    @Query("SELECT COUNT(*) FROM media_group WHERE media_type = :mediaType AND group_type = :groupType")
    suspend fun groupCount(mediaType: String, groupType: String): Long

    @Query(
        "SELECT COUNT(*) FROM media_group_member member " +
            "INNER JOIN media_group mediaGroup ON mediaGroup.id = member.group_id " +
            "WHERE mediaGroup.media_type = :mediaType AND mediaGroup.group_type = :groupType"
    )
    suspend fun membershipCount(mediaType: String, groupType: String): Long

    @Query(
        """
        WITH memberships AS (
            SELECT member.media_id AS target_id, mediaGroup.id AS group_id
            FROM media_group_member member
            INNER JOIN media_group mediaGroup ON mediaGroup.id=member.group_id
            WHERE mediaGroup.media_type=:mediaType AND mediaGroup.group_type=:groupType
              AND mediaGroup.member_count>1 AND member.media_id IN (:mediaIds)
        ), chosenGroups AS (
            SELECT target_id, MIN(group_id) AS group_id
            FROM memberships GROUP BY target_id
        )
        SELECT chosen.target_id AS target_id,
               (SELECT firstMember.media_id FROM media_group_member firstMember
                WHERE firstMember.group_id=chosen.group_id
                ORDER BY firstMember.position ASC, firstMember.media_id ASC LIMIT 1) AS cover_media_id
        FROM chosenGroups chosen
        """
    )
    suspend fun sharedCoverIdentitiesForItems(
        mediaType: String,
        groupType: String,
        mediaIds: List<String>
    ): List<SharedCoverIdentityRow>

    @Query(
        """
        SELECT mediaGroup.id AS target_id,
               (SELECT firstMember.media_id FROM media_group_member firstMember
                WHERE firstMember.group_id=mediaGroup.id
                ORDER BY firstMember.position ASC, firstMember.media_id ASC LIMIT 1) AS cover_media_id
        FROM media_group mediaGroup
        WHERE mediaGroup.id IN (:groupIds) AND mediaGroup.member_count>1
        """
    )
    suspend fun sharedCoverIdentitiesForGroups(groupIds: List<String>): List<SharedCoverIdentityRow>

    @Query(
        """
        WITH groupCandidates AS (
            SELECT (
                       SELECT firstMember.media_id
                       FROM media_group_member firstMember
                       WHERE firstMember.group_id=mediaGroup.id
                       ORDER BY firstMember.position ASC, firstMember.media_id ASC
                       LIMIT 1
                   ) AS media_id,
                   mediaGroup.group_popularity_rank AS popularity_rank,
                   mediaGroup.group_popularity AS popularity,
                   mediaGroup.display_title AS title,
                   mediaGroup.id AS entry_id
            FROM media_group mediaGroup
            WHERE mediaGroup.media_type=:mediaType AND mediaGroup.group_type=:groupType
              AND mediaGroup.member_count>1
        ), ungroupedCandidates AS (
            SELECT item.id AS media_id, item.popularity_rank AS popularity_rank,
                   item.popularity AS popularity, item.title AS title, item.id AS entry_id
            FROM media_item item
            WHERE item.media_type=:mediaType AND NOT EXISTS (
                SELECT 1 FROM media_group_member membership
                INNER JOIN media_group mediaGroup ON mediaGroup.id=membership.group_id
                WHERE membership.media_id=item.id AND mediaGroup.media_type=:mediaType
                  AND mediaGroup.group_type=:groupType AND mediaGroup.member_count>1
            )
        ), candidates AS (
            SELECT * FROM groupCandidates WHERE media_id IS NOT NULL
            UNION ALL
            SELECT * FROM ungroupedCandidates
        ), distinctCovers AS (
            SELECT media_id, MIN(popularity_rank) AS popularity_rank,
                   MAX(popularity) AS popularity, MIN(title) AS title, MIN(entry_id) AS entry_id
            FROM candidates GROUP BY media_id
        )
        SELECT item.* FROM distinctCovers candidate
        INNER JOIN media_item item ON item.id=candidate.media_id
        ORDER BY
          CASE
              WHEN candidate.popularity_rank IS NOT NULL THEN 0
              WHEN candidate.popularity IS NOT NULL THEN 1
              ELSE 2
          END ASC,
          candidate.popularity_rank ASC,
          candidate.popularity DESC,
          candidate.title COLLATE NOCASE ASC, candidate.entry_id ASC, candidate.media_id ASC
        LIMIT :limit
        """
    )
    suspend fun topLogicalCoverItems(
        mediaType: String,
        groupType: String,
        limit: Int
    ): List<MediaItemEntity>

    @Query(
        """
        WITH memberRatings AS (
            SELECT ratedMember.group_id AS group_id,
                   COUNT(rating.final_score) AS rated_count,
                   AVG(rating.final_score) AS final_score,
                   AVG(rating.quality_score) AS quality_score,
                   AVG(rating.additive_score) AS additive_score
            FROM media_group_member ratedMember
            INNER JOIN media_rating rating ON rating.media_id = ratedMember.media_id
            WHERE rating.pack_id = :packId AND rating.final_score IS NOT NULL
            GROUP BY ratedMember.group_id
        )
        SELECT mediaGroup.*,
               COALESCE(memberRatings.rated_count, 0) AS rated_count,
               COALESCE(groupRating.final_score, memberRatings.final_score) AS final_score,
               COALESCE(groupRating.quality_score, memberRatings.quality_score) AS quality_score,
               COALESCE(groupRating.additive_score, memberRatings.additive_score) AS additive_score,
               NULL AS category_score,
               CASE WHEN groupRating.final_score IS NULL THEN 0 ELSE 1 END AS has_explicit_rating
               ,representative.local_cover_path AS representative_local_cover_path
               ,representative.cover_resolution_state AS representative_cover_resolution_state
        FROM media_group mediaGroup
        LEFT JOIN media_group_rating groupRating ON groupRating.group_id = mediaGroup.id
            AND groupRating.pack_id = :packId
        LEFT JOIN memberRatings ON memberRatings.group_id = mediaGroup.id
        LEFT JOIN media_item representative ON representative.id = mediaGroup.representative_media_id
        WHERE mediaGroup.media_type = :mediaType AND mediaGroup.group_type = :groupType
          AND mediaGroup.member_count > 1
          AND (:ratedOnly = 0 OR groupRating.final_score IS NOT NULL OR memberRatings.rated_count > 0)
          AND (:query = '' OR mediaGroup.display_title LIKE '%' || :query || '%' COLLATE NOCASE
               OR EXISTS (
                   SELECT 1 FROM media_group_member searchMember
                   INNER JOIN media_item searchItem ON searchItem.id = searchMember.media_id
                   WHERE searchMember.group_id = mediaGroup.id
                     AND (searchItem.title LIKE '%' || :query || '%' COLLATE NOCASE
                          OR searchItem.original_title LIKE '%' || :query || '%' COLLATE NOCASE
                          OR EXISTS (SELECT 1 FROM media_alias alias
                                     WHERE alias.media_id = searchItem.id
                                       AND alias.title LIKE '%' || :query || '%' COLLATE NOCASE))
               ))
          AND (:genre IS NULL OR EXISTS (
              SELECT 1 FROM media_group_member filterMember
              INNER JOIN media_genre genreRow ON genreRow.media_id = filterMember.media_id
              WHERE filterMember.group_id = mediaGroup.id AND genreRow.genre = :genre
          ))
          AND (:year IS NULL OR EXISTS (
              SELECT 1 FROM media_group_member filterMember
              INNER JOIN media_item filterItem ON filterItem.id = filterMember.media_id
              WHERE filterMember.group_id = mediaGroup.id AND filterItem.year = :year
          ))
          AND (:format IS NULL OR EXISTS (
              SELECT 1 FROM media_group_member filterMember
              INNER JOIN media_item filterItem ON filterItem.id = filterMember.media_id
              WHERE filterMember.group_id = mediaGroup.id AND filterItem.format = :format
          ))
          AND (:demographic IS NULL OR EXISTS (
              SELECT 1 FROM media_group_member filterMember
              INNER JOIN media_item filterItem ON filterItem.id = filterMember.media_id
              WHERE filterMember.group_id = mediaGroup.id AND filterItem.demographic = :demographic
          ))
          AND (:creditName IS NULL OR EXISTS (
              SELECT 1 FROM media_group_member filterMember
              INNER JOIN media_credit credit ON credit.media_id = filterMember.media_id
              WHERE filterMember.group_id = mediaGroup.id
                AND credit.role = :creditRole AND credit.name = :creditName
          ))
        ORDER BY
          CASE WHEN :sortOrder = 'TITLE_ASC' THEN mediaGroup.display_title END COLLATE NOCASE ASC,
          CASE WHEN :sortOrder = 'TITLE_DESC' THEN mediaGroup.display_title END COLLATE NOCASE DESC,
          CASE WHEN :sortOrder IN ('POPULARITY', 'POPULARITY_REVERSE')
                    AND mediaGroup.group_popularity_rank IS NULL AND mediaGroup.group_popularity IS NULL
               THEN 1 ELSE 0 END ASC,
          CASE WHEN :sortOrder = 'POPULARITY' THEN mediaGroup.group_popularity_rank END ASC,
          CASE WHEN :sortOrder = 'POPULARITY_REVERSE' THEN mediaGroup.group_popularity_rank END DESC,
          CASE WHEN :sortOrder = 'POPULARITY' AND mediaGroup.group_popularity_rank IS NULL THEN mediaGroup.group_popularity END DESC,
          CASE WHEN :sortOrder = 'POPULARITY_REVERSE' AND mediaGroup.group_popularity_rank IS NULL THEN mediaGroup.group_popularity END ASC,
          mediaGroup.display_title COLLATE NOCASE ASC, mediaGroup.id ASC
        LIMIT :limit
        """
    )
    suspend fun matchingGroupRows(
        mediaType: String,
        packId: String,
        groupType: String,
        query: String,
        genre: String?,
        format: String?,
        year: Int?,
        creditRole: String?,
        creditName: String?,
        demographic: String?,
        sortOrder: String,
        ratedOnly: Boolean,
        limit: Int
    ): List<MediaMergedCatalogRow>

    /**
     * Ranking is intentionally unpaged, but it must be proportional to user-owned ratings rather
     * than to every catalog group. The candidate CTE is anchored by explicit group ratings and
     * rated members; filters are evaluated only for those candidates. Relationship data remains
     * the precomputed graph imported from the pack.
     */
    @Query(
        """
        WITH eligibleGroups AS (
            SELECT rating.group_id
            FROM media_group_rating rating
            WHERE rating.pack_id = :packId AND rating.final_score IS NOT NULL
            UNION
            SELECT member.group_id
            FROM media_rating rating
            INNER JOIN media_group_member member ON member.media_id=rating.media_id
            WHERE rating.pack_id = :packId AND rating.final_score IS NOT NULL
        ),
        memberRatings AS (
            SELECT member.group_id AS group_id,
                   COUNT(rating.final_score) AS rated_count,
                   AVG(rating.final_score) AS final_score,
                   AVG(rating.quality_score) AS quality_score,
                   AVG(rating.additive_score) AS additive_score
            FROM eligibleGroups eligible
            INNER JOIN media_group_member member ON member.group_id=eligible.group_id
            INNER JOIN media_rating rating ON rating.media_id=member.media_id
            WHERE rating.pack_id = :packId AND rating.final_score IS NOT NULL
            GROUP BY member.group_id
        ),
        memberCategoryScores AS (
            SELECT member.group_id AS group_id, AVG(score.score) AS category_score
            FROM eligibleGroups eligible
            INNER JOIN media_group_member member ON member.group_id=eligible.group_id
            INNER JOIN media_rating rating ON rating.media_id=member.media_id
            INNER JOIN media_rating_score score ON score.media_id=rating.media_id
            WHERE :categoryId IS NOT NULL
              AND rating.pack_id=:packId
              AND score.pack_id=:packId
              AND rating.final_score IS NOT NULL
              AND score.category_id=:categoryId
              AND score.applicable=1
              AND score.score IS NOT NULL
            GROUP BY member.group_id
        )
        SELECT mediaGroup.*,
               COALESCE(memberRatings.rated_count,0) AS rated_count,
               COALESCE(groupRating.final_score,memberRatings.final_score) AS final_score,
               COALESCE(groupRating.quality_score,memberRatings.quality_score) AS quality_score,
               COALESCE(groupRating.additive_score,memberRatings.additive_score) AS additive_score,
               CASE WHEN :categoryId IS NULL THEN NULL
                    ELSE COALESCE(groupCategory.score,memberCategoryScores.category_score)
               END AS category_score,
               CASE WHEN groupRating.final_score IS NULL THEN 0 ELSE 1 END AS has_explicit_rating,
               representative.local_cover_path AS representative_local_cover_path,
               representative.cover_resolution_state AS representative_cover_resolution_state
        FROM eligibleGroups eligible
        INNER JOIN media_group mediaGroup ON mediaGroup.id=eligible.group_id
        LEFT JOIN media_group_rating groupRating ON groupRating.group_id=mediaGroup.id
            AND groupRating.pack_id=:packId
        LEFT JOIN memberRatings ON memberRatings.group_id=mediaGroup.id
        LEFT JOIN memberCategoryScores ON memberCategoryScores.group_id=mediaGroup.id
        LEFT JOIN media_group_rating_score groupCategory
          ON groupCategory.group_id=mediaGroup.id
         AND groupCategory.pack_id=:packId
         AND groupCategory.category_id=:categoryId
         AND groupCategory.applicable=1
        LEFT JOIN media_item representative ON representative.id=mediaGroup.representative_media_id
        WHERE mediaGroup.media_type=:mediaType
          AND mediaGroup.group_type=:groupType
          AND mediaGroup.member_count>1
          AND (:genre IS NULL OR EXISTS (
              SELECT 1 FROM media_group_member filterMember
              INNER JOIN media_genre genreRow ON genreRow.media_id=filterMember.media_id
              WHERE filterMember.group_id=mediaGroup.id AND genreRow.genre=:genre
          ))
          AND (:year IS NULL OR EXISTS (
              SELECT 1 FROM media_group_member filterMember
              INNER JOIN media_item filterItem ON filterItem.id=filterMember.media_id
              WHERE filterMember.group_id=mediaGroup.id AND filterItem.year=:year
          ))
          AND (:format IS NULL OR EXISTS (
              SELECT 1 FROM media_group_member filterMember
              INNER JOIN media_item filterItem ON filterItem.id=filterMember.media_id
              WHERE filterMember.group_id=mediaGroup.id AND filterItem.format=:format
          ))
          AND (:demographic IS NULL OR EXISTS (
              SELECT 1 FROM media_group_member filterMember
              INNER JOIN media_item filterItem ON filterItem.id=filterMember.media_id
              WHERE filterMember.group_id=mediaGroup.id AND filterItem.demographic=:demographic
          ))
          AND (:creditName IS NULL OR EXISTS (
              SELECT 1 FROM media_group_member filterMember
              INNER JOIN media_credit credit ON credit.media_id=filterMember.media_id
              WHERE filterMember.group_id=mediaGroup.id
                AND credit.role=:creditRole AND credit.name=:creditName
          ))
        """
    )
    suspend fun ratedGroupRows(
        mediaType: String,
        packId: String,
        groupType: String,
        genre: String?,
        format: String?,
        year: Int?,
        creditRole: String?,
        creditName: String?,
        demographic: String?,
        categoryId: String?
    ): List<MediaMergedCatalogRow>

    @Query(
        """
        WITH memberRatings AS (
            SELECT ratedMember.group_id AS group_id,
                   COUNT(rating.final_score) AS rated_count,
                   AVG(rating.final_score) AS final_score,
                   AVG(rating.quality_score) AS quality_score,
                   AVG(rating.additive_score) AS additive_score
            FROM media_group_member ratedMember
            INNER JOIN media_rating rating ON rating.media_id=ratedMember.media_id
            WHERE rating.pack_id=:packId AND ratedMember.group_id IN (:groupIds)
              AND rating.final_score IS NOT NULL
            GROUP BY ratedMember.group_id
        )
        SELECT mediaGroup.*,
               COALESCE(memberRatings.rated_count,0) AS rated_count,
               COALESCE(groupRating.final_score,memberRatings.final_score) AS final_score,
               COALESCE(groupRating.quality_score,memberRatings.quality_score) AS quality_score,
               COALESCE(groupRating.additive_score,memberRatings.additive_score) AS additive_score,
               NULL AS category_score,
               CASE WHEN groupRating.final_score IS NULL THEN 0 ELSE 1 END AS has_explicit_rating,
               representative.local_cover_path AS representative_local_cover_path,
               representative.cover_resolution_state AS representative_cover_resolution_state
        FROM media_group mediaGroup
        LEFT JOIN media_group_rating groupRating ON groupRating.group_id=mediaGroup.id
            AND groupRating.pack_id=:packId
        LEFT JOIN memberRatings ON memberRatings.group_id=mediaGroup.id
        LEFT JOIN media_item representative ON representative.id=mediaGroup.representative_media_id
        WHERE mediaGroup.id IN (:groupIds)
        """
    )
    suspend fun groupRowsByIds(groupIds: List<String>, packId: String): List<MediaMergedCatalogRow>

    @Query("SELECT id, media_type FROM media_item WHERE id IN (:mediaIds)")
    suspend fun mediaIdTypes(mediaIds: List<String>): List<MediaIdType>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNewGroups(groups: List<MediaGroupEntity>): List<Long>

    @Update(entity = MediaGroupEntity::class)
    suspend fun updateCatalogFields(groups: List<MediaGroupCatalogFields>): Int

    @Query("DELETE FROM media_group_member WHERE group_id IN (:groupIds)")
    suspend fun deleteMembers(groupIds: List<String>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMembers(members: List<MediaGroupMemberEntity>)

    @Query("SELECT * FROM media_group WHERE id = :groupId")
    suspend fun group(groupId: String): MediaGroupEntity?

    @Query("SELECT local_cover_path FROM media_group WHERE id = :groupId")
    fun observeLocalCoverPath(groupId: String): Flow<String?>

    @Query(
        "UPDATE media_group SET local_cover_path = :path, cover_resolution_state = :state " +
            "WHERE id = :groupId"
    )
    suspend fun updateGroupCover(groupId: String, path: String?, state: String): Int

    @Query("SELECT * FROM media_group WHERE media_type = :mediaType AND local_cover_path IS NOT NULL")
    suspend fun groupsWithLocalCovers(mediaType: String): List<MediaGroupEntity>

    @Query(
        "UPDATE media_group SET local_cover_path = NULL, cover_resolution_state = 'UNKNOWN' " +
            "WHERE media_type = :mediaType AND local_cover_path IS NOT NULL"
    )
    suspend fun clearLocalCovers(mediaType: String): Int

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

    @Query("SELECT * FROM media_group_rating WHERE pack_id = :packId AND group_id = :groupId")
    suspend fun groupRating(packId: String, groupId: String): MediaGroupRatingEntity?

    @Query("SELECT * FROM media_group_rating_score WHERE pack_id = :packId AND group_id = :groupId")
    suspend fun groupRatingScores(packId: String, groupId: String): List<MediaGroupRatingScoreEntity>

    @Query(
        """
        SELECT rating.*
        FROM media_group_member member
        INNER JOIN media_rating rating ON rating.media_id = member.media_id
        WHERE member.group_id = :groupId AND rating.pack_id = :packId
          AND rating.final_score IS NOT NULL
        ORDER BY member.position ASC, member.media_id ASC
        """
    )
    suspend fun ratedMemberRatings(packId: String, groupId: String): List<MediaRatingEntity>

    @Query(
        """
        SELECT score.*
        FROM media_group_member member
        INNER JOIN media_rating rating ON rating.media_id = member.media_id
        INNER JOIN media_rating_score score ON score.media_id = member.media_id
        WHERE member.group_id = :groupId AND rating.pack_id = :packId
          AND score.pack_id = :packId AND rating.final_score IS NOT NULL
        ORDER BY member.position ASC, member.media_id ASC, score.category_id ASC
        """
    )
    suspend fun ratedMemberScores(packId: String, groupId: String): List<MediaRatingScoreEntity>

    @Upsert
    suspend fun upsertGroupRating(rating: MediaGroupRatingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroupRatingScores(scores: List<MediaGroupRatingScoreEntity>)

    @Query("DELETE FROM media_group_rating_score WHERE pack_id = :packId AND group_id = :groupId")
    suspend fun deleteGroupRatingScores(packId: String, groupId: String)

    @Transaction
    suspend fun replaceGroupRatingAndScores(
        rating: MediaGroupRatingEntity,
        scores: List<MediaGroupRatingScoreEntity>
    ) {
        upsertGroupRating(rating)
        deleteGroupRatingScores(rating.packId, rating.groupId)
        if (scores.isNotEmpty()) upsertGroupRatingScores(scores)
    }

    @Query("DELETE FROM media_group_rating WHERE pack_id = :packId AND group_id = :groupId")
    suspend fun deleteGroupRating(packId: String, groupId: String)

    @Query(
        """
        DELETE FROM media_group_rating_score
        WHERE pack_id = :packId AND category_id = :categoryId
        """
    )
    suspend fun deleteGroupRatingScoresForCategory(packId: String, categoryId: String)

    @Query(
        """
        DELETE FROM media_group_rating_score
        WHERE pack_id = :packId AND category_id NOT IN (:categoryIds)
        """
    )
    suspend fun deleteGroupRatingScoresOutsideCategories(packId: String, categoryIds: List<String>)

    @Query(
        """
        DELETE FROM media_group_rating_score
        WHERE pack_id = :packId
        """
    )
    suspend fun deleteAllGroupRatingScores(packId: String)
}
