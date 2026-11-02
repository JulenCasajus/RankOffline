package org.rankoffline.app

import androidx.room.Dao
import androidx.room.Transaction

/** Personal information is detached from installed packs, including group ratings. */
@Dao
interface PersonalDataDao : PersonalBackupReadDao, PersonalBackupWriteDao {
    @Transaction
    suspend fun deletePersonalData(packId: Long) {
        deletePersonalItemRatings(packId)
        deletePersonalGroupRatings(packId)
    }

    /**
     * Replace: restore exactly the backup snapshot.
     * Merge: only newer incoming parent rows replace their scores; other data is untouched.
     * Room's parent->score FK cascade is confined to personal score tables.
     */
    @Transaction
    suspend fun restore(data: PersonalRestoreData, replace: Boolean, packId: Long? = null) {
        if (packId != null) {
            PackId(packId)
            require(data.ratings.all { it.packId == packId } &&
                data.groupRatings.all { it.packId == packId })
        }
        validateRestore(data)
        val ratings = if (replace) data.ratings else {
            val local = allRatings().associateBy { it.packId to it.mediaId }
            data.ratings.filter { incoming ->
                val current = local[incoming.packId to incoming.mediaId]
                current == null || incoming.updatedAt > current.updatedAt
            }
        }
        val groups = if (replace) data.groupRatings else {
            val local = allGroupRatings().associateBy { it.packId to it.groupId }
            data.groupRatings.filter { incoming ->
                val current = local[incoming.packId to incoming.groupId]
                current == null || incoming.updatedAt > current.updatedAt
            }
        }
        if (replace) {
            if (packId == null) {
                deleteAllRatings()
                deleteAllGroupRatings()
            } else {
                deletePersonalData(packId)
            }
        } else {
            ratings.groupBy(MediaRatingEntity::packId).forEach { (packId, values) ->
                values.map(MediaRatingEntity::mediaId).chunked(500).forEach { deleteRatingScores(packId, it) }
            }
            groups.groupBy(MediaGroupRatingEntity::packId).forEach { (packId, values) ->
                values.map(MediaGroupRatingEntity::groupId).chunked(500).forEach { deleteGroupRatingScores(packId, it) }
            }
        }
        ratings.chunked(500).forEach { upsertRatings(it) }
        val ratingKeys = ratings.mapTo(hashSetOf()) { it.packId to it.mediaId }
        data.ratingScores.filter { it.packId to it.mediaId in ratingKeys }
            .chunked(500).forEach { upsertRatingScores(it) }
        groups.chunked(500).forEach { upsertGroupRatings(it) }
        val groupKeys = groups.mapTo(hashSetOf()) { it.packId to it.groupId }
        data.groupRatingScores.filter { it.packId to it.groupId in groupKeys }
            .chunked(500).forEach { upsertGroupRatingScores(it) }
    }
}

data class PersonalRestoreData(
    val ratings: List<MediaRatingEntity>,
    val ratingScores: List<MediaRatingScoreEntity>,
    val groupRatings: List<MediaGroupRatingEntity>,
    val groupRatingScores: List<MediaGroupRatingScoreEntity>
)

/** Reject invalid backups *before* mutating any stored user data. */
internal fun validateRestore(data: PersonalRestoreData) {
    val items = data.ratings.map { it.packId to it.mediaId }
    val groups = data.groupRatings.map { it.packId to it.groupId }
    require(items.size == items.distinct().size && groups.size == groups.distinct().size)
    val itemKeys = items.toHashSet()
    val groupKeys = groups.toHashSet()
    require(data.ratingScores.all { it.packId to it.mediaId in itemKeys })
    require(data.groupRatingScores.all { it.packId to it.groupId in groupKeys })
    val itemScores = data.ratingScores.map { Triple(it.packId, it.mediaId, it.categoryId) }
    val groupScores = data.groupRatingScores.map { Triple(it.packId, it.groupId, it.categoryId) }
    require(itemScores.size == itemScores.distinct().size)
    require(groupScores.size == groupScores.distinct().size)
    val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,191}")
    require((items + groups).all { (pack, id) ->
        runCatching { PackId(pack) }.isSuccess && id.matches(idPattern)
    })
    require(data.ratings.all { it.additiveScore.isFinite() &&
        (it.qualityScore == null || it.qualityScore.isFinite()) &&
        (it.finalScore == null || it.finalScore.isFinite()) })
    require(data.groupRatings.all { it.additiveScore.isFinite() &&
        (it.qualityScore == null || it.qualityScore.isFinite()) &&
        (it.finalScore == null || it.finalScore.isFinite()) })
    require(data.ratingScores.all { it.score == null || it.score.isFinite() })
    require(data.groupRatingScores.all { it.score == null || it.score.isFinite() })
}
