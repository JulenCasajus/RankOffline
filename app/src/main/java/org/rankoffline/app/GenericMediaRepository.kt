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
    private val groupDao = database.mediaGroupDao()
    private val groupRepository = GenericMediaGroupRepository(database, now)

    suspend fun search(
        mediaType: MediaType,
        query: String,
        sortOrder: CatalogSortOrder,
        filters: GenericMediaFilters,
        mergeEnabled: Boolean
    ): List<MediaListEntry> = withContext(Dispatchers.IO) {
        requireIntegratedMediaType(mediaType)
        val role = mediaType.genericFilterCreditRole()
        val groupType = mediaType.genericGroupType().takeIf { mergeEnabled }
        val individuals = dao.searchItems(
            mediaType.persistedValue,
            query.trim(),
            sortOrder.name,
            filters.genre,
            filters.year,
            role,
            filters.creditName,
            filters.demographic
        )
        if (groupType == null) return@withContext individuals.map { MediaListEntry.Item(it.toDomain()) }

        val groupedIds = groupDao.groupedMediaIds(mediaType.persistedValue, groupType).toSet()
        val ungrouped = individuals.asSequence()
            .filterNot { it.item.id in groupedIds }
            .map { MediaListEntry.Item(it.toDomain()) }
            .toList()
        val groups = groupDao.matchingGroups(
            mediaType.persistedValue,
            groupType,
            query.trim(),
            filters.genre,
            filters.year,
            role,
            filters.creditName,
            filters.demographic
        ).mapNotNull { group -> groupRepository.groupDetail(group.id)?.toListEntry() }
        sortCatalogEntries(ungrouped + groups, sortOrder)
    }

    suspend fun ranking(
        mediaType: MediaType,
        filters: GenericMediaFilters,
        sortOrder: RankingSortOrder,
        categoryId: String?,
        descending: Boolean,
        mergeEnabled: Boolean
    ): List<MediaListEntry> = withContext(Dispatchers.IO) {
        requireIntegratedMediaType(mediaType)
        val role = mediaType.genericFilterCreditRole()
        val groupType = mediaType.genericGroupType().takeIf { mergeEnabled }
        val categoryScores = if (sortOrder == RankingSortOrder.CATEGORY_SCORE && categoryId != null) {
            dao.categoryScores(mediaType.persistedValue, categoryId)
                .filter { it.applicable && it.score?.isFinite() == true }
                .associate { it.mediaId to it.score }
        } else emptyMap()
        val groupedIds = if (groupType != null) {
            groupDao.groupedMediaIds(mediaType.persistedValue, groupType).toSet()
        } else emptySet()
        val individuals = dao.ratedItems(
            mediaType.persistedValue,
            filters.genre,
            filters.year,
            role,
            filters.creditName,
            filters.demographic
        ).asSequence()
            .filterNot { it.item.id in groupedIds }
            .map { row -> MediaListEntry.Item(row.toDomain(categoryScores[row.item.id])) }
            .filter { it.rankingScore(sortOrder) != null }
            .toMutableList<MediaListEntry>()

        if (groupType != null) {
            groupDao.matchingGroups(
                mediaType.persistedValue,
                groupType,
                "",
                filters.genre,
                filters.year,
                role,
                filters.creditName,
                filters.demographic
            ).mapNotNullTo(individuals) { group ->
                val detail = groupRepository.groupDetail(group.id) ?: return@mapNotNullTo null
                val explicitCategory = if (sortOrder == RankingSortOrder.CATEGORY_SCORE && categoryId != null) {
                    groupDao.groupRatingScores(group.id).firstOrNull {
                        it.categoryId == categoryId && it.applicable && it.score?.isFinite() == true
                    }?.score
                } else null
                detail.toListEntry(categoryId, explicitCategory)
                    .takeIf { it.rankingScore(sortOrder) != null }
            }
        }
        individuals.sortedWith { left, right ->
            val leftScore = requireNotNull(left.rankingScore(sortOrder))
            val rightScore = requireNotNull(right.rankingScore(sortOrder))
            val scoreComparison = if (descending) rightScore.compareTo(leftScore)
            else leftScore.compareTo(rightScore)
            if (scoreComparison != 0) scoreComparison
            else compareValuesBy(left, right, { it.title.lowercase() }, { it.id })
        }
    }

    suspend fun filterOptions(mediaType: MediaType): GenericMediaFilterOptions =
        withContext(Dispatchers.IO) {
            requireIntegratedMediaType(mediaType)
            val role = mediaType.genericFilterCreditRole()
            GenericMediaFilterOptions(
                genres = dao.genres(mediaType.persistedValue),
                years = dao.years(mediaType.persistedValue),
                creditNames = role?.let { dao.creditNames(mediaType.persistedValue, it) }.orEmpty(),
                demographics = dao.demographics(mediaType.persistedValue)
            )
        }

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

}

private fun requireIntegratedMediaType(mediaType: MediaType) {
    require(mediaType != MediaType.ANIME)
}

private fun MediaGroupDetail.toListEntry(
    categoryId: String? = null,
    explicitCategoryScore: Double? = null
): MediaListEntry.Group {
    val explicit = explicitRating
    val representative = members.firstOrNull { it.id == group.representativeMediaId }
        ?: members.firstOrNull()
    val displayGroup = if (group.mediaType == MediaType.MANGA && group.localCoverPath == null) {
        group.copy(
            localCoverPath = representative?.localCoverPath,
            coverResolutionState = representative?.coverResolutionState
                ?: CoverResolutionState.UNKNOWN
        )
    } else {
        group
    }
    return MediaListEntry.Group(
        value = displayGroup,
        finalScore = explicit?.finalScore ?: derivedRating.finalScore,
        qualityScore = explicit?.qualityScore ?: derivedRating.qualityScore,
        additiveScore = explicit?.additiveScore ?: derivedRating.additiveScore,
        categoryScore = explicitCategoryScore
            ?: categoryId?.let(derivedRating.categoryScores::get),
        popularity = members.mapNotNull { it.popularity?.takeIf(Double::isFinite) }.maxOrNull(),
        memberCount = members.size,
        ratedCount = derivedRating.ratedCount,
        representativeMediaId = group.representativeMediaId ?: representative?.id,
        derived = explicit == null
    )
}

private fun MediaListEntry.rankingScore(sortOrder: RankingSortOrder): Double? = when (sortOrder) {
    RankingSortOrder.FINAL_SCORE -> finalScore
    RankingSortOrder.QUALITY_SCORE -> qualityScore
    RankingSortOrder.PERSONAL_TASTE -> additiveScore?.times(10.0)
    RankingSortOrder.CATEGORY_SCORE -> categoryScore
}?.takeIf { it.isFinite() && it in RATING_MIN..RATING_MAX }

private fun sortCatalogEntries(
    entries: List<MediaListEntry>,
    sortOrder: CatalogSortOrder
): List<MediaListEntry> = when (sortOrder) {
    CatalogSortOrder.TITLE_ASC -> entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    CatalogSortOrder.TITLE_DESC -> entries.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title })
    CatalogSortOrder.POPULARITY,
    CatalogSortOrder.POPULARITY_REVERSE -> entries.sortedWith { left, right ->
        fun MediaListEntry.popularity() = when (this) {
            is MediaListEntry.Item -> value.popularity
            is MediaListEntry.Group -> popularity
        }
        val a = left.popularity()
        val b = right.popularity()
        val score = when {
            a == null && b == null -> 0
            a == null -> 1
            b == null -> -1
            sortOrder == CatalogSortOrder.POPULARITY -> b.compareTo(a)
            else -> a.compareTo(b)
        }
        if (score != 0) score else compareValuesBy(left, right, { it.title.lowercase() }, { it.id })
    }
}

internal fun requireGenericMediaType(persistedType: String) {
    val mediaType = MediaType.entries.firstOrNull { it.persistedValue == persistedType }
    require(mediaType != null && mediaType != MediaType.ANIME) {
        "Generic media persistence does not accept media type: $persistedType"
    }
}
