package org.rankoffline.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import java.io.File

data class GenericMediaRatingSave(
    val rating: MediaRatingEntity,
    val scores: List<MediaRatingScoreEntity>
)

internal fun buildGenericElaborateRating(
    packId: String,
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
            profileId = profileId,
            packId = packId
        ),
        scores = draft.categoryScores.map { (categoryId, score) ->
            MediaRatingScoreEntity(
                mediaId = mediaId,
                categoryId = categoryId,
                score = score?.takeIf(Double::isFinite),
                applicable = score?.isFinite() == true,
                packId = packId
            )
        }
    )
}

internal fun buildGenericSimpleRating(
    packId: String,
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
    profileId = draft.profileId,
    packId = packId
)

class GenericMediaRepository(
    private val database: AnimeDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    private val packManifest: (MediaType) -> PackManifest? = { null }
) {
    private val dao = database.mediaDao()
    private val groupDao = database.mediaGroupDao()
    private val catalogDao = database.mediaCatalogDao()

    suspend fun itemCount(mediaType: MediaType): Int = withContext(Dispatchers.IO) {
        dao.itemCount(mediaType.persistedValue)
    }

    suspend fun reconcileLegacyAnimeCoverMetadata(): Int = withContext(Dispatchers.IO) {
        catalogDao.reconcileLegacyAnimeCoverMetadata()
    }

    suspend fun search(
        mediaType: MediaType,
        query: String,
        sortOrder: CatalogSortOrder,
        filters: GenericMediaFilters,
        mergeEnabled: Boolean,
        limit: Int = 200,
        offset: Int = 0
    ): List<MediaListEntry> = withContext(Dispatchers.IO) {
        requireIntegratedMediaType(mediaType)
        val role = packManifest(mediaType)?.creditRole() ?: mediaType.genericFilterCreditRole()
        val packId = mediaType.toPackId().value
        val configuredGroupType = packManifest(mediaType)?.groupTypeOrNull()
            ?: mediaType.genericGroupType()
        val groupType = configuredGroupType.takeIf { mergeEnabled }
        val entries = if (groupType == null) {
            dao.searchItems(
                mediaType.persistedValue,
                packId,
                query.trim(),
                sortOrder.name,
                filters.genre,
                filters.format,
                filters.year,
                role,
                filters.creditName,
                filters.demographic,
                null,
                limit,
                offset
            ).map { MediaListEntry.Item(it.toDomain()) }
        } else {
            val visibleIds = groupDao.visibleCatalogEntryIds(
                GenericGroupedQueries.catalogIds(
                    mediaType = mediaType.persistedValue,
                    groupType = groupType,
                    query = query,
                    sortOrder = sortOrder,
                    filters = filters,
                    creditRole = role,
                    limit = limit,
                    offset = offset
                )
            )
            val itemIds = visibleIds.filter { it.entryType == 0 }.map(GenericVisibleEntryId::id)
            val groupIds = visibleIds.filter { it.entryType == 1 }.map(GenericVisibleEntryId::id)
            val itemsById = if (itemIds.isEmpty()) emptyMap() else
                dao.itemsByIds(itemIds, role, packId).associateBy { it.item.id }
            val groupsById = if (groupIds.isEmpty()) emptyMap() else
                groupDao.groupRowsByIds(groupIds, packId).associateBy { it.group.id }
            visibleIds.mapNotNull { visible ->
                if (visible.entryType == 1) groupsById[visible.id]?.toListEntry()
                else itemsById[visible.id]?.let { MediaListEntry.Item(it.toDomain()) }
            }
        }
        attachSharedCoverIdentities(entries, mediaType, configuredGroupType)
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
        val role = packManifest(mediaType)?.creditRole() ?: mediaType.genericFilterCreditRole()
        val packId = mediaType.toPackId().value
        val configuredGroupType = packManifest(mediaType)?.groupTypeOrNull()
            ?: mediaType.genericGroupType()
        val groupType = configuredGroupType.takeIf { mergeEnabled }
        val categoryScores = if (sortOrder == RankingSortOrder.CATEGORY_SCORE && categoryId != null) {
            dao.categoryScores(mediaType.persistedValue, packId, categoryId)
                .filter { it.applicable && it.score?.isFinite() == true }
                .associate { it.mediaId to it.score }
        } else emptyMap()
        val individuals = dao.ratedItems(
            mediaType.persistedValue,
            packId,
            filters.genre,
            filters.format,
            filters.year,
            role,
            filters.creditName,
            filters.demographic,
            groupType
        ).asSequence()
            .map { row -> MediaListEntry.Item(row.toDomain(categoryScores[row.item.id])) }
            .filter { it.rankingScore(sortOrder) != null }
            .toMutableList<MediaListEntry>()

        if (groupType != null) {
            val matchingGroupRows = groupDao.ratedGroupRows(
                mediaType.persistedValue,
                packId,
                groupType,
                filters.genre,
                filters.format,
                filters.year,
                role,
                filters.creditName,
                filters.demographic,
                categoryId.takeIf { sortOrder == RankingSortOrder.CATEGORY_SCORE }
            )
            matchingGroupRows.asSequence()
                .filter { it.finalScore != null }
                .map(MediaMergedCatalogRow::toListEntry)
                .filterTo(individuals) { it.rankingScore(sortOrder) != null }
        }
        val sorted = individuals.sortedWith { left, right ->
            val leftScore = requireNotNull(left.rankingScore(sortOrder))
            val rightScore = requireNotNull(right.rankingScore(sortOrder))
            val scoreComparison = if (descending) rightScore.compareTo(leftScore)
            else leftScore.compareTo(rightScore)
            if (scoreComparison != 0) scoreComparison
            else compareValuesBy(left, right, { it.title.lowercase() }, { it.id })
        }
        attachSharedCoverIdentities(sorted, mediaType, configuredGroupType)
    }

    suspend fun filterOptions(mediaType: MediaType): GenericMediaFilterOptions =
        withContext(Dispatchers.IO) {
            requireIntegratedMediaType(mediaType)
            val role = packManifest(mediaType)?.creditRole() ?: mediaType.genericFilterCreditRole()
            GenericMediaFilterOptions(
                genres = dao.genres(mediaType.persistedValue),
                formats = dao.formats(mediaType.persistedValue),
                years = dao.years(mediaType.persistedValue),
                creditNames = role?.let { dao.creditNames(mediaType.persistedValue, it) }.orEmpty(),
                demographics = emptyList()
            )
        }

    suspend fun item(mediaId: String): MediaItem? = withContext(Dispatchers.IO) {
        val entity = dao.item(mediaId) ?: return@withContext null
        val mediaType = requireNotNull(MediaType.entries.firstOrNull {
            it.persistedValue == entity.mediaType
        })
        entity.toDomain(dao.rating(mediaType.toPackId().value, mediaId))
    }

    suspend fun upsertItem(item: MediaItemEntity) = withContext(Dispatchers.IO) {
        requireGenericMediaType(item.mediaType)
        dao.upsertItem(item)
    }

    suspend fun upsertItems(items: List<MediaItemEntity>) = withContext(Dispatchers.IO) {
        items.forEach { requireGenericMediaType(it.mediaType) }
        dao.upsertItems(items)
    }

    suspend fun deleteCatalogReferenceData(mediaType: MediaType) = withContext(Dispatchers.IO) {
        requireIntegratedMediaType(mediaType)
        catalogDao.deleteCatalogReferenceData(mediaType.persistedValue)
    }

    suspend fun deleteAllPersonalData(mediaType: MediaType) = withContext(Dispatchers.IO) {
        requireIntegratedMediaType(mediaType)
        catalogDao.deletePersonalData(mediaType.toPackId().value)
    }

    suspend fun purgePackData(mediaType: MediaType) = withContext(Dispatchers.IO) {
        requireIntegratedMediaType(mediaType)
        catalogDao.purgePackData(
            mediaType.persistedValue,
            mediaType.toPackId().value
        )
    }

    suspend fun installPackCovers(manifest: PackManifest, directory: File) = withContext(Dispatchers.IO) {
        manifest.readCoverIndex(directory).forEach { cover ->
            val path = File(directory, cover.relativePath).absolutePath
            when {
                dao.item(cover.canonicalId) != null ->
                    dao.updateMediaCover(cover.canonicalId, path, CoverResolutionState.AVAILABLE.name)
                groupDao.group(cover.canonicalId) != null ->
                    groupDao.updateGroupCover(cover.canonicalId, path, CoverResolutionState.AVAILABLE.name)
            }
        }
    }

    suspend fun upsertDiscoveredItem(record: MediaCatalogRecord) = withContext(Dispatchers.IO) {
        requireGenericMediaType(record.mediaType)
        database.withTransaction {
            catalogDao.insertNewItems(listOf(record.toNewEntity()))
            if (catalogDao.updateCatalogFields(listOf(record.toCatalogFields())) != 1) {
                throw IllegalArgumentException("Remote item identity conflicts with local catalog data")
            }
            val ids = listOf(record.id)
            catalogDao.deleteGenres(ids)
            catalogDao.deleteCredits(ids)
            catalogDao.deleteAliases(ids)
            val genres = record.genres.distinct().map { MediaGenreEntity(record.id, it) }
            val credits = record.credits.distinct().map { MediaCreditEntity(record.id, it.role, it.name) }
            val aliases = record.aliases.distinctBy { it.title }
                .map { MediaAliasEntity(record.id, it.title, it.language, it.kind) }
            if (genres.isNotEmpty()) catalogDao.insertGenres(genres)
            if (credits.isNotEmpty()) catalogDao.insertCredits(credits)
            if (aliases.isNotEmpty()) catalogDao.insertAliases(aliases)
        }
    }

    suspend fun rating(mediaType: MediaType, mediaId: String): MediaRatingEntity? = withContext(Dispatchers.IO) {
        dao.rating(mediaType.toPackId().value, mediaId)
    }

    suspend fun ratingScores(mediaType: MediaType, mediaId: String): List<MediaRatingScoreEntity> = withContext(Dispatchers.IO) {
        dao.ratingScores(mediaType.toPackId().value, mediaId)
    }

    suspend fun loadRating(mediaType: MediaType, mediaId: String): RatingDraft = withContext(Dispatchers.IO) {
        val packId = mediaType.toPackId().value
        val rating = dao.rating(packId, mediaId) ?: return@withContext RatingDraft()
        RatingDraft(
            categoryScores = dao.ratingScores(packId, mediaId).associate { score ->
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
        val mediaType = requireGenericItem(mediaId)
        dao.upsertRating(
            buildGenericSimpleRating(
                mediaType.toPackId().value,
                mediaId,
                draft,
                qualityScore,
                finalScore,
                now()
            )
        )
    }

    suspend fun saveElaborateRating(
        mediaId: String,
        draft: RatingDraft,
        config: RatingSystemConfig,
        finalScoreOverride: Double? = null,
        profileId: String? = null
    ) = withContext(Dispatchers.IO) {
        val mediaType = requireGenericItem(mediaId)
        val save = buildGenericElaborateRating(
            mediaType.toPackId().value,
            mediaId,
            draft,
            config,
            finalScoreOverride,
            profileId,
            now()
        )
        dao.replaceRatingAndScores(save.rating, save.scores)
    }

    suspend fun deleteRating(mediaType: MediaType, mediaId: String) = withContext(Dispatchers.IO) {
        dao.deleteRating(mediaType.toPackId().value, mediaId)
    }

    /** Compatibility overloads for detached legacy identities and older tests. New UI paths pass pack ownership. */
    suspend fun rating(mediaId: String): MediaRatingEntity? =
        rating(mediaTypeForItemIdentity(mediaId), mediaId)

    suspend fun ratingScores(mediaId: String): List<MediaRatingScoreEntity> =
        ratingScores(mediaTypeForItemIdentity(mediaId), mediaId)

    suspend fun loadRating(mediaId: String): RatingDraft =
        loadRating(mediaTypeForItemIdentity(mediaId), mediaId)

    suspend fun deleteRating(mediaId: String) =
        deleteRating(mediaTypeForItemIdentity(mediaId), mediaId)

    suspend fun deleteRatingCategory(mediaType: MediaType, categoryId: String) =
        withContext(Dispatchers.IO) {
            requireGenericMediaType(mediaType.persistedValue)
            dao.deleteRatingScoresForCategory(mediaType.toPackId().value, categoryId)
        }

    suspend fun deleteRatingScoresOutsideCategories(mediaType: MediaType, categoryIds: List<String>) =
        withContext(Dispatchers.IO) {
            requireGenericMediaType(mediaType.persistedValue)
            if (categoryIds.isEmpty()) {
                dao.deleteAllRatingScores(mediaType.toPackId().value)
            } else {
                dao.deleteRatingScoresOutsideCategories(mediaType.toPackId().value, categoryIds)
            }
        }

    private suspend fun requireGenericItem(mediaId: String): MediaType {
        val persistedType = requireNotNull(dao.mediaType(mediaId)) { "Unknown media item: $mediaId" }
        requireGenericMediaType(persistedType)
        return requireNotNull(MediaType.entries.firstOrNull { it.persistedValue == persistedType })
    }

    private suspend fun mediaTypeForItemIdentity(mediaId: String): MediaType {
        dao.mediaType(mediaId)?.let { persisted ->
            return requireNotNull(MediaType.entries.firstOrNull { it.persistedValue == persisted })
        }
        return when {
            mediaId.startsWith("manga:") -> MediaType.MANGA
            mediaId.startsWith("movie:") -> MediaType.MOVIE
            mediaId.startsWith("series:") -> MediaType.SERIES
            mediaId.startsWith("book:") -> MediaType.BOOK
            mediaId.startsWith("mal:") || mediaId.startsWith("anilist:") -> MediaType.ANIME
            else -> error("Unknown detached media identity: $mediaId")
        }
    }

    private suspend fun attachSharedCoverIdentities(
        entries: List<MediaListEntry>,
        mediaType: MediaType,
        groupType: String?
    ): List<MediaListEntry> {
        if (entries.isEmpty() || groupType == null) return entries
        val itemIds = entries.asSequence()
            .filterIsInstance<MediaListEntry.Item>()
            .map { it.id }
            .toList()
        val itemCoverIds = buildMap {
            itemIds.chunked(SQLITE_ID_BATCH).forEach { batch ->
                groupDao.sharedCoverIdentitiesForItems(
                    mediaType.persistedValue,
                    groupType,
                    batch
                ).forEach { put(it.targetId, it.coverMediaId) }
            }
        }
        val groupIds = entries.asSequence()
            .filterIsInstance<MediaListEntry.Group>()
            .map { it.id }
            .toList()
        val groupCoverIds = buildMap {
            groupIds.chunked(SQLITE_ID_BATCH).forEach { batch ->
                groupDao.sharedCoverIdentitiesForGroups(batch)
                    .forEach { put(it.targetId, it.coverMediaId) }
            }
        }
        return entries.map { entry ->
            when (entry) {
                is MediaListEntry.Item -> entry.copy(
                    value = entry.value.copy(sharedCoverMediaId = itemCoverIds[entry.id])
                )
                is MediaListEntry.Group -> entry.copy(sharedCoverMediaId = groupCoverIds[entry.id])
            }
        }
    }

}

private const val SQLITE_ID_BATCH = 900

private fun requireIntegratedMediaType(mediaType: MediaType) {
    require(mediaType in MediaType.entries)
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
        fun MediaListEntry.popularityRank() = when (this) {
            is MediaListEntry.Item -> value.popularityRank
            is MediaListEntry.Group -> popularityRank
        }
        val leftRank = left.popularityRank()
        val rightRank = right.popularityRank()
        val rankComparison = when {
            leftRank == null && rightRank == null -> 0
            leftRank == null -> 1
            rightRank == null -> -1
            sortOrder == CatalogSortOrder.POPULARITY -> leftRank.compareTo(rightRank)
            else -> rightRank.compareTo(leftRank)
        }
        if (rankComparison != 0) return@sortedWith rankComparison
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
    require(mediaType != null) {
        "Media persistence does not accept media type: $persistedType"
    }
}
