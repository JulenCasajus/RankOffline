package org.rankoffline.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GenericMediaRatingSave(
    val rating: MediaRatingEntity,
    val scores: List<MediaRatingScoreEntity>
)

internal fun buildGenericElaborateRating(
    mediaId: String,
    draft: RatingDraft,
    config: RatingSystemConfig,
    finalScoreOverride: Double? = null,
    profileId: String? = null,
    updatedAt: Long
): GenericMediaRatingSave {
    require(draft.categoryScores.values.all { it == null || isDiscreteRatingValue(it) })
    val normalizedBonus = requireNotNull(snapBonusValue(draft.additiveScore))
    val result = ScoreCalculator.calculate(draft.categoryScores, normalizedBonus, config)
    val finalScore = finalScoreOverride?.let { requireNotNull(normalizeDirectRatingValue(it)) }
        ?: result.finalScore
    return GenericMediaRatingSave(
        rating = MediaRatingEntity(
            mediaId = mediaId,
            additiveScore = normalizedBonus,
            qualityScore = result.qualityScore,
            finalScore = finalScore,
            notes = draft.notes,
            status = draft.status,
            updatedAt = updatedAt,
            profileId = profileId
        ),
        scores = draft.categoryScores.map { (categoryId, score) ->
            MediaRatingScoreEntity(
                mediaId = mediaId,
                categoryId = categoryId,
                score = score?.takeIf(Double::isFinite),
                applicable = score?.isFinite() == true
            )
        }
    )
}

internal fun buildGenericSimpleRating(
    mediaId: String,
    draft: RatingDraft,
    qualityScore: Double,
    finalScore: Double,
    updatedAt: Long
): MediaRatingEntity = MediaRatingEntity(
    mediaId = mediaId,
    additiveScore = requireNotNull(snapBonusValue(draft.additiveScore)),
    qualityScore = requireNotNull(normalizeDirectRatingValue(qualityScore)),
    finalScore = requireNotNull(normalizeDirectRatingValue(finalScore)),
    notes = draft.notes,
    status = draft.status,
    updatedAt = updatedAt,
    profileId = draft.profileId
)

class GenericMediaRepository(
    database: AnimeDatabase,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val dao = database.mediaDao()

    suspend fun item(mediaId: String): MediaItem? = withContext(Dispatchers.IO) {
        val entity = dao.item(mediaId) ?: return@withContext null
        entity.toDomain(dao.rating(mediaId))
    }

    suspend fun upsertItem(item: MediaItemEntity) = withContext(Dispatchers.IO) {
        requireGenericMediaType(item.mediaType)
        dao.upsertItem(item)
    }

    suspend fun upsertItems(items: List<MediaItemEntity>) = withContext(Dispatchers.IO) {
        items.forEach { requireGenericMediaType(it.mediaType) }
        dao.upsertItems(items)
    }

    suspend fun rating(mediaId: String): MediaRatingEntity? = withContext(Dispatchers.IO) {
        dao.rating(mediaId)
    }

    suspend fun ratingScores(mediaId: String): List<MediaRatingScoreEntity> =
        withContext(Dispatchers.IO) { dao.ratingScores(mediaId) }

    suspend fun loadRating(mediaId: String): RatingDraft = withContext(Dispatchers.IO) {
        val rating = dao.rating(mediaId) ?: return@withContext RatingDraft()
        RatingDraft(
            categoryScores = dao.ratingScores(mediaId).associate { score ->
                score.categoryId to score.score.takeIf { score.applicable }
            },
            additiveScore = rating.additiveScore,
            notes = rating.notes,
            status = rating.status,
            finalScore = rating.finalScore,
            qualityScore = rating.qualityScore,
            profileId = rating.profileId
        )
    }

    suspend fun saveSimpleRating(
        mediaId: String,
        draft: RatingDraft,
        qualityScore: Double,
        finalScore: Double
    ) = withContext(Dispatchers.IO) {
        requireGenericItem(mediaId)
        dao.upsertRating(buildGenericSimpleRating(mediaId, draft, qualityScore, finalScore, now()))
    }

    suspend fun saveElaborateRating(
        mediaId: String,
        draft: RatingDraft,
        config: RatingSystemConfig,
        finalScoreOverride: Double? = null,
        profileId: String? = null
    ) = withContext(Dispatchers.IO) {
        requireGenericItem(mediaId)
        val save = buildGenericElaborateRating(
            mediaId,
            draft,
            config,
            finalScoreOverride,
            profileId,
            now()
        )
        dao.replaceRatingAndScores(save.rating, save.scores)
    }

    suspend fun deleteRating(mediaId: String) = withContext(Dispatchers.IO) {
        dao.deleteRating(mediaId)
    }

    suspend fun deleteRatingCategory(mediaType: MediaType, categoryId: String) =
        withContext(Dispatchers.IO) {
            requireGenericMediaType(mediaType.persistedValue)
            dao.deleteRatingScoresForCategory(mediaType.persistedValue, categoryId)
        }

    suspend fun deleteRatingScoresOutsideCategories(mediaType: MediaType, categoryIds: List<String>) =
        withContext(Dispatchers.IO) {
            requireGenericMediaType(mediaType.persistedValue)
            if (categoryIds.isEmpty()) {
                dao.deleteAllRatingScores(mediaType.persistedValue)
            } else {
                dao.deleteRatingScoresOutsideCategories(mediaType.persistedValue, categoryIds)
            }
        }

    private suspend fun requireGenericItem(mediaId: String) {
        val persistedType = requireNotNull(dao.mediaType(mediaId)) { "Unknown media item: $mediaId" }
        requireGenericMediaType(persistedType)
    }

    private fun requireGenericMediaType(persistedType: String) {
        val mediaType = MediaType.entries.firstOrNull { it.persistedValue == persistedType }
        require(mediaType != null && mediaType != MediaType.ANIME) {
            "Generic media persistence does not accept media type: $persistedType"
        }
    }
}
