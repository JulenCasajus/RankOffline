package org.rankoffline.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class GenericMediaRatingSave(
    val rating: MediaRatingEntity,
    val scores: List<MediaRatingScoreEntity>
)

internal data class AllCatalogCursor(
    val offsets: Map<MediaType, Int> = emptyMap(),
    val exhausted: Set<MediaType> = emptySet(),
    val nextPopularityPack: Int = 0
)

internal data class AllCatalogPage(
    val entries: List<MediaListEntry>,
    val nextCursor: AllCatalogCursor,
    val hasMore: Boolean
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
    private val sharedCoverCaches = ConcurrentHashMap<MediaType, SharedCoverCache>()

    /** Loaded after group import, before READY is published, while the import writer is idle. */
    suspend fun refreshSharedCoverIdentities(mediaType: MediaType, groupType: String?) =
        withContext(Dispatchers.IO) {
            if (groupType == null) {
                sharedCoverCaches.remove(mediaType)
                return@withContext
            }
            val itemIdentities = groupDao.allSharedCoverIdentitiesForItems(
                mediaType.persistedValue,
                groupType
            )
            val groupIdentities = groupDao.allSharedCoverIdentitiesForGroups(
                mediaType.persistedValue,
                groupType
            )
            sharedCoverCaches[mediaType] = SharedCoverCache(
                groupType = groupType,
                items = itemIdentities.associate { it.targetId to it.coverMediaId },
                groups = groupIdentities.associate { it.targetId to it.coverMediaId }
            )
        }

    fun clearSharedCoverIdentities(mediaType: MediaType) {
        sharedCoverCaches.remove(mediaType)
    }

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
    ): List<MediaListEntry> = timedRead(mediaType, "search") {
        val role = packManifest(mediaType)?.creditRole() ?: mediaType.genericFilterCreditRole()
        val packId = mediaType.toPackId().value
        val configuredGroupType = packManifest(mediaType)?.groupTypeOrNull()
            ?: mediaType.genericGroupType()
        val groupType = configuredGroupType.takeIf { mergeEnabled }
        val catalogQueryStarted = System.nanoTime()
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
        logReadPhase(mediaType, "search-catalog", catalogQueryStarted)
        val aliasStarted = System.nanoTime()
        val titledEntries = entries.withMatchingAliasTitles(query)
        logReadPhase(mediaType, "search-aliases", aliasStarted)
        val coversStarted = System.nanoTime()
        attachSharedCoverIdentities(
            titledEntries,
            mediaType,
            configuredGroupType
        ).also { logReadPhase(mediaType, "search-cover-identities", coversStarted) }
    }

    /**
     * Cursor-based virtual page. Source queries are bounded by [limit], so deep scrolling does
     * not reload every preceding row from every pack.
     */
    internal suspend fun searchAllPage(
        mediaTypes: List<MediaType>,
        query: String,
        sortOrder: CatalogSortOrder,
        filters: GenericMediaFilters,
        mergeByMedia: Map<MediaType, Boolean>,
        limit: Int,
        cursor: AllCatalogCursor = AllCatalogCursor()
    ): AllCatalogPage = coroutineScope {
        require(limit > 0)
        if (mediaTypes.isEmpty()) {
            return@coroutineScope AllCatalogPage(emptyList(), cursor, hasMore = false)
        }
        val pages = mediaTypes.filterNot { it in cursor.exhausted }.associateWith { mediaType ->
            async {
                search(
                    mediaType = mediaType,
                    query = query,
                    sortOrder = sortOrder,
                    filters = filters,
                    mergeEnabled = mergeByMedia[mediaType] == true,
                    limit = limit,
                    offset = cursor.offsets[mediaType] ?: 0
                )
            }
        }.mapValues { it.value.await() }
        mergeAllCatalogPage(mediaTypes, pages, sortOrder, limit, cursor)
    }

    suspend fun rankingAll(
        mediaTypes: List<MediaType>,
        filters: GenericMediaFilters,
        sortOrder: RankingSortOrder,
        categoryId: String?,
        descending: Boolean,
        mergeByMedia: Map<MediaType, Boolean>
    ): List<MediaListEntry> = coroutineScope {
        val startedAt = System.nanoTime()
        val result = mediaTypes.map { mediaType ->
            async {
                ranking(
                    mediaType,
                    filters,
                    sortOrder,
                    categoryId,
                    descending,
                    mergeByMedia[mediaType] == true
                )
            }
        }.flatMap { it.await() }.sortedWith { left, right ->
            val leftScore = left.rankingScore(sortOrder)
            val rightScore = right.rankingScore(sortOrder)
            when {
                leftScore == null && rightScore == null -> 0
                leftScore == null -> 1
                rightScore == null -> -1
                else -> {
                    val score = if (descending) rightScore.compareTo(leftScore)
                    else leftScore.compareTo(rightScore)
                    if (score != 0) score else compareValuesBy(
                        left,
                        right,
                        { it.title.lowercase() },
                        { it.mediaType.persistedValue },
                        { it.id }
                    )
                }
            }
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                CATALOG_LOG_TAG,
                "scope=all operation=ranking packs=${mediaTypes.size} rows=${result.size} " +
                    "elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000L}"
            )
        }
        result
    }

    private suspend fun List<MediaListEntry>.withMatchingAliasTitles(
        query: String
    ): List<MediaListEntry> {
        if (query.trim().codePointCount(0, query.trim().length) < 3) return this
        val itemIds = mapNotNull { (it as? MediaListEntry.Item)?.value?.id }
        if (itemIds.isEmpty()) return this
        val aliasesByItem = catalogDao.aliasesForMediaIds(itemIds)
            .groupBy(MediaAliasEntity::mediaId, MediaAliasEntity::title)
        return map { entry ->
            if (entry !is MediaListEntry.Item) return@map entry
            val displayTitle = bestMatchingAliasTitle(
                canonicalTitle = entry.value.title,
                aliases = aliasesByItem[entry.value.id].orEmpty(),
                rawQuery = query
            )
            if (displayTitle == null) entry
            else entry.copy(value = entry.value.copy(searchDisplayTitle = displayTitle))
        }
    }

    suspend fun ranking(
        mediaType: MediaType,
        filters: GenericMediaFilters,
        sortOrder: RankingSortOrder,
        categoryId: String?,
        descending: Boolean,
        mergeEnabled: Boolean
    ): List<MediaListEntry> = timedRead(mediaType, "ranking") {
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

    suspend fun localCoverPaths(mediaIds: Collection<String>): Map<String, String> = withContext(Dispatchers.IO) {
        mediaIds.distinct().chunked(SQLITE_ID_BATCH).flatMap { dao.coverPaths(it) }
            .mapNotNull { row -> row.localCoverPath?.let { row.mediaId to it } }
            .toMap()
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
        catalogDao.deleteCatalogReferenceData(mediaType.persistedValue)
    }

    suspend fun deleteAllPersonalData(mediaType: MediaType) = withContext(Dispatchers.IO) {
        catalogDao.deletePersonalData(mediaType.toPackId().value)
    }

    suspend fun purgePackData(mediaType: MediaType) = withContext(Dispatchers.IO) {
        catalogDao.purgePackData(
            mediaType.persistedValue,
            mediaType.toPackId().value
        )
    }

    suspend fun installPackCovers(manifest: PackManifest, directory: File) = withContext(Dispatchers.IO) {
        manifest.readCoverIndex(directory).forEach { cover ->
            val path = File(directory, cover.relativePath).absolutePath
            val item = dao.item(cover.canonicalId)
            val group = if (item == null) groupDao.group(cover.canonicalId) else null
            when {
                item != null -> {
                    val existing = item.localCoverPath?.let(::File)
                    if (existing == null || !existing.isFile || existing.length() <= 0L) {
                        dao.updateMediaCover(cover.canonicalId, path, CoverResolutionState.AVAILABLE.name)
                    }
                }
                group != null -> {
                    val existing = group.localCoverPath?.let(::File)
                    if (existing == null || !existing.isFile || existing.length() <= 0L) {
                        groupDao.updateGroupCover(cover.canonicalId, path, CoverResolutionState.AVAILABLE.name)
                    }
                }
            }
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

    /**
     * Separates coroutine-dispatch wait from database/query work in debug diagnostics. This is
     * intentionally around the complete repository read because grouped results need several
     * indexed statements; the distinction still tells us whether Room/SQLite or the IO executor
     * delayed a READY pack while another pack was importing.
     */
    private suspend fun <T> timedRead(
        mediaType: MediaType,
        operation: String,
        block: suspend () -> T
    ): T {
        val queuedAt = System.nanoTime()
        return withContext(Dispatchers.IO) {
            val startedAt = System.nanoTime()
            try {
                block().also {
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            CATALOG_LOG_TAG,
                            "pack=${mediaType.toPackId().value} operation=$operation " +
                                "dispatcherWaitMs=${(startedAt - queuedAt) / 1_000_000L} " +
                                "queryMs=${(System.nanoTime() - startedAt) / 1_000_000L} success=true"
                        )
                    }
                }
            } catch (exception: Exception) {
                Log.e(
                    CATALOG_LOG_TAG,
                    "pack=${mediaType.toPackId().value} operation=$operation " +
                        "dispatcherWaitMs=${(startedAt - queuedAt) / 1_000_000L} " +
                        "queryMs=${(System.nanoTime() - startedAt) / 1_000_000L} " +
                        "failure=${exception.javaClass.simpleName}: ${exception.message}",
                    exception
                )
                throw exception
            }
        }
    }

    private fun logReadPhase(mediaType: MediaType, operation: String, startedAt: Long) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            CATALOG_LOG_TAG,
            "pack=${mediaType.toPackId().value} operation=$operation " +
                "elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000L}"
        )
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
        val cache = sharedCoverCaches[mediaType]?.takeIf { it.groupType == groupType }
        val itemIds = entries.asSequence()
            .filterIsInstance<MediaListEntry.Item>()
            .map { it.id }
            .toList()
        val itemCoverIds = cache?.items ?: buildMap {
            itemIds.chunked(SQLITE_ID_BATCH).forEach { batch ->
                groupDao.sharedCoverIdentitiesForItems(mediaType.persistedValue, groupType, batch)
                    .forEach { put(it.targetId, it.coverMediaId) }
            }
        }
        val groupIds = entries.asSequence()
            .filterIsInstance<MediaListEntry.Group>()
            .map { it.id }
            .toList()
        val groupCoverIds = cache?.groups ?: buildMap {
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

private data class SharedCoverCache(
    val groupType: String,
    val items: Map<String, String>,
    val groups: Map<String, String>
)

internal fun mergeAllCatalogPage(
    mediaTypes: List<MediaType>,
    pages: Map<MediaType, List<MediaListEntry>>,
    sortOrder: CatalogSortOrder,
    limit: Int,
    cursor: AllCatalogCursor
): AllCatalogPage {
    require(limit > 0)
    val consumed = mediaTypes.associateWithTo(linkedMapOf()) { 0 }
    val exhausted = cursor.exhausted.toMutableSet()
    val result = ArrayList<MediaListEntry>(limit)
    var nextPopularityPack = cursor.nextPopularityPack.coerceAtLeast(0)

    if (sortOrder == CatalogSortOrder.POPULARITY ||
        sortOrder == CatalogSortOrder.POPULARITY_REVERSE
    ) {
        var withoutEmission = 0
        while (result.size < limit && withoutEmission < mediaTypes.size) {
            val index = nextPopularityPack % mediaTypes.size
            val mediaType = mediaTypes[index]
            nextPopularityPack = (index + 1) % mediaTypes.size
            val page = pages[mediaType].orEmpty()
            val position = consumed.getValue(mediaType)
            if (mediaType !in exhausted && position < page.size) {
                result += page[position]
                consumed[mediaType] = position + 1
                withoutEmission = 0
            } else {
                if (page.size < limit && position >= page.size) exhausted += mediaType
                withoutEmission++
            }
        }
    } else {
        val comparator = allTitleComparator(sortOrder)
        while (result.size < limit) {
            val next = mediaTypes.asSequence()
                .filterNot { it in exhausted }
                .mapNotNull { mediaType ->
                    pages[mediaType]?.getOrNull(consumed.getValue(mediaType))
                        ?.let { mediaType to it }
                }
                .minWithOrNull { left, right -> comparator.compare(left.second, right.second) }
                ?: break
            result += next.second
            consumed[next.first] = consumed.getValue(next.first) + 1
        }
    }

    mediaTypes.forEach { mediaType ->
        val page = pages[mediaType].orEmpty()
        if (page.size < limit && consumed.getValue(mediaType) >= page.size) {
            exhausted += mediaType
        }
    }
    val offsets = cursor.offsets.toMutableMap()
    consumed.forEach { (mediaType, count) ->
        if (count > 0) offsets[mediaType] = (offsets[mediaType] ?: 0) + count
    }
    return AllCatalogPage(
        entries = result,
        nextCursor = AllCatalogCursor(offsets, exhausted, nextPopularityPack),
        hasMore = mediaTypes.any { it !in exhausted }
    )
}

private fun allTitleComparator(sortOrder: CatalogSortOrder): Comparator<MediaListEntry> =
    Comparator { left, right ->
        // Match SQLite's built-in NOCASE collation (ASCII folding over UTF-8 bytes) so each
        // per-pack page is a valid input for this k-way merge, including non-ASCII titles.
        val title = sqliteNoCaseCompare(left.title, right.title)
        val orderedTitle = if (sortOrder == CatalogSortOrder.TITLE_DESC) -title else title
        if (orderedTitle != 0) orderedTitle
        else compareValuesBy(
            left,
            right,
            { it.mediaType.persistedValue },
            { it.id }
        )
    }

private fun sqliteNoCaseCompare(left: String, right: String): Int {
    val a = left.encodeToByteArray()
    val b = right.encodeToByteArray()
    val common = minOf(a.size, b.size)
    for (index in 0 until common) {
        val comparison = sqliteNoCaseByte(a[index]).compareTo(sqliteNoCaseByte(b[index]))
        if (comparison != 0) return comparison
    }
    return a.size.compareTo(b.size)
}

private fun sqliteNoCaseByte(value: Byte): Int {
    val unsigned = value.toInt() and 0xFF
    return if (unsigned in 0x41..0x5A) unsigned + 0x20 else unsigned
}

private const val SQLITE_ID_BATCH = 900
private const val CATALOG_LOG_TAG = "RankOfflineCatalog"

private fun MediaListEntry.rankingScore(sortOrder: RankingSortOrder): Double? = when (sortOrder) {
    RankingSortOrder.FINAL_SCORE -> finalScore
    RankingSortOrder.QUALITY_SCORE -> qualityScore
    RankingSortOrder.PERSONAL_TASTE -> additiveScore?.times(10.0)
    RankingSortOrder.CATEGORY_SCORE -> categoryScore
}?.takeIf { it.isFinite() && it in RATING_MIN..RATING_MAX }

internal fun requireGenericMediaType(persistedType: String) {
    val mediaType = MediaType.entries.firstOrNull { it.persistedValue == persistedType }
    require(mediaType != null) {
        "Media persistence does not accept media type: $persistedType"
    }
}
