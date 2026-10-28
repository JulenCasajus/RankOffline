package org.rankoffline.app

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface PersonalDataDao {
    @Query("SELECT * FROM media_rating")
    suspend fun allRatings(): List<MediaRatingEntity>

    @Query("SELECT * FROM media_rating_score")
    suspend fun allRatingScores(): List<MediaRatingScoreEntity>

    @Query("SELECT * FROM media_group_rating")
    suspend fun allGroupRatings(): List<MediaGroupRatingEntity>

    @Query("SELECT * FROM media_group_rating_score")
    suspend fun allGroupRatingScores(): List<MediaGroupRatingScoreEntity>

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

    @Query("DELETE FROM media_rating WHERE pack_id IN (:packIds)")
    suspend fun deleteRatingsForPacks(packIds: List<String>)

    @Query("DELETE FROM media_group_rating WHERE pack_id IN (:packIds)")
    suspend fun deleteGroupRatingsForPacks(packIds: List<String>)

    @Query("DELETE FROM media_rating_score WHERE pack_id=:packId AND media_id IN (:mediaIds)")
    suspend fun deleteRatingScores(packId: String, mediaIds: List<String>)

    @Query("DELETE FROM media_group_rating_score WHERE pack_id=:packId AND group_id IN (:groupIds)")
    suspend fun deleteGroupRatingScores(packId: String, groupIds: List<String>)

    @Transaction
    suspend fun restore(data: PersonalRestoreData, replace: Boolean) {
        val ratings = if (replace) data.ratings else {
            val local = allRatings().associateBy { it.packId to it.mediaId }
            data.ratings.filter { incoming ->
                val existing = local[incoming.packId to incoming.mediaId]
                existing == null || incoming.updatedAt > existing.updatedAt
            }
        }
        val groups = if (replace) data.groupRatings else {
            val local = allGroupRatings().associateBy { it.packId to it.groupId }
            data.groupRatings.filter { incoming ->
                val existing = local[incoming.packId to incoming.groupId]
                existing == null || incoming.updatedAt > existing.updatedAt
            }
        }
        if (replace) {
            // Replace is deliberately limited to the backup's declared ownership. This prevents
            // importing one package from erasing ratings belonging to every other package.
            data.scopePackIds.chunked(SQLITE_BIND_CHUNK).forEach { packIds ->
                if (packIds.isNotEmpty()) {
                    deleteRatingsForPacks(packIds)
                    deleteGroupRatingsForPacks(packIds)
                }
            }
        } else {
            ratings.groupBy(MediaRatingEntity::packId).forEach { (packId, values) ->
                values.map(MediaRatingEntity::mediaId).chunked(SQLITE_BIND_CHUNK).forEach {
                    deleteRatingScores(packId, it)
                }
            }
            groups.groupBy(MediaGroupRatingEntity::packId).forEach { (packId, values) ->
                values.map(MediaGroupRatingEntity::groupId).chunked(SQLITE_BIND_CHUNK).forEach {
                    deleteGroupRatingScores(packId, it)
                }
            }
        }
        if (ratings.isNotEmpty()) upsertRatings(ratings)
        val ratingKeys = ratings.mapTo(hashSetOf()) { it.packId to it.mediaId }
        val scores = data.ratingScores.filter { it.packId to it.mediaId in ratingKeys }
        if (scores.isNotEmpty()) upsertRatingScores(scores)
        if (groups.isNotEmpty()) upsertGroupRatings(groups)
        val groupKeys = groups.mapTo(hashSetOf()) { it.packId to it.groupId }
        val groupScores = data.groupRatingScores.filter { it.packId to it.groupId in groupKeys }
        if (groupScores.isNotEmpty()) upsertGroupRatingScores(groupScores)
    }
}

private const val SQLITE_BIND_CHUNK = 900

data class PersonalRestoreData(
    val ratings: List<MediaRatingEntity>,
    val ratingScores: List<MediaRatingScoreEntity>,
    val groupRatings: List<MediaGroupRatingEntity>,
    val groupRatingScores: List<MediaGroupRatingScoreEntity>,
    val scopePackIds: Set<String> = (
        ratings.map(MediaRatingEntity::packId) +
            groupRatings.map(MediaGroupRatingEntity::packId)
        ).toSet()
)
