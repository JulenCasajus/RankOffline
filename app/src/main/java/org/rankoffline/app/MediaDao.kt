package org.rankoffline.app

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class MediaItemRatingRow(
    @Embedded val item: MediaItemEntity,
    @ColumnInfo(name = "rating_additive_score") val additiveScore: Double?,
    @ColumnInfo(name = "rating_quality_score") val qualityScore: Double?,
    @ColumnInfo(name = "rating_final_score") val finalScore: Double?,
    @ColumnInfo(name = "rating_profile_id") val profileId: String?,
    @ColumnInfo(name = "rating_pack_id") val ratingPackId: String?,
    @ColumnInfo(name = "filter_genre") val filterGenre: String? = null,
    @ColumnInfo(name = "filter_credit") val filterCredit: String? = null
) {
    fun toDomain(categoryScore: Double? = null): MediaItem = item.toDomain(
        rating = finalScore?.let {
            MediaRatingEntity(
                mediaId = item.id,
                additiveScore = additiveScore ?: 0.0,
                qualityScore = qualityScore,
                finalScore = finalScore,
                notes = "",
                status = "",
                updatedAt = 0L,
                profileId = profileId,
                packId = ratingPackId.orEmpty()
            )
        },
        categoryScore = categoryScore,
        genre = filterGenre,
        filterCredit = filterCredit
    )
}

data class MediaCategoryScoreRow(
    @ColumnInfo(name = "media_id") val mediaId: String,
    val score: Double?,
    val applicable: Boolean
)

@Dao
interface MediaDao {
    @Query("SELECT COUNT(*) FROM media_item WHERE media_type = :mediaType")
    suspend fun itemCount(mediaType: String): Int

    @Query(
        """
        SELECT item.*,
               rating.additive_score AS rating_additive_score,
               rating.quality_score AS rating_quality_score,
               rating.final_score AS rating_final_score,
               rating.profile_id AS rating_profile_id,
               rating.pack_id AS rating_pack_id,
               (SELECT genreRow.genre FROM media_genre genreRow
                WHERE genreRow.media_id = item.id ORDER BY genreRow.genre COLLATE NOCASE LIMIT 1) AS filter_genre,
               (SELECT credit.name FROM media_credit credit
                WHERE credit.media_id = item.id AND credit.role = :creditRole
                ORDER BY credit.name COLLATE NOCASE LIMIT 1) AS filter_credit
        FROM media_item item
        LEFT JOIN media_rating rating ON rating.media_id = item.id AND rating.pack_id = :packId
        WHERE item.media_type = :mediaType
          AND (:query = '' OR item.title LIKE '%' || :query || '%' COLLATE NOCASE
               OR item.original_title LIKE '%' || :query || '%' COLLATE NOCASE
               OR EXISTS (SELECT 1 FROM media_alias alias
                          WHERE alias.media_id = item.id
                            AND alias.title LIKE '%' || :query || '%' COLLATE NOCASE))
          AND (:genre IS NULL OR EXISTS (SELECT 1 FROM media_genre genreRow
                                        WHERE genreRow.media_id = item.id AND genreRow.genre = :genre))
          AND (:year IS NULL OR item.year = :year)
          AND (:format IS NULL OR item.format = :format)
          AND (:demographic IS NULL OR item.demographic = :demographic)
          AND (:creditName IS NULL OR EXISTS (SELECT 1 FROM media_credit credit
                                             WHERE credit.media_id = item.id
                                               AND credit.role = :creditRole
                                               AND credit.name = :creditName))
          AND (:excludedGroupType IS NULL OR item.id NOT IN (
              SELECT excludedMember.media_id FROM media_group_member excludedMember
              INNER JOIN media_group excludedGroup ON excludedGroup.id = excludedMember.group_id
              WHERE excludedGroup.media_type = :mediaType
                AND excludedGroup.group_type = :excludedGroupType
          ))
        ORDER BY
          CASE WHEN :sortOrder = 'TITLE_ASC' THEN item.title END COLLATE NOCASE ASC,
          CASE WHEN :sortOrder = 'TITLE_DESC' THEN item.title END COLLATE NOCASE DESC,
          CASE WHEN :sortOrder IN ('POPULARITY', 'POPULARITY_REVERSE')
                    AND item.popularity_rank IS NULL AND item.popularity IS NULL
               THEN 1 ELSE 0 END ASC,
          CASE WHEN :sortOrder = 'POPULARITY' THEN item.popularity_rank END ASC,
          CASE WHEN :sortOrder = 'POPULARITY_REVERSE' THEN item.popularity_rank END DESC,
          CASE WHEN :sortOrder = 'POPULARITY' AND item.popularity_rank IS NULL THEN item.popularity END DESC,
          CASE WHEN :sortOrder = 'POPULARITY_REVERSE' AND item.popularity_rank IS NULL THEN item.popularity END ASC,
          item.title COLLATE NOCASE ASC, item.id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchItems(
        mediaType: String,
        packId: String,
        query: String,
        sortOrder: String,
        genre: String?,
        format: String?,
        year: Int?,
        creditRole: String?,
        creditName: String?,
        demographic: String?,
        excludedGroupType: String?,
        limit: Int,
        offset: Int
    ): List<MediaItemRatingRow>

    @Query(
        """
        SELECT item.*,
               rating.additive_score AS rating_additive_score,
               rating.quality_score AS rating_quality_score,
               rating.final_score AS rating_final_score,
               rating.profile_id AS rating_profile_id,
               rating.pack_id AS rating_pack_id,
               (SELECT genreRow.genre FROM media_genre genreRow
                WHERE genreRow.media_id=item.id ORDER BY genreRow.genre COLLATE NOCASE LIMIT 1) AS filter_genre,
               (SELECT credit.name FROM media_credit credit
                WHERE credit.media_id=item.id AND credit.role=:creditRole
                ORDER BY credit.name COLLATE NOCASE LIMIT 1) AS filter_credit
        FROM media_item item
        LEFT JOIN media_rating rating ON rating.media_id=item.id AND rating.pack_id=:packId
        WHERE item.id IN (:mediaIds)
        """
    )
    suspend fun itemsByIds(
        mediaIds: List<String>,
        creditRole: String?,
        packId: String
    ): List<MediaItemRatingRow>

    @Query(
        """
        SELECT item.*,
               rating.additive_score AS rating_additive_score,
               rating.quality_score AS rating_quality_score,
               rating.final_score AS rating_final_score,
               rating.profile_id AS rating_profile_id,
               rating.pack_id AS rating_pack_id,
               (SELECT genreRow.genre FROM media_genre genreRow
                WHERE genreRow.media_id = item.id ORDER BY genreRow.genre COLLATE NOCASE LIMIT 1) AS filter_genre,
               (SELECT credit.name FROM media_credit credit
                WHERE credit.media_id = item.id AND credit.role = :creditRole
                ORDER BY credit.name COLLATE NOCASE LIMIT 1) AS filter_credit
        FROM media_item item
        INNER JOIN media_rating rating ON rating.media_id = item.id AND rating.pack_id = :packId
        WHERE item.media_type = :mediaType
          AND (:genre IS NULL OR EXISTS (SELECT 1 FROM media_genre genreRow
                                        WHERE genreRow.media_id = item.id AND genreRow.genre = :genre))
          AND (:year IS NULL OR item.year = :year)
          AND (:format IS NULL OR item.format = :format)
          AND (:demographic IS NULL OR item.demographic = :demographic)
          AND (:creditName IS NULL OR EXISTS (SELECT 1 FROM media_credit credit
                                             WHERE credit.media_id = item.id
                                               AND credit.role = :creditRole
                                               AND credit.name = :creditName))
          AND (:excludedGroupType IS NULL OR item.id NOT IN (
              SELECT excludedMember.media_id FROM media_group_member excludedMember
              INNER JOIN media_group excludedGroup ON excludedGroup.id = excludedMember.group_id
              WHERE excludedGroup.media_type = :mediaType
                AND excludedGroup.group_type = :excludedGroupType
          ))
        ORDER BY item.title COLLATE NOCASE ASC, item.id ASC
        """
    )
    suspend fun ratedItems(
        mediaType: String,
        packId: String,
        genre: String?,
        format: String?,
        year: Int?,
        creditRole: String?,
        creditName: String?,
        demographic: String?,
        excludedGroupType: String?
    ): List<MediaItemRatingRow>

    @Query(
        """
        SELECT score.media_id, score.score, score.applicable
        FROM media_rating_score score
        INNER JOIN media_item item ON item.id = score.media_id
        WHERE item.media_type = :mediaType AND score.pack_id = :packId
          AND score.category_id = :categoryId
        """
    )
    suspend fun categoryScores(
        mediaType: String,
        packId: String,
        categoryId: String
    ): List<MediaCategoryScoreRow>

    @Query(
        "SELECT DISTINCT genre FROM media_genre genreRow INNER JOIN media_item item " +
            "ON item.id = genreRow.media_id WHERE item.media_type = :mediaType " +
            "ORDER BY genre COLLATE NOCASE ASC"
    )
    suspend fun genres(mediaType: String): List<String>

    @Query(
        "SELECT DISTINCT year FROM media_item WHERE media_type = :mediaType AND year IS NOT NULL " +
            "ORDER BY year DESC"
    )
    suspend fun years(mediaType: String): List<Int>

    @Query(
        "SELECT DISTINCT format FROM media_item WHERE media_type = :mediaType AND format IS NOT NULL " +
            "ORDER BY format COLLATE NOCASE ASC"
    )
    suspend fun formats(mediaType: String): List<String>

    @Query(
        "SELECT DISTINCT demographic FROM media_item " +
            "WHERE media_type = :mediaType AND demographic IS NOT NULL " +
            "ORDER BY demographic COLLATE NOCASE ASC"
    )
    suspend fun demographics(mediaType: String): List<String>

    @Query(
        "SELECT DISTINCT credit.name FROM media_credit credit INNER JOIN media_item item " +
            "ON item.id = credit.media_id WHERE item.media_type = :mediaType AND credit.role = :role " +
            "ORDER BY credit.name COLLATE NOCASE ASC"
    )
    suspend fun creditNames(mediaType: String, role: String): List<String>

    @Query("SELECT * FROM media_item WHERE id = :mediaId")
    suspend fun item(mediaId: String): MediaItemEntity?

    @Query(
        "SELECT COUNT(*) FROM media_item WHERE media_type = :mediaType " +
            "AND local_cover_path IS NOT NULL AND cover_resolution_state = 'AVAILABLE'"
    )
    fun observeDownloadedCoverCount(mediaType: String): Flow<Int>

    @Query(
        "SELECT local_cover_path FROM media_item WHERE media_type = :mediaType " +
            "AND local_cover_path IS NOT NULL AND cover_resolution_state = 'AVAILABLE'"
    )
    fun observeLocalCoverPaths(mediaType: String): Flow<List<String>>

    @Query("SELECT local_cover_path FROM media_item WHERE id = :mediaId")
    fun observeLocalCoverPath(mediaId: String): Flow<String?>

    @Query(
        "SELECT item.* FROM media_item item INNER JOIN media_rating rating ON rating.media_id = item.id " +
            "WHERE item.media_type = :mediaType AND rating.pack_id = :packId " +
            "AND rating.final_score IS NOT NULL ORDER BY item.id"
    )
    suspend fun ratedCoverItems(mediaType: String, packId: String): List<MediaItemEntity>

    @Query(
        "SELECT * FROM media_item WHERE media_type = :mediaType " +
            "ORDER BY CASE WHEN popularity_rank IS NOT NULL THEN 0 " +
            "WHEN popularity IS NOT NULL THEN 1 ELSE 2 END ASC, " +
            "popularity_rank ASC, popularity DESC, title COLLATE NOCASE ASC, id ASC LIMIT :limit"
    )
    suspend fun topPopularCoverItems(mediaType: String, limit: Int): List<MediaItemEntity>

    @Query("SELECT * FROM media_item WHERE media_type = :mediaType AND local_cover_path IS NOT NULL")
    suspend fun itemsWithLocalCovers(mediaType: String): List<MediaItemEntity>

    @Query(
        "UPDATE media_item SET local_cover_path = NULL, cover_resolution_state = 'UNKNOWN' " +
            "WHERE media_type = :mediaType AND local_cover_path IS NOT NULL"
    )
    suspend fun clearLocalCovers(mediaType: String): Int

    @Query("SELECT media_type FROM media_item WHERE id = :mediaId")
    suspend fun mediaType(mediaId: String): String?

    @Upsert
    suspend fun upsertItem(item: MediaItemEntity)

    @Upsert
    suspend fun upsertItems(items: List<MediaItemEntity>)

    @Query(
        "UPDATE media_item SET local_cover_path = :path, cover_resolution_state = :state " +
            "WHERE id = :mediaId"
    )
    suspend fun updateMediaCover(mediaId: String, path: String?, state: String): Int

    @Query("SELECT * FROM media_rating WHERE pack_id = :packId AND media_id = :mediaId")
    suspend fun rating(packId: String, mediaId: String): MediaRatingEntity?

    @Query("SELECT * FROM media_rating_score WHERE pack_id = :packId AND media_id = :mediaId")
    suspend fun ratingScores(packId: String, mediaId: String): List<MediaRatingScoreEntity>

    @Upsert
    suspend fun upsertRating(rating: MediaRatingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRatingScores(scores: List<MediaRatingScoreEntity>)

    @Query("DELETE FROM media_rating_score WHERE pack_id = :packId AND media_id = :mediaId")
    suspend fun deleteRatingScores(packId: String, mediaId: String)

    @Transaction
    suspend fun replaceRatingAndScores(
        rating: MediaRatingEntity,
        scores: List<MediaRatingScoreEntity>
    ) {
        upsertRating(rating)
        deleteRatingScores(rating.packId, rating.mediaId)
        if (scores.isNotEmpty()) upsertRatingScores(scores)
    }

    @Query("DELETE FROM media_rating WHERE pack_id = :packId AND media_id = :mediaId")
    suspend fun deleteRating(packId: String, mediaId: String)

    @Query(
        """
        DELETE FROM media_rating_score
        WHERE pack_id = :packId AND category_id = :categoryId
        """
    )
    suspend fun deleteRatingScoresForCategory(packId: String, categoryId: String)

    @Query(
        """
        DELETE FROM media_rating_score
        WHERE pack_id = :packId AND category_id NOT IN (:categoryIds)
        """
    )
    suspend fun deleteRatingScoresOutsideCategories(packId: String, categoryIds: List<String>)

    @Query(
        """
        DELETE FROM media_rating_score
        WHERE pack_id = :packId
        """
    )
    suspend fun deleteAllRatingScores(packId: String)
}
